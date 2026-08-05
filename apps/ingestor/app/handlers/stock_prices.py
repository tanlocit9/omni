import logging
from datetime import date

import pandas as pd
from py_common.kafka import decode_json_object_payload
from py_common.messaging import JobStatus, JobStatusMessage, JobStatusPublisher, utc_now
from py_common.storage.parquet import ParquetStorage

from app.messaging.messages import SymbolJobMessage
from app.messaging.status import build_status, status_publish_key
from app.settings import settings
from app.stocks.base import StockClient
from app.stocks.client_factory import get_or_create_client
from app.stocks.normalization import normalize_record_list_keys

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


def normalize_stock_price_dataframe_columns(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return df.copy()

    normalized_df = pd.DataFrame(normalize_record_list_keys(df.to_dict("records")))

    if "date" not in normalized_df.columns:
        raise ValueError("Missing required stock price field: 'date'")

    return normalized_df


async def process_stock_price_message(
    raw_msg: str | bytes | dict[str, object],
    status_publisher: JobStatusPublisher,
    default_client: StockClient,
    parquet_storage: ParquetStorage,
) -> JobStatusMessage:
    started_at = utc_now()
    payload: dict[str, object] = {}
    symbol_key = None

    try:
        payload = decode_json_object_payload(raw_msg, "Stock price sync job")
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
            combined = normalize_stock_price_dataframe_columns(combined)
            combined = combined.drop_duplicates(subset=["date"])
            combined = combined.sort_values("date")

        await parquet_storage.write_dataframe(object_name, combined)

        status = build_status(
            "symbolKey",
            symbol_key,
            payload,
            started_at,
            JobStatus.SUCCESS,
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
            JobStatus.ERROR,
            error_message=str(exc),
        )

    await status_publisher.publish(status, key=status_publish_key(status, "symbolKey"))
    return status
