import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from aiokafka import TopicPartition

from app.kafka_consumer import consume_loop
from app.messaging.consumer import IngestorKafkaRoutingService


def test_kafka_consumer_exports_consume_loop():
    assert callable(consume_loop)


@pytest.mark.anyio
async def test_ingestor_routing_service_dispatches_symbol_topic():
    settings = SimpleNamespace(
        topic_sync_symbols="topic-sync-symbols",
        topic_sync_stock_prices="topic-sync-stock-prices",
        topic_sync_intraday_eod="topic-sync-intraday-eod",
    )
    service = IngestorKafkaRoutingService(settings)
    service._producer = object()
    service._status_publisher = object()
    service._default_client = object()
    service._parquet_storage = object()
    service._immutable_publisher = object()
    service._settings = settings
    message = SimpleNamespace(
        topic="topic-sync-symbols",
        partition=0,
        offset=1,
        key=b"HOSE",
        value=b"{}",
    )

    with pytest.MonkeyPatch.context() as monkeypatch:
        stock_handler = AsyncMock()
        symbol_handler = AsyncMock()
        monkeypatch.setattr(
            "app.messaging.consumer.process_stock_price_message",
            stock_handler,
        )
        monkeypatch.setattr(
            "app.messaging.consumer.process_sync_symbols_message",
            symbol_handler,
        )

        await service._route_message(message)

    symbol_handler.assert_awaited_once_with(
        b"{}",
        service._producer,
        service._status_publisher,
        service._default_client,
        service._parquet_storage,
    )
    stock_handler.assert_not_awaited()


@pytest.mark.anyio
async def test_ingestor_routing_service_dispatches_stock_price_topic():
    settings = SimpleNamespace(
        topic_sync_symbols="topic-sync-symbols",
        topic_sync_stock_prices="topic-sync-stock-prices",
        topic_sync_intraday_eod="topic-sync-intraday-eod",
    )
    service = IngestorKafkaRoutingService(settings)
    service._producer = object()
    service._status_publisher = object()
    service._default_client = object()
    service._parquet_storage = object()
    service._immutable_publisher = object()
    service._settings = settings
    message = SimpleNamespace(
        topic="topic-sync-stock-prices",
        partition=0,
        offset=1,
        key=b"HOSE-FPT",
        value=b"{}",
    )

    with pytest.MonkeyPatch.context() as monkeypatch:
        stock_handler = AsyncMock()
        symbol_handler = AsyncMock()
        monkeypatch.setattr(
            "app.messaging.consumer.process_stock_price_message",
            stock_handler,
        )
        monkeypatch.setattr(
            "app.messaging.consumer.process_sync_symbols_message",
            symbol_handler,
        )

        await service._route_message(message)

    stock_handler.assert_awaited_once_with(
        b"{}",
        service._status_publisher,
        service._default_client,
        service._parquet_storage,
    )
    symbol_handler.assert_not_awaited()


def kafka_settings():
    return SimpleNamespace(
        topic_sync_symbols="topic-sync-symbols",
        topic_sync_stock_prices="topic-sync-stock-prices",
        topic_sync_intraday_eod="topic-sync-intraday-eod",
        sync_job_status_topic="statuses",
        kafka=SimpleNamespace(bootstrap_servers="unused"),
        minio=SimpleNamespace(bucket="unused"),
        default_stock_source="test",
        kafka_retry_interval_seconds=0,
    )


class Consumer:
    def __init__(self, records):
        self.records = iter(records)
        self.commit = AsyncMock()
        self.stop = AsyncMock()
        self.start = AsyncMock()
        self.getmany = AsyncMock(side_effect=AssertionError("startup must not poll"))

    def __aiter__(self):
        return self

    async def __anext__(self):
        msg = next(self.records, None)
        if msg is None:
            raise StopAsyncIteration
        return msg


