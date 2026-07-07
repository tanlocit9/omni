import json
import logging
from datetime import UTC, date, datetime
from typing import Any

import pandas as pd
from aiokafka import AIOKafkaProducer

from app.config import EOD_PREFIX, SYNC_JOB_STATUS_TOPIC
from app.messaging.status import build_status
from app.stocks.base import StockClient
from app.stocks.client_factory import get_or_create_client
from app.storage.minio_client import get_minio_client
from app.storage.parquet import read_existing_parquet, write_parquet_to_minio

logger = logging.getLogger(__name__)

_ALL_RECORDS_LIMIT = 10_000


def compute_limit(
    from_date: date | None,
    to_date: date,
) -> int:
    if from_date is None:
        return _ALL_RECORDS_LIMIT
    return max((to_date - from_date).days + 1, 1)


async def fetch_stock_data(
    client: StockClient,
    symbol: str,
    limit: int,
) -> pd.DataFrame:
    records = await client.fetch_recent_stock(symbol=symbol, size=limit)
    if not records:
        return pd.DataFrame()
    return pd.DataFrame(records)


async def process_stock_price_message(
    raw_msg: bytes,
    producer: AIOKafkaProducer,
    default_client: StockClient,
) -> None:
    started_at = datetime.now(UTC)
    payload: dict[str, Any] = {}
    symbol_key = None

    try:
        payload = json.loads(raw_msg.decode())
        symbol_key = payload["symbolKey"]
        _exchange, code = symbol_key.split("-", 1)

        metadata = payload.get("metadata") or {}
        bucket = metadata.get("bucket")
        object_name_override = metadata.get("objectName")

        source = payload.get("source")
        client = get_or_create_client(source) if source else default_client

        from_offset = payload.get("fromOffset")
        to_offset = payload.get("toOffset")
        from_date = date.fromisoformat(from_offset[:10]) if from_offset else None
        to_date = date.fromisoformat(to_offset[:10]) if to_offset else date.today()

        limit = compute_limit(from_date, to_date)
        new_df = await fetch_stock_data(client, code, limit)

        minio = get_minio_client()
        object_name = object_name_override or f"{EOD_PREFIX}{symbol_key}.parquet"
        existing_df = read_existing_parquet(minio, object_name, bucket=bucket)

        combined = pd.concat([existing_df, new_df])
        combined = combined.drop_duplicates(subset=["date"])
        combined = combined.sort_values("date")

        write_parquet_to_minio(minio, combined, object_name, bucket=bucket)

        status = build_status(
            "symbolKey",
            symbol_key,
            payload,
            started_at,
            "success",
            records_inserted=len(new_df),
            total_records=len(combined),
            new_offset=to_date.isoformat(),
        )
    except Exception as exc:
        logger.exception("Failed to process stock-price sync message: %s", exc)
        status = build_status(
            "symbolKey",
            symbol_key,
            payload,
            started_at,
            "error",
            error_message=str(exc),
        )

    await producer.send_and_wait(
        SYNC_JOB_STATUS_TOPIC,
        key=status["symbolKey"].encode() if status.get("symbolKey") else None,
        value=json.dumps(status).encode(),
    )