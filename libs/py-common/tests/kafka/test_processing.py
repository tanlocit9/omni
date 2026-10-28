from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import pytest
from aiokafka import TopicPartition
from aiokafka.errors import CommitFailedError

from py_common.config.models import KafkaSettings
from py_common.kafka.factory import KafkaClientFactory
from py_common.kafka.processing import consume_job_records
from py_common.messaging import JobStatus, JobStatusMessage, utc_now


def status(
    outcome=JobStatus.SUCCESS, execution_id="00000000-0000-0000-0000-000000000001"
):
    return JobStatusMessage(
        execution_id=execution_id,
        work_type="GLOBAL",
        work_key="test",
        status=outcome,
        started_at=utc_now(),
        finished_at=utc_now(),
    )


def record(partition=0, offset=0):
    return SimpleNamespace(topic="jobs", partition=partition, offset=offset)


class Consumer:
    def __init__(self, records):
        self.records = iter(records)
        self.commit = AsyncMock()
        self.fetched = []

    def __aiter__(self):
        return self

    async def __anext__(self):
        result = next(self.records, None)
        if result is None:
            raise StopAsyncIteration
        self.fetched.append(result)
        return result


@pytest.mark.parametrize("topics", ["jobs", ["jobs", "more-jobs"]])
def test_factory_disables_implicit_commits_for_every_subscription(topics):
    with patch("py_common.kafka.factory.AIOKafkaConsumer", Mock()) as constructor:
        KafkaClientFactory.create_consumer(KafkaSettings(), topics, "test-group")
    assert constructor.call_args.kwargs["enable_auto_commit"] is False
    assert constructor.call_args.kwargs["group_id"] == "test-group"
    assert constructor.call_args.kwargs["auto_offset_reset"] == "earliest"
    expected_topics = topics if isinstance(topics, list) else [topics]
    assert constructor.call_args.args == tuple(expected_topics)


@pytest.mark.asyncio
@pytest.mark.parametrize("outcome", list(JobStatus))
async def test_commit_follows_output_and_status_ack_for_only_processed_partition(
    outcome,
):
    consumer = Consumer([record(1, 7), record(0, 2)])
    events = []

    async def process(msg):
        events.extend([("output-ack", msg.offset), ("status-ack", msg.offset)])
        return status(outcome)

    async def commit(offsets):
        events.append(("commit", offsets))

    consumer.commit.side_effect = commit
    await consume_job_records(consumer, process)
    assert events == [
        ("output-ack", 7),
        ("status-ack", 7),
        ("commit", {TopicPartition("jobs", 1): 8}),
        ("output-ack", 2),
        ("status-ack", 2),
        ("commit", {TopicPartition("jobs", 0): 3}),
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["output", "status", "decode"])
async def test_failed_record_stops_before_later_partition_or_offset(failure):
    records = [record(0, 0), record(0, 1), record(1, 9)]
    consumer = Consumer(records)
    process = AsyncMock(side_effect=[status(), RuntimeError(failure)])
    with pytest.raises(RuntimeError, match=failure):
        await consume_job_records(consumer, process)
    consumer.commit.assert_awaited_once_with({TopicPartition("jobs", 0): 1})
    assert consumer.fetched == records[:2]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "result", [None, status(execution_id=None), status(execution_id=" ")]
)
async def test_unacknowledged_or_unidentifiable_poison_record_is_not_skipped(result):
    first = record()
    consumer = Consumer([first, record(offset=1)])
    with pytest.raises(ValueError, match="acknowledged execution status"):
        await consume_job_records(consumer, AsyncMock(return_value=result))
    consumer.commit.assert_not_awaited()
    assert consumer.fetched == [first]


@pytest.mark.asyncio
async def test_malformed_execution_uuid_is_not_committed():
    consumer = Consumer([record(), record(offset=1)])
    with pytest.raises(ValueError):
        await consume_job_records(
            consumer, AsyncMock(return_value=status(execution_id="None"))
        )
    consumer.commit.assert_not_awaited()
    assert len(consumer.fetched) == 1


@pytest.mark.asyncio
async def test_cancellation_during_status_publish_does_not_commit():
    consumer = Consumer([record()])
    entered = asyncio.Event()

    async def process(_):
        entered.set()
        await asyncio.Future()

    task = asyncio.create_task(consume_job_records(consumer, process))
    await entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    consumer.commit.assert_not_awaited()


@pytest.mark.asyncio
async def test_rebalance_commit_failure_stops_without_consuming_later_records():
    first = record()
    consumer = Consumer([first, record(offset=1)])
    consumer.commit.side_effect = CommitFailedError()
    with pytest.raises(CommitFailedError):
        await consume_job_records(consumer, AsyncMock(return_value=status()))
    assert consumer.fetched == [first]
