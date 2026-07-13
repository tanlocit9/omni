import asyncio
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.handlers.stock_prices import process_stock_price_message
from app.handlers.symbols import process_sync_symbols_message
from app.settings import settings
from app.stocks.client_factory import close_cached_clients, get_or_create_client

from py_common.kafka.factory import KafkaClientFactory
from py_common.storage.adapters.factory import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.exceptions import StorageValidationError
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

logger = logging.getLogger(__name__)


async def _start_kafka_clients() -> tuple[AIOKafkaConsumer, AIOKafkaProducer]:
    while True:
        try:
            consumer = KafkaClientFactory.create_consumer(
                settings.kafka,
                [
                    settings.topic_sync_stock_prices,
                    settings.topic_sync_symbols,
                ],
                group_id=settings.kafka.consumer_group,
            )
            producer = KafkaClientFactory.create_producer(settings.kafka)

            await consumer.start()
            await producer.start()
            await consumer.getmany(timeout_ms=1000)  # Check connectivity
            logger.info(
                "Kafka consumer ready (topics=%s,%s  bootstrap=%s  source=%s)",
                settings.topic_sync_stock_prices,
                settings.topic_sync_symbols,
                settings.kafka.bootstrap_servers,
                settings.default_stock_source,
            )
            return consumer, producer
        except Exception as exc:
            logger.warning(
                "Kafka not ready, retrying in %ds... (%s)",
                settings.kafka_retry_interval_seconds,
                exc,
            )
            # Ensure clients are stopped if startup fails
            if "consumer" in locals() and consumer.started():
                await consumer.stop()
            if "producer" in locals() and producer.started():
                await producer.stop()
            await asyncio.sleep(settings.kafka_retry_interval_seconds)


def create_storage_registry() -> StorageProviderRegistry:
    minio_client = create_minio_client(settings.minio)
    minio_adapter = MinioStorageAdapter(minio_client)
    return StorageProviderRegistry([minio_adapter])


async def consume_loop() -> None:
    default_client = get_or_create_client(settings.default_stock_source)
    consumer, producer = await _start_kafka_clients()

    # Initialize storage
    registry = create_storage_registry()
    try:
        await registry.validate_all(fail_fast=True)
    except StorageValidationError as e:
        logger.critical("Storage validation failed: %s", e)
        raise

    minio_adapter = registry.get_adapter(StorageProvider.MINIO)
    await minio_adapter.ensure_bucket(settings.minio.bucket)

    parquet_storage = ParquetStorage(
        registry=registry,
        provider=StorageProvider.MINIO,
        bucket=settings.minio.bucket,
    )

    try:
        async for msg in consumer:
            if msg.topic == settings.topic_sync_symbols:
                await process_sync_symbols_message(
                    msg.value, producer, default_client, parquet_storage
                )
            else:
                await process_stock_price_message(
                    msg.value, producer, default_client, parquet_storage
                )
    except asyncio.CancelledError:
        logger.info("Kafka consumer loop cancelled, shutting down.")
    finally:
        await consumer.stop()
        await producer.stop()
        await close_cached_clients()
