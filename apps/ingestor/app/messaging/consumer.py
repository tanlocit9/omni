import asyncio
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.config import (
    DEFAULT_STOCK_SOURCE,
    KAFKA_BOOTSTRAP,
    KAFKA_CONSUMER_GROUP_ID,
    KAFKA_RETRY_INTERVAL_SECONDS,
    TOPIC_SYNC_STOCK_PRICES,
    TOPIC_SYNC_SYMBOLS,
)
from app.handlers.stock_prices import process_stock_price_message
from app.handlers.symbols import process_sync_symbols_message
from app.stocks.client_factory import close_cached_clients, get_or_create_client
from app.storage.minio_client import ensure_bucket, get_minio_client

logger = logging.getLogger(__name__)


async def _start_kafka_clients() -> tuple[AIOKafkaConsumer, AIOKafkaProducer]:
    while True:
        consumer = AIOKafkaConsumer(
            TOPIC_SYNC_STOCK_PRICES,
            TOPIC_SYNC_SYMBOLS,
            bootstrap_servers=KAFKA_BOOTSTRAP,
            group_id=KAFKA_CONSUMER_GROUP_ID,
            enable_auto_commit=True,
            auto_offset_reset="earliest",
        )
        producer = AIOKafkaProducer(bootstrap_servers=KAFKA_BOOTSTRAP)

        try:
            await consumer.start()
            await producer.start()
            await consumer.getmany(timeout_ms=1000)
            logger.info(
                "Kafka consumer ready (topics=%s,%s  bootstrap=%s  source=%s)",
                TOPIC_SYNC_STOCK_PRICES,
                TOPIC_SYNC_SYMBOLS,
                KAFKA_BOOTSTRAP,
                DEFAULT_STOCK_SOURCE,
            )
            return consumer, producer
        except Exception as exc:
            logger.warning(
                "Kafka not ready, retrying in %ds... (%s)",
                KAFKA_RETRY_INTERVAL_SECONDS,
                exc,
            )
            await consumer.stop()
            await producer.stop()
            await asyncio.sleep(KAFKA_RETRY_INTERVAL_SECONDS)


async def consume_loop() -> None:
    default_client = get_or_create_client(DEFAULT_STOCK_SOURCE)
    consumer, producer = await _start_kafka_clients()

    ensure_bucket(get_minio_client())

    try:
        async for msg in consumer:
            if msg.topic == TOPIC_SYNC_SYMBOLS:
                await process_sync_symbols_message(msg.value, producer, default_client)
            else:
                await process_stock_price_message(msg.value, producer, default_client)
    except asyncio.CancelledError:
        logger.info("Kafka consumer loop cancelled, shutting down.")
    finally:
        await consumer.stop()
        await producer.stop()
        await close_cached_clients()