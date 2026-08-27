from __future__ import annotations

import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from py_common.messaging import JobStatusMessage, JobStatusPublisher


class FakeProducer:
    def __init__(self) -> None:
        self.sent = []

    async def send_and_wait(self, topic, payload, key=None):
        self.sent.append((topic, payload, key))
        return SimpleNamespace(topic=topic, partition=1, offset=2)


@pytest.mark.anyio
async def test_job_status_publisher_serializes_aliases_and_work_identity():
    producer = FakeProducer()
    publisher = JobStatusPublisher(producer, "topic-sync-job-status", "test")
    timestamp = datetime(2026, 1, 1, tzinfo=UTC)

    await publisher.publish(
        JobStatusMessage(
            job_definition_id="job-definition-id",
            execution_id="execution-id",
            work_type="SYMBOL",
            work_key="HOSE-HPG",
            status="SUCCESS",
            started_at=timestamp,
            finished_at=timestamp,
            records_processed=1,
        )
    )

    assert len(producer.sent) == 1
    topic, payload, key = producer.sent[0]
    assert topic == "topic-sync-job-status"
    assert key == b"HOSE-HPG"
    decoded = json.loads(payload.decode("utf-8"))
    assert decoded["jobDefinitionId"] == "job-definition-id"
    assert decoded["executionId"] == "execution-id"
    assert decoded["workType"] == "SYMBOL"
    assert decoded["workKey"] == "HOSE-HPG"
    assert decoded["recordsProcessed"] == 1


@pytest.mark.anyio
async def test_job_status_publisher_uses_explicit_key_for_non_symbol_status():
    producer = FakeProducer()
    publisher = JobStatusPublisher(producer, "topic-sync-job-status", "test")
    timestamp = datetime(2026, 1, 1, tzinfo=UTC)

    await publisher.publish(
        JobStatusMessage(
            job_definition_id="job-definition-id",
            execution_id="execution-id",
            work_type="EXCHANGE",
            work_key="HOSE",
            status="SUCCESS",
            started_at=timestamp,
            finished_at=timestamp,
            records_processed=1,
            exchange="HOSE",
        ),
        key="HOSE",
    )

    assert len(producer.sent) == 1
    topic, payload, key = producer.sent[0]
    assert topic == "topic-sync-job-status"
    assert key == b"HOSE"
    decoded = json.loads(payload.decode("utf-8"))
    assert decoded["exchange"] == "HOSE"
    assert decoded["workType"] == "EXCHANGE"
    assert decoded["workKey"] == "HOSE"
