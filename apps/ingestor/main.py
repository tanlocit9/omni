import asyncio
import io
import json
import logging
import os
from datetime import datetime, timedelta

import pandas as pd
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from dotenv import load_dotenv
from minio import Minio

# Load environment variables from .env if present
load_dotenv()

# ----------------------------------------------------------------------
# Configuration (environment variables with sensible defaults)
# ----------------------------------------------------------------------
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP", "localhost:9092")
SYNC_TOPIC = os.getenv("SYNC_TOPIC", "stock-sync")
STATUS_TOPIC = os.getenv("STATUS_TOPIC", "stock-sync-status")

MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.getenv("MINIO_BUCKET", "stock-data")
PARQUET_PREFIX = os.getenv("PARQUET_PREFIX", "parquet/")  # e.g., parquet/XYZ.parquet

logger = logging.getLogger(__name__)


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


async def fetch_stock_data(symbol: str, limit: int) -> pd.DataFrame:
    """
    Placeholder implementation for fetching stock data.
    In a real implementation you would call the VNDirect API via httpx.
    Here we generate dummy data for demonstration purposes.
    """
    # Simulate network latency
    await asyncio.sleep(0.1)

    dates = [datetime.utcnow() - timedelta(days=i) for i in range(limit)]
    df = pd.DataFrame(
        {
            "date": dates,
            "symbol": [symbol] * limit,
            "price": [100.0 + i for i in range(limit)],
        }
    )
    return df


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
    2. Fetch new stock data.
    3. Merge with existing Parquet in MinIO.
    4. Upload updated Parquet.
    5. Publish a status message to the STATUS_TOPIC.
    """
    start_ts = asyncio.get_event_loop().time()
    try:
        payload = json.loads(raw_msg.decode())
        symbol = payload["symbol"]
        limit = int(payload.get("limit", 10))

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
            "status": "success",
            "recordsInserted": len(new_df),
            "totalRecords": len(combined),
            "durationMs": int((asyncio.get_event_loop().time() - start_ts) * 1000),
            "errorMessage": None,
        }
    except Exception as exc:
        # Build error status
        status = {
            "symbol": payload.get("symbol", "unknown") if "payload" in locals() else "unknown",
            "status": "error",
            "recordsInserted": 0,
            "totalRecords": 0,
            "durationMs": int((asyncio.get_event_loop().time() - start_ts) * 1000),
            "errorMessage": str(exc),
        }

    # Publish status message
    await producer.send_and_wait(STATUS_TOPIC, json.dumps(status).encode())


async def consume_loop() -> None:
    consumer = AIOKafkaConsumer(
        SYNC_TOPIC,
        bootstrap_servers=KAFKA_BOOTSTRAP,
        group_id="ingestor-group",
        enable_auto_commit=True,
        auto_offset_reset="earliest",
    )
    producer = AIOKafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP,
    )

    # Retry until Kafka is ready
    while True:
        try:
            await consumer.start()
            await producer.start()
            break
        except Exception as e:
            logger.warning(f"Kafka not ready, retrying in 3s... ({e})")
            await asyncio.sleep(3)

    try:
        async for msg in consumer:
            await process_message(msg.value, producer)
    except asyncio.CancelledError:
        pass
    finally:
        await consumer.stop()
        await producer.stop()


if __name__ == "__main__":
    # Entry point for `nx serve ingestor` (or `uv run python main.py`)
    asyncio.run(consume_loop())
