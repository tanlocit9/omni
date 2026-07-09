import json
import logging
import math
from datetime import UTC, datetime
from typing import Any

import pandas as pd
from aiokafka import AIOKafkaProducer

from app.messaging.status import build_status
from app.settings import settings
from app.stocks.base import StockClient
from app.stocks.client_factory import get_or_create_client
from app.stocks.sectors_cache import get_cached_sectors
from app.storage.minio_client import get_minio_client
from app.storage.parquet import write_parquet_to_minio

logger = logging.getLogger(__name__)


async def process_sync_symbols_message(
    raw_msg: bytes,
    producer: AIOKafkaProducer,
    default_client: StockClient,
) -> None:
    started_at = datetime.now(UTC)
    payload: dict[str, Any] = {}
    exchange = None

    try:
        payload = json.loads(raw_msg.decode())
        exchange = payload["exchange"]
        metadata = payload.get("metadata") or {}
        expected_count = metadata.get("symbolCount")
        bucket = metadata.get("bucket")
        object_name_override = metadata.get("objectName")

        source = payload.get("source")
        client = get_or_create_client(source) if source else default_client

        symbols = await client.fetch_symbols(exchange=exchange)
        symbols_df = pd.DataFrame(symbols)

        vci_client = get_or_create_client("VCI")
        sectors = await get_cached_sectors(vci_client)
        sectors_df = pd.DataFrame(sectors.values())

        if not symbols_df.empty and not sectors_df.empty:
            left_key = "code" if "code" in symbols_df.columns else "symbol"
            merged_df = symbols_df.merge(
                sectors_df,
                left_on=left_key,
                right_on="symbol",
                how="left",
            )
            if "symbol" in merged_df.columns and "code" in merged_df.columns:
                merged_df = merged_df.drop(columns=["symbol"])
        else:
            merged_df = symbols_df

        minio = get_minio_client()
        object_name = (
            object_name_override or settings.get_symbols_path(exchange)
        )
        write_parquet_to_minio(minio, merged_df, object_name, bucket=bucket)

        if "delistedDate" in merged_df.columns:
            merged_df = merged_df[merged_df["delistedDate"].isna()]

        current_count = len(merged_df)

        if expected_count is not None and current_count != expected_count:
            await publish_symbol_upsert_batch(
                producer,
                job_id=payload.get("jobId"),
                log_id=payload.get("logId"),
                exchange=exchange,
                merged_df=merged_df,
                expected_count=expected_count,
            )

        status = build_status(
            "exchange",
            exchange,
            payload,
            started_at,
            "success",
            records_inserted=current_count,
            total_records=current_count,
        )
    except Exception as exc:
        logger.exception("Failed to process sync-symbols message: %s", exc)
        status = build_status(
            "exchange",
            exchange,
            payload,
            started_at,
            "error",
            error_message=str(exc),
        )

    await producer.send_and_wait(
        settings.sync_job_status_topic,
        key=status["exchange"].encode() if status.get("exchange") else None,
        value=json.dumps(status).encode(),
    )


def _clean_str(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, float) and math.isnan(value):
        return None
    text = str(value).strip()
    return text or None


def _row_to_symbol_record(row: pd.Series) -> dict[str, Any] | None:
    code = _clean_str(row.get("code"))
    exchange = _clean_str(row.get("floor"))

    if not code or not exchange:
        logger.warning("Skipping row with missing code/exchange: %r", row.to_dict())
        return None

    sector_raw = _clean_str(row.get("icb_lv2_name_en"))
    sector = sector_raw.replace(" ", "_").upper() if sector_raw else None

    return {
        "code": code,
        "exchange": exchange,
        "meta": {"sector": sector},
    }


async def publish_symbol_upsert_batch(
    producer: AIOKafkaProducer,
    *,
    job_id: str | None,
    log_id: str | None,
    exchange: str,
    merged_df: pd.DataFrame,
    expected_count: int,
) -> None:
    records = [
        rec
        for rec in (_row_to_symbol_record(row) for _, row in merged_df.iterrows())
        if rec is not None
    ]

    event = {
        "jobId": job_id,
        "logId": log_id,
        "exchange": exchange,
        "expectedCount": expected_count,
        "actualCount": len(records),
        "symbols": records,
        "detectedAt": datetime.now(UTC).isoformat(),
    }

    logger.warning(
        "Symbol count diff for %s: expected=%d actual=%d — publishing upsert batch",
        exchange,
        expected_count,
        len(records),
    )

    result = await producer.send_and_wait(
        settings.topic_upsert_symbols,
        key=exchange.encode(),
        value=json.dumps(event).encode(),
    )

    logger.warning(
        "Published symbol upsert batch for %s to topic=%s partition=%s offset=%s",
        exchange,
        result.topic,
        result.partition,
        result.offset,
    )
