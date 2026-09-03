from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.kafka_consumer import consume_loop
from app.messaging.consumer import IngestorKafkaRoutingService


def test_kafka_consumer_exports_consume_loop():
    assert callable(consume_loop)


@pytest.mark.anyio
async def test_ingestor_routing_service_dispatches_symbol_topic():
    settings = SimpleNamespace(
        topic_sync_symbols="topic-sync-symbols",
        topic_sync_stock_prices="topic-sync-stock-prices",
    )
    service = IngestorKafkaRoutingService(settings)
    service._producer = object()
    service._status_publisher = object()
    service._default_client = object()
    service._parquet_storage = object()
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
    )
    service = IngestorKafkaRoutingService(settings)
    service._producer = object()
    service._status_publisher = object()
    service._default_client = object()
    service._parquet_storage = object()
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