@pytest.mark.anyio
async def test_startup_retains_first_batch_and_retries_client_start_failure(
    monkeypatch,
):
    service = IngestorKafkaRoutingService(kafka_settings())
    failed, consumer, producer = Consumer([]), Consumer([]), AsyncMock()
    failed.start.side_effect = RuntimeError("broker unavailable")
    factory = "app.messaging.consumer.KafkaClientFactory."
    monkeypatch.setattr(
        factory + "create_consumer", Mock(side_effect=[failed, consumer])
    )
    monkeypatch.setattr(factory + "create_producer", Mock(return_value=producer))
    actual_consumer, actual_producer = await service._start_kafka_clients()
    assert actual_consumer is consumer and actual_producer is producer
    failed.stop.assert_awaited_once()
    consumer.getmany.assert_not_awaited()
    failed.getmany.assert_not_awaited()


@pytest.mark.anyio
@pytest.mark.parametrize(
    "topic",
    ["topic-sync-symbols", "topic-sync-stock-prices", "topic-sync-intraday-eod"],
)
@pytest.mark.parametrize("publish_fails", [False, True])
async def test_run_commits_first_record_only_after_handler_status_ack(
    monkeypatch, topic, publish_fails
):
    from py_common.messaging import JobStatusMessage, utc_now

    message = SimpleNamespace(topic=topic, partition=1, offset=7, value=b"{}")
    consumer = Consumer([message])
    producer = AsyncMock()
    service = IngestorKafkaRoutingService(kafka_settings())
    service._start_kafka_clients = AsyncMock(return_value=(consumer, producer))
    service._create_storage = AsyncMock(return_value=(object(), object()))
    monkeypatch.setattr("app.messaging.consumer.get_or_create_client", Mock())
    close = AsyncMock()
    monkeypatch.setattr("app.messaging.consumer.close_cached_clients", close)

    async def handler(*args):
        consumer.commit.assert_not_awaited()
        if publish_fails:
            raise RuntimeError("status not acknowledged")
        return JobStatusMessage(
            execution_id="00000000-0000-0000-0000-000000000001",
            work_type="GLOBAL",
            work_key="test",
            status="SUCCESS",
            started_at=utc_now(),
            finished_at=utc_now(),
        )

    for name in (
        "process_sync_symbols_message",
        "process_stock_price_message",
        "process_intraday_eod_message",
    ):
        monkeypatch.setattr("app.messaging.consumer." + name, handler)
    if publish_fails:
        with pytest.raises(RuntimeError, match="status not acknowledged"):
            await service.run()
        consumer.commit.assert_not_awaited()
    else:
        await service.run()
        consumer.commit.assert_awaited_once_with({TopicPartition(topic, 1): 8})
    consumer.getmany.assert_not_awaited()
    consumer.stop.assert_awaited_once()
    producer.stop.assert_awaited_once()
    close.assert_awaited_once()


@pytest.mark.anyio
async def test_storage_startup_failure_also_closes_started_kafka_clients(monkeypatch):
    service = IngestorKafkaRoutingService(kafka_settings())
    consumer, producer = Consumer([]), AsyncMock()
    service._start_kafka_clients = AsyncMock(return_value=(consumer, producer))
    service._create_storage = AsyncMock(side_effect=RuntimeError("storage unavailable"))
    monkeypatch.setattr("app.messaging.consumer.get_or_create_client", Mock())
    monkeypatch.setattr("app.messaging.consumer.close_cached_clients", AsyncMock())
    with pytest.raises(RuntimeError, match="storage unavailable"):
        await service.run()
    consumer.stop.assert_awaited_once()
    producer.stop.assert_awaited_once()


