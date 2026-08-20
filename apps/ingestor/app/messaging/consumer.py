from __future__ import annotations

import asyncio
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from py_common.config import ConsumerGroup
from py_common.kafka import KafkaClientFactory
from py_common.messaging import JobStatusPublisher
from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.exceptions import StorageValidationError
from py_common.storage.manifest import ManifestWriter
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.handlers.stock_prices import process_stock_price_message
from app.handlers.symbols import process_sync_symbols_message
from app.settings import Settings, settings
from app.stocks.base import StockClient
from app.stocks.client_factory import close_cached_clients, get_or_create_client

logger = logging.getLogger(__name__)


class IngestorKafkaRoutingService:
    """Kafka lifecycle and topic router for ingestor jobs."""

    def __init__(self, app_settings: Settings = settings) -> None:
        self._settings = app_settings
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._status_publisher: JobStatusPublisher | None = None
        self._default_client: StockClient | None = None
        self._parquet_storage: ParquetStorage | None = None
        self._manifest_writer: ManifestWriter | None = None

    async def run(self) -> None:
        logger.info(
            "Starting ingestor consume loop (topics=%s,%s statusTopic=%s bootstrap=%s "
            "bucket=%s defaultStockSource=%s)",
            self._settings.topic_sync_stock_prices,
            self._settings.topic_sync_symbols,
            self._settings.sync_job_status_topic,
            self._settings.kafka.bootstrap_servers,
            self._settings.minio.bucket,
            self._settings.default_stock_source,
        )
        self._default_client = get_or_create_client(self._settings.default_stock_source)
        self._consumer, self._producer = await self._start_kafka_clients()
        self._status_publisher = JobStatusPublisher(
            self._producer,
            self._settings.sync_job_status_topic,
            "ingestor",
        )
        self._parquet_storage = await self._create_parquet_storage()
        self._manifest_writer = await self._create_manifest_writer()

        try:
            logger.info("Ingestor waiting for Kafka messages")
            async for msg in self._consumer:
                await self._route_message(msg)
        except asyncio.CancelledError:
            logger.info("Kafka consumer loop cancelled, shutting down.")
        finally:
            await self.stop()

    async def stop(self) -> None:
        if self._consumer is not None:
            await self._consumer.stop()
        if self._producer is not None:
            await self._producer.stop()
        await close_cached_clients()

    async def _start_kafka_clients(self) -> tuple[AIOKafkaConsumer, AIOKafkaProducer]:
        while True:
            consumer: AIOKafkaConsumer | None = None
            producer: AIOKafkaProducer | None = None
            try:
                consumer = KafkaClientFactory.create_consumer(
                    self._settings.kafka,
                    [
                        self._settings.topic_sync_stock_prices,
                        self._settings.topic_sync_symbols,
                    ],
                    group_id=ConsumerGroup.INGESTOR.for_topic("sync-jobs"),
                )
                producer = KafkaClientFactory.create_producer(self._settings.kafka)

                await consumer.start()
                await producer.start()
                logger.info(
                    "Kafka producer ready "
                    "(statusTopic=%s upsertTopics=%s,%s bootstrap=%s)",
                    self._settings.sync_job_status_topic,
                    self._settings.topic_upsert_sectors,
                    self._settings.topic_upsert_symbols,
                    self._settings.kafka.bootstrap_servers,
                )
                await consumer.getmany(timeout_ms=1000)
                logger.info(
                    "Kafka consumer ready "
                    "(topics=%s,%s groupId=%s bootstrap=%s source=%s)",
                    self._settings.topic_sync_stock_prices,
                    self._settings.topic_sync_symbols,
                    ConsumerGroup.INGESTOR.for_topic("sync-jobs"),
                    self._settings.kafka.bootstrap_servers,
                    self._settings.default_stock_source,
                )
                return consumer, producer
            except Exception as exc:
                logger.warning(
                    "Kafka not ready, retrying in %ds... (%s)",
                    self._settings.kafka_retry_interval_seconds,
                    exc,
                )
                if consumer is not None and consumer.started():
                    await consumer.stop()
                if producer is not None and producer.started():
                    await producer.stop()
                await asyncio.sleep(self._settings.kafka_retry_interval_seconds)

    async def _create_parquet_storage(self) -> ParquetStorage:
        registry = create_storage_registry(self._settings)
        try:
            await registry.validate_all(fail_fast=True)
        except StorageValidationError as e:
            logger.critical("Storage validation failed: %s", e)
            raise

        logger.info("Ingestor storage providers validated")
        minio_adapter = registry.get_adapter(StorageProvider.MINIO)
        await minio_adapter.ensure_bucket(self._settings.minio.bucket)
        logger.info("Ingestor storage bucket ready: %s", self._settings.minio.bucket)

        return ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket=self._settings.minio.bucket,
        )

    async def _create_manifest_writer(self) -> ManifestWriter:
        registry = create_storage_registry(self._settings)
        logger.info("Ingestor manifest writer initialized")
        return ManifestWriter(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket=self._settings.minio.bucket,
        )

    async def _route_message(self, msg) -> None:
        assert self._producer is not None
        assert self._status_publisher is not None
        assert self._default_client is not None
        assert self._parquet_storage is not None

        logger.info(
            "Received Kafka message topic=%s partition=%s offset=%s key=%s",
            msg.topic,
            msg.partition,
            msg.offset,
            msg.key.decode("utf-8", errors="replace") if msg.key else None,
        )
        if msg.topic == self._settings.topic_sync_symbols:
            await process_sync_symbols_message(
                msg.value,
                self._producer,
                self._status_publisher,
                self._default_client,
                self._parquet_storage,
            )
            return

        await process_stock_price_message(
            msg.value,
            self._status_publisher,
            self._default_client,
            self._parquet_storage,
            self._manifest_writer,
        )


def create_storage_registry(
    app_settings: Settings = settings,
) -> StorageProviderRegistry:
    minio_client = create_minio_client(app_settings.minio)
    minio_adapter = MinioStorageAdapter(minio_client)
    return StorageProviderRegistry([minio_adapter])


async def consume_loop() -> None:
    await IngestorKafkaRoutingService(settings).run()
