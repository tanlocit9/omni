import asyncio
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.handlers.stock_prices import process_stock_price_message
from app.handlers.symbols import process_sync_symbols_message
from app.settings import settings
from app.stocks.client_factory import close_cached_clients, get_or_create_client
from app.storage.minio_client import ensure_bucket, get_minio_client

logger = logging.getLogger(__name__)


async def _start_kafka_clients() -> tuple[AIOKafkaConsumer, AIOKafkaProducer]:
    while True:
        consumer = AIOKafkaConsumer(
            settings.topic_sync_stock_prices,
            settings.topic_sync_symbols,
            bootstrap_servers=settings.kafka_bootstrap,
            group_id=settings.kafka_consumer_group_id,
            enable_auto_commit=True,
            auto_offset_reset="earliest",
        )
        producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap)

        try:
            await consumer.start()
            await producer.start()
            await consumer.getmany(timeout_ms=1000)
            logger.info(
                "Kafka consumer ready (topics=%s,%s  bootstrap=%s  source=%s)",
                settings.topic_sync_stock_prices,
                settings.topic_sync_symbols,
                settings.kafka_bootstrap,
                settings.default_stock_source,
            )
            return consumer, producer
        except Exception as exc:
            logger.warning(
                "Kafka not ready, retrying in %ds... (%s)",
                settings.kafka_retry_interval_seconds,
                exc,
            )
            await consumer.stop()
            await producer.stop()
            await asyncio.sleep(settings.kafka_retry_interval_seconds)


async def consume_loop() -> None:
    default_client = get_or_create_client(settings.default_stock_source)
    consumer, producer = await _start_kafka_clients()

    ensure_bucket(get_minio_client())

    try:
        async for msg in consumer:
            if msg.topic == settings.topic_sync_symbols:
                await process_sync_symbols_message(msg.value, producer, default_client)
            else:
                await process_stock_price_message(msg.value, producer, default_client)
    except asyncio.CancelledError:
        logger.info("Kafka consumer loop cancelled, shutting down.")
    finally:
        await consumer.stop()
        await producer.stop()
        await close_cached_clients()