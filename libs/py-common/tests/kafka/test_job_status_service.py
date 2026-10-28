from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import pytest
from aiokafka import TopicPartition
from test_processing import Consumer, status

from py_common.config.models import KafkaSettings
from py_common.kafka.factory import KafkaClientFactory
from py_common.kafka.job_status_service import JobStatusKafkaService
from py_common.messaging import JobStatusPublisher


class Service(JobStatusKafkaService):
    def __init__(self):
        super().__init__(
            kafka_settings=KafkaSettings(),
            input_topic="jobs",
            status_topic="statuses",
            group_id="test",
            service_name="test",
        )
        self.process_payload = AsyncMock(side_effect=self.process_payload)

    async def process_payload(self, payload):
        result = status()
        await self.publish_status(result)
        return result


def record(value):
    return SimpleNamespace(
        topic="jobs", partition=2, offset=7, key=b"test", value=value
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("payload", [b"{}", "{}"])
async def test_service_awaits_actual_status_publisher_before_committing(payload):
    service = Service()
    consumer = Consumer([record(payload)])
    service._consumer = consumer
    producer = AsyncMock()
    service._producer = producer
    producer.send_and_wait.side_effect = lambda *a, **kw: (
        consumer.commit.assert_not_awaited()
        or SimpleNamespace(topic="statuses", partition=0, offset=3)
    )
    await service._consume_loop()
    service.process_payload.assert_awaited_once_with("{}")
    producer.send_and_wait.assert_awaited_once()
    consumer.commit.assert_awaited_once_with({TopicPartition("jobs", 2): 8})
    assert isinstance(service._publisher, JobStatusPublisher)


@pytest.mark.asyncio
async def test_status_broker_failure_leaves_command_uncommitted():
    service = Service()
    service._consumer = Consumer([record(b"{}"), record(b"later")])
    service._producer = AsyncMock()
    service._producer.send_and_wait.side_effect = RuntimeError("status unavailable")
    with pytest.raises(RuntimeError, match="status unavailable"):
        await service._consume_loop()
    service._consumer.commit.assert_not_awaited()
    assert len(service._consumer.fetched) == 1


@pytest.mark.asyncio
async def test_invalid_utf8_is_not_committed():
    service = Service()
    service._consumer = Consumer([record(b"\xff")])
    with pytest.raises(UnicodeDecodeError):
        await service._consume_loop()
    service.process_payload.assert_not_awaited()
    service._consumer.commit.assert_not_awaited()


@pytest.mark.asyncio
async def test_start_and_stop_cancel_inflight_work_and_close_clients():
    service = Service()
    consumer, producer = AsyncMock(), AsyncMock()
    blocked = asyncio.Event()

    async def consume():
        blocked.set()
        await asyncio.Future()

    with (
        patch(
            "py_common.kafka.job_status_service.KafkaClientFactory.create_consumer",
            return_value=consumer,
        ),
        patch(
            "py_common.kafka.job_status_service.KafkaClientFactory.create_producer",
            return_value=producer,
        ),
    ):
        service._consume_loop = consume
        await service.start()
        await blocked.wait()
        assert service.is_running
        await service.stop()
        assert not service.is_running
    consumer.start.assert_awaited_once()
    producer.start.assert_awaited_once()
    consumer.stop.assert_awaited_once()
    producer.stop.assert_awaited_once()


@pytest.mark.asyncio
async def test_stop_closes_both_clients_even_after_task_or_consumer_failure():
    service = Service()
    service._consumer, service._producer = AsyncMock(), AsyncMock()
    producer = service._producer
    service._consumer.stop.side_effect = RuntimeError("consumer stop")
    service._task = asyncio.create_task(asyncio.sleep(0))
    await service._task
    with pytest.raises(RuntimeError, match="consumer stop"):
        await service.stop()
    producer.stop.assert_awaited_once()


@pytest.mark.asyncio
async def test_stop_without_started_clients():
    await Service().stop()


@pytest.mark.asyncio
async def test_recovery_recreates_clients_and_stop_does_not_restart(monkeypatch):
    from py_common.runtime.supervision import WorkerSupervisor

    service = Service()
    consumers = [AsyncMock(), AsyncMock()]
    producers = [AsyncMock(), AsyncMock()]
    consume = 0
    recovered = asyncio.Event()

    async def session():
        nonlocal consume
        consume += 1
        if consume == 1:
            raise RuntimeError("broker down")
        recovered.set()
        await asyncio.Future()

    service._consume_loop = session
    consumer_factory = Mock(side_effect=consumers)
    producer_factory = Mock(side_effect=producers)
    monkeypatch.setattr(KafkaClientFactory, "create_consumer", consumer_factory)
    monkeypatch.setattr(KafkaClientFactory, "create_producer", producer_factory)
    monkeypatch.setattr(
        "py_common.kafka.job_status_service.WorkerSupervisor",
        lambda *a, **kw: WorkerSupervisor(*a, **kw, retry_delay=0.01),
    )
    await service.start()
    await service.start()  # no duplicate task/session
    await asyncio.wait_for(recovered.wait(), 1)
    assert service.is_running
    await service.stop()
    assert not service.is_running
    assert consumer_factory.call_count == producer_factory.call_count == 2
    for client in consumers + producers:
        client.start.assert_awaited_once()
        client.stop.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["consumer", "producer"])
async def test_failed_initial_start_closes_partially_started_clients(
    monkeypatch, failure
):
    service = Service()
    consumer, producer = AsyncMock(), AsyncMock()
    (consumer if failure == "consumer" else producer).start.side_effect = RuntimeError(
        "startup"
    )
    monkeypatch.setattr(
        KafkaClientFactory, "create_consumer", Mock(return_value=consumer)
    )
    monkeypatch.setattr(
        KafkaClientFactory, "create_producer", Mock(return_value=producer)
    )
    with pytest.raises(RuntimeError, match="startup"):
        await service.start()
    assert not service.is_running
    consumer.stop.assert_awaited_once()
    producer.stop.assert_awaited_once()
