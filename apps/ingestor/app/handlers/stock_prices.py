import json
import logging
from datetime import UTC, date, datetime

import pandas as pd
from aiokafka import AIOKafkaProducer

from app.messaging.messages import SymbolJobMessage
from app.messaging.status import build_status
from app.settings import settings
from app.stocks.base import StockClient
from app.stocks.client_factory import get_or_create_client

from py_common.storage.parquet import ParquetStorage

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
    parquet_storage: ParquetStorage,
) -> None:
    started_at = datetime.now(UTC)
    payload: dict[str, object] = {}
    symbol_key = None

    try:
        payload = json.loads(raw_msg.decode())
        message = SymbolJobMessage.model_validate(payload)
        payload = message.status_payload
        symbol_key = message.symbol_key
        exchange, code = message.parse_symbol_key()

        client = (
            get_or_create_client(message.source) if message.source else default_client
        )

        from_date = message.from_offset.date() if message.from_offset else None
        to_date = message.to_offset.date() if message.to_offset else date.today()

        limit = compute_limit(from_date, to_date)
        new_df = await fetch_stock_data(client, code, limit)

        object_name = settings.get_eod_path(exchange, code)
        existing_df = await parquet_storage.read_optional_dataframe(object_name)
        frames = [df for df in (existing_df, new_df) if df is not None and not df.empty]
        combined = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()

        if not combined.empty:
            combined = combined.drop_duplicates(subset=["date"])
            combined = combined.sort_values("date")

        await parquet_storage.write_dataframe(object_name, combined)

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

    result = await producer.send_and_wait(
        settings.sync_job_status_topic,
        key=status["symbolKey"].encode() if status.get("symbolKey") else None,
        value=json.dumps(status, default=str).encode(),
    )

    logger.info(
        "Published stock-price sync status for %s to topic=%s partition=%s offset=%s",
        status.get("symbolKey"),
        result.topic,
        result.partition,
        result.offset,
    )