@pytest.mark.anyio
async def test_cancelled_handler_does_not_commit_and_closes_clients(monkeypatch):
    service = IngestorKafkaRoutingService(kafka_settings())
    consumer, producer = (
        Consumer([SimpleNamespace(topic="jobs", partition=0, offset=0)]),
        AsyncMock(),
    )
    service._start_kafka_clients = AsyncMock(return_value=(consumer, producer))
    service._create_storage = AsyncMock(return_value=(object(), object()))
    service._route_message = AsyncMock(side_effect=asyncio.CancelledError())
    monkeypatch.setattr("app.messaging.consumer.get_or_create_client", Mock())
    monkeypatch.setattr("app.messaging.consumer.close_cached_clients", AsyncMock())
    with pytest.raises(asyncio.CancelledError):
        await service.run()
    consumer.commit.assert_not_awaited()
    consumer.stop.assert_awaited_once()
    producer.stop.assert_awaited_once()


@pytest.mark.anyio
async def test_stop_without_started_clients_still_closes_cached_providers(monkeypatch):
    close = AsyncMock()
    monkeypatch.setattr("app.messaging.consumer.close_cached_clients", close)
    await IngestorKafkaRoutingService(kafka_settings()).stop()
    close.assert_awaited_once()


@pytest.mark.anyio
async def test_cancel_during_initial_kafka_connection_closes_all_owned_resources(
    monkeypatch,
):
    service = IngestorKafkaRoutingService(kafka_settings())
    consumer, producer = AsyncMock(), AsyncMock()
    consumer.start.side_effect = asyncio.CancelledError()
    close = AsyncMock()
    monkeypatch.setattr("app.messaging.consumer.get_or_create_client", Mock())
    monkeypatch.setattr("app.messaging.consumer.close_cached_clients", close)
    monkeypatch.setattr(
        "app.messaging.consumer.KafkaClientFactory.create_consumer",
        Mock(return_value=consumer),
    )
    monkeypatch.setattr(
        "app.messaging.consumer.KafkaClientFactory.create_producer",
        Mock(return_value=producer),
    )
    with pytest.raises(asyncio.CancelledError):
        await service.run()
    assert not service.is_running
    consumer.stop.assert_awaited_once()
    producer.stop.assert_awaited_once()
    close.assert_awaited_once()


@pytest.mark.anyio
@pytest.mark.parametrize("failure", ["consumer", "producer"])
async def test_client_construction_failure_retries_without_polling_or_leaking(
    monkeypatch, failure
):
    service = IngestorKafkaRoutingService(kafka_settings())
    first, consumer, producer = Consumer([]), Consumer([]), AsyncMock()
    factory = "app.messaging.consumer.KafkaClientFactory."
    make_consumer = Mock(
        side_effect=[RuntimeError("construct"), consumer]
        if failure == "consumer"
        else [first, consumer]
    )
    make_producer = Mock(
        side_effect=[RuntimeError("construct"), producer]
        if failure == "producer"
        else [producer]
    )
    monkeypatch.setattr(factory + "create_consumer", make_consumer)
    monkeypatch.setattr(factory + "create_producer", make_producer)
    assert await service._start_kafka_clients() == (consumer, producer)
    if failure == "producer":
        first.stop.assert_awaited_once()
    consumer.getmany.assert_not_awaited()


@pytest.mark.anyio
async def test_storage_is_validated_before_consumer_can_publish(monkeypatch):
    service = IngestorKafkaRoutingService(kafka_settings())
    registry = Mock()
    registry.validate_all = AsyncMock()
    adapter = Mock(ensure_bucket=AsyncMock())
    registry.get_adapter.return_value = adapter
    monkeypatch.setattr(
        "app.messaging.consumer.create_storage_registry", Mock(return_value=registry)
    )
    parquet, publisher = await service._create_storage()
    assert parquet is not None and publisher is not None
    registry.validate_all.assert_awaited_once_with(fail_fast=True)
    adapter.ensure_bucket.assert_awaited_once_with("unused")

    from py_common.storage.exceptions import StorageValidationError

    registry.validate_all.side_effect = StorageValidationError(
        "minio", RuntimeError("invalid storage")
    )
    adapter.ensure_bucket.reset_mock()
    with pytest.raises(StorageValidationError):
        await service._create_storage()
    adapter.ensure_bucket.assert_not_awaited()
