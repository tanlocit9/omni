from __future__ import annotations

import asyncio
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from py_common.config import ConsumerGroup
from py_common.kafka import KafkaClientFactory
from py_common.kafka.processing import consume_job_records
from py_common.messaging import JobStatusMessage, JobStatusPublisher
from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.exceptions import StorageValidationError
from py_common.storage.immutable_publication import ImmutableDatasetPublisher
from py_common.storage.parquet import ParquetStorage
from py_common.storage.ports import ReadableStorage, WritableStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.handlers.intraday_eod import process_intraday_eod_message
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
        self._immutable_publisher: ImmutableDatasetPublisher | None = None

    async def run(self) -> None:
        logger.info(
            "Starting ingestor consume loop (topics=%s,%s statusTopic=%s "
            "bootstrap=%s bucket=%s defaultStockSource=%s)",
            self._settings.topic_sync_stock_prices,
            self._settings.topic_sync_symbols,
            self._settings.sync_job_status_topic,
            self._settings.kafka.bootstrap_servers,
            self._settings.minio.bucket,
            self._settings.default_stock_source,
        )
        try:
            self._default_client = get_or_create_client(
                self._settings.default_stock_source
            )
            self._consumer, self._producer = await self._start_kafka_clients()
            self._status_publisher = JobStatusPublisher(
                self._producer, self._settings.sync_job_status_topic, "ingestor"
            )
            (
                self._parquet_storage,
                self._immutable_publisher,
            ) = await self._create_storage()
            logger.info("Ingestor waiting for Kafka messages")
            await consume_job_records(self._consumer, self._route_message)
        except asyncio.CancelledError:
            logger.info("Kafka consumer loop cancelled, shutting down.")
            raise
        finally:
            await self.stop()

    async def stop(self) -> None:
        try:
            if self._consumer is not None:
                await self._consumer.stop()
        finally:
            try:
                if self._producer is not None:
                    await self._producer.stop()
            finally:
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
                        self._settings.topic_sync_intraday_eod,
                        self._settings.topic_sync_symbols,
                    ],
                    group_id=ConsumerGroup.INGESTOR.for_topic("sync-jobs"),
                )
                producer = KafkaClientFactory.create_producer(self._settings.kafka)
                await consumer.start()
                await producer.start()
                return consumer, producer
            except (Exception, asyncio.CancelledError) as exc:
                logger.warning(
                    "Kafka not ready, retrying in %ds... (%s)",
                    self._settings.kafka_retry_interval_seconds,
                    exc,
                )
                try:
                    if consumer is not None:
                        await consumer.stop()
                finally:
                    if producer is not None:
                        await producer.stop()
                if isinstance(exc, asyncio.CancelledError):
                    raise
                await asyncio.sleep(self._settings.kafka_retry_interval_seconds)

    async def _create_storage(self) -> tuple[ParquetStorage, ImmutableDatasetPublisher]:
        registry = create_storage_registry(self._settings)
        try:
            await registry.validate_all(fail_fast=True)
        except StorageValidationError as e:
            logger.critical("Storage validation failed: %s", e)
            raise
        minio_adapter = registry.get_adapter(StorageProvider.MINIO)
        await minio_adapter.ensure_bucket(self._settings.minio.bucket)
        parquet = ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket=self._settings.minio.bucket,
        )
        publisher = ImmutableDatasetPublisher(
            registry.get_port(StorageProvider.MINIO, ReadableStorage),
            registry.get_port(StorageProvider.MINIO, WritableStorage),
            self._settings.minio.bucket,
        )
        return parquet, publisher

    async def _route_message(self, msg) -> JobStatusMessage:
        assert self._producer is not None
        assert self._status_publisher is not None
        assert self._default_client is not None
        assert self._parquet_storage is not None
        assert self._immutable_publisher is not None

        if msg.topic == self._settings.topic_sync_intraday_eod:
            return await process_intraday_eod_message(
                msg.value,
                self._status_publisher,
                self._immutable_publisher,
                self._parquet_storage,
            )
        if msg.topic == self._settings.topic_sync_symbols:
            return await process_sync_symbols_message(
                msg.value,
                self._producer,
                self._status_publisher,
                self._default_client,
                self._parquet_storage,
            )
        return await process_stock_price_message(
            msg.value,
            self._status_publisher,
            self._default_client,
            self._parquet_storage,
        )


def create_storage_registry(
    app_settings: Settings = settings,
) -> StorageProviderRegistry:
    minio_client = create_minio_client(app_settings.minio)
    minio_adapter = MinioStorageAdapter(minio_client)
    return StorageProviderRegistry([minio_adapter])


async def consume_loop() -> None:
    await IngestorKafkaRoutingService(settings).run()
