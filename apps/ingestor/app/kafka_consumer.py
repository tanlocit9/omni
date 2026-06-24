import asyncio
import io
import json
import logging
import os
from datetime import datetime, date

import pandas as pd
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from dotenv import load_dotenv
from minio import Minio

from app.clients.vndirect_client import VNDirectClient

vnd_client = VNDirectClient()
# Load environment variables from .env if present
load_dotenv()

# ----------------------------------------------------------------------
# Configuration (environment variables with sensible defaults)
# ----------------------------------------------------------------------
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP", "localhost:9092")
SYNC_SYMBOLS_JOBS = os.getenv("SYNC_SYMBOLS_JOBS", "sync-symbols-job")
STATUS_TOPIC = os.getenv("STATUS_TOPIC", "stock-sync-status")

MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.getenv("MINIO_BUCKET", "stock-data")
PARQUET_PREFIX = os.getenv("PARQUET_PREFIX", "parquet/")

logger = logging.getLogger(__name__)

# Large sentinel value used when fromOffset is absent ("get all").
_ALL_RECORDS_LIMIT = 10_000


# ----------------------------------------------------------------------
# Helper utilities
# ----------------------------------------------------------------------
def get_minio_client() -> Minio:
    """Create a MinIO client using the configured credentials."""
    return Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=False,
    )


def compute_limit(
    from_date: date | None,
    to_date: date,
) -> int:
    if from_date is None:
        return _ALL_RECORDS_LIMIT

    return max((to_date - from_date).days + 1, 1)


async def fetch_stock_data(symbol: str, limit: int) -> pd.DataFrame:
    records = await vnd_client.fetch_recent_stock(
        symbol=symbol,
        size=limit,
    )

    if not records:
        return pd.DataFrame()
    return pd.DataFrame(records)


def read_existing_parquet(client: Minio, object_name: str) -> pd.DataFrame:
    """Attempt to read an existing Parquet file from MinIO; return empty DataFrame on failure."""
    try:
        response = client.get_object(MINIO_BUCKET, object_name)
        return pd.read_parquet(response)
    except Exception:
        # File does not exist or cannot be read
        return pd.DataFrame()


def write_parquet_to_minio(client: Minio, df: pd.DataFrame, object_name: str) -> None:
    """Write a DataFrame as Parquet back to MinIO."""
    buffer = io.BytesIO()
    df.to_parquet(buffer, index=False)
    buffer.seek(0)
    client.put_object(
        MINIO_BUCKET,
        object_name,
        data=buffer,
        length=buffer.getbuffer().nbytes,
        content_type="application/octet-stream",
    )


async def process_message(raw_msg: bytes, producer: AIOKafkaProducer) -> None:
    """
    Process a single Kafka message:
    1. Parse JSON payload.
    2. Compute limit from ``fromOffset`` / ``toOffset``.
    3. Fetch new stock data.
    4. Merge with existing Parquet in MinIO.
    5. Upload updated Parquet.
    6. Publish a status message to the STATUS_TOPIC.

    Expected payload fields
    -----------------------
    symbol      : str          (required)
    fromOffset  : str | null   ISO-8601 date/datetime; null → fetch all
    toOffset    : str | null   ISO-8601 date/datetime; null → today
    jobId       : str | null   (passed through to status for traceability)
    logId       : str | null   (passed through to status for traceability)
    """
    start_ts = asyncio.get_event_loop().time()
    payload: dict = {}
    try:
        payload = json.loads(raw_msg.decode())
        symbol = payload["symbol"]
        from_offset = payload.get("fromOffset")
        to_offset = payload.get("toOffset")
        from_date = date.fromisoformat(from_offset[:10]) if from_offset else None
        to_date = date.fromisoformat(to_offset[:10]) if to_offset else date.today()

        limit = compute_limit(from_date, to_date)
        logger.info(
            "Processing sync for symbol=%s  fromOffset=%s  toOffset=%s  limit=%d",
            symbol,
            from_offset,
            to_offset,
            limit,
        )

        # 1️⃣ Fetch new data
        new_df = await fetch_stock_data(symbol, limit)

        # 2️⃣ Read existing Parquet (if any)
        client = get_minio_client()
        object_name = f"{PARQUET_PREFIX}{symbol}.parquet"
        existing_df = read_existing_parquet(client, object_name)

        # 3️⃣ Merge & deduplicate
        combined = pd.concat([existing_df, new_df])
        combined = combined.drop_duplicates(subset=["date"])
        combined = combined.sort_values("date")

        # 4️⃣ Write back to MinIO
        write_parquet_to_minio(client, combined, object_name)

        # 5️⃣ Build success status
        status = {
            "symbol": symbol,
            "jobId": payload.get("jobId"),
            "logId": payload.get("logId"),
            "status": "success",
            "recordsInserted": len(new_df),
            "totalRecords": len(combined),
            "durationMs": int((asyncio.get_event_loop().time() - start_ts) * 1000),
            "errorMessage": None,
        }
    except Exception as exc:
        logger.exception("Failed to process sync message: %s", exc)
        status = {
            "symbol": payload.get("symbol", "unknown"),
            "jobId": payload.get("jobId"),
            "logId": payload.get("logId"),
            "status": "error",
            "recordsInserted": 0,
            "totalRecords": 0,
            "durationMs": int((asyncio.get_event_loop().time() - start_ts) * 1000),
            "errorMessage": str(exc),
        }

    # Publish status message
    await producer.send_and_wait(STATUS_TOPIC, json.dumps(status).encode())


def ensure_bucket(client: Minio) -> None:
    if not client.bucket_exists(MINIO_BUCKET):
        client.make_bucket(MINIO_BUCKET)
        logger.info("Created MinIO bucket: %s", MINIO_BUCKET)


async def consume_loop() -> None:
    KAFKA_RETRY_INTERVAL = 3

    while True:
        consumer = AIOKafkaConsumer(
            SYNC_SYMBOLS_JOBS,
            bootstrap_servers=KAFKA_BOOTSTRAP,
            group_id="analyzer-group",
            enable_auto_commit=True,
            auto_offset_reset="earliest",
        )
        producer = AIOKafkaProducer(bootstrap_servers=KAFKA_BOOTSTRAP)
        try:
            await consumer.start()
            await producer.start()

            # Probe: actually attempt a fetch to confirm the group
            # coordinator is elected. This is what triggers Error 15.
            await consumer.getmany(timeout_ms=1000)

            logger.info(
                "Kafka consumer ready (topic=%s  bootstrap=%s)",
                SYNC_SYMBOLS_JOBS,
                KAFKA_BOOTSTRAP,
            )
            break
        except Exception as e:
            logger.warning(
                "Kafka not ready, retrying in %ds... (%s)", KAFKA_RETRY_INTERVAL, e
            )
            await consumer.stop()
            await producer.stop()
            await asyncio.sleep(KAFKA_RETRY_INTERVAL)

    # Ensure bucket exists before processing messages
    ensure_bucket(get_minio_client())

    try:
        async for msg in consumer:
            await process_message(msg.value, producer)
    except asyncio.CancelledError:
        logger.info("Kafka consumer loop cancelled, shutting down.")
    finally:
        await consumer.stop()
        await producer.stop()
        await vnd_client.close()
