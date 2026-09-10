"""Completed-session intraday ingestion for configured Vietnam exchanges."""

from __future__ import annotations

import logging
from datetime import UTC
from decimal import Decimal
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd
from py_common.intraday_reconciliation import ReconciliationStatus, reconcile_intraday_eod
from py_common.kafka import decode_json_object_payload
from py_common.messaging import JobStatus, JobStatusMessage, JobStatusPublisher, utc_now
from py_common.storage.immutable_publication import ImmutableDatasetPublisher
from py_common.storage.parquet import ParquetCodec, ParquetStorage

from app.messaging.messages import IntradayEodJobMessage
from app.messaging.status import build_status
from app.settings import settings
from app.stocks.clients.vci_intraday import VCIIntradayQuoteAdapter

logger = logging.getLogger(__name__)
_REQUIRED = ("time", "price", "volume", "match_type", "id")
_VIETNAM_ZONE = ZoneInfo("Asia/Ho_Chi_Minh")
_SUPPORTED_EXCHANGES = {"HOSE", "HNX", "UPCOM"}


async def process_intraday_eod_message(
    raw_msg: str | bytes | dict[str, object],
    status_publisher: JobStatusPublisher,
    publisher: ImmutableDatasetPublisher,
    parquet_storage: ParquetStorage,
    adapter: VCIIntradayQuoteAdapter | None = None,
) -> JobStatusMessage:
    started_at = utc_now()
    payload: dict[str, Any] = {}
    try:
        payload = decode_json_object_payload(raw_msg, "Intraday EOD sync job")
        message = IntradayEodJobMessage.model_validate(payload)
        payload = message.status_payload
        exchange, symbol = message.parse_symbol_key()
        if exchange not in _SUPPORTED_EXCHANGES or message.provider != "VCI":
            raise ValueError("P9-I1 supports HOSE/HNX/UPCOM with provider VCI")
        if message.exchange != exchange:
            raise ValueError("Message exchange differs from symbolKey exchange")

        frame = await (adapter or VCIIntradayQuoteAdapter()).fetch_session(symbol, message.trading_date)
        normalized = normalize_intraday_trades(frame, message)
        if normalized.empty:
            raise ValueError("Provider returned no terminal successful trade result")

        eod = await parquet_storage.read_optional_dataframe(settings.stock_data_paths.eod(exchange, symbol))
        reconciliation = reconcile_against_eod(normalized, eod, message)
        if reconciliation.status == ReconciliationStatus.REJECTED:
            raise ValueError("Intraday EOD reconciliation rejected candidate partition")

        parquet = ParquetCodec.encode(normalized, index=False)
        logical_path = settings.stock_data_paths.intraday_trades(
            message.provider, exchange, message.trading_date.isoformat(), symbol
        )
        prefix, object_name = logical_path.rsplit("/", maxsplit=1)
        result = await publisher.publish(
            partition_prefix=prefix,
            object_name=object_name,
            data=parquet,
            manifest={
                "dataset": "intraday-trades",
                "partition": {
                    "provider": message.provider.lower(),
                    "exchange": exchange.lower(),
                    "trading_date": message.trading_date.isoformat(),
                },
                "normalizationVersion": 1,
                "sourceExecutionId": message.execution_id,
                "objectCount": 1,
                "rowCount": len(normalized),
                "completeness": "COMPLETE",
                "reconciliation": reconciliation_manifest(reconciliation),
            },
            validate_data=lambda raw: _validate_parquet(raw, len(normalized)),
        )
        status = build_status(
            payload, started_at, JobStatus.SUCCESS,
            records_inserted=len(normalized), total_records=len(normalized), new_offset=result.data_version,
        )
    except Exception as exc:
        logger.exception("Failed to process intraday EOD message: %s", exc)
        status = build_status(payload, started_at, JobStatus.ERROR, error_message=str(exc))
    await status_publisher.publish(status, key=status.work_key)
    return status


def normalize_intraday_trades(frame: pd.DataFrame, message: IntradayEodJobMessage) -> pd.DataFrame:
    missing = [name for name in _REQUIRED if name not in frame.columns]
    if missing and not frame.empty:
        raise ValueError(f"Missing required VCI trade fields: {missing}")
    if frame.empty:
        return frame.copy()

    normalized = frame.loc[:, list(_REQUIRED)].copy()
    normalized["timestamp"] = pd.to_datetime(normalized.pop("time"), utc=True)
    local_dates = normalized["timestamp"].dt.tz_convert(_VIETNAM_ZONE).dt.date
    if (local_dates != message.trading_date).any():
        raise ValueError("Provider timestamp local date differs from requested trading date")
    normalized["provider_id"] = normalized.pop("id").astype(str)
    normalized["price"] = pd.to_numeric(normalized["price"])
    normalized["volume"] = pd.to_numeric(normalized["volume"])
    normalized["trade_value"] = normalized["price"] * normalized["volume"]
    normalized["trading_date"] = message.trading_date
    normalized["exchange"] = message.exchange
    normalized["symbol"] = message.parse_symbol_key()[1]

    comparable = ["timestamp", "price", "volume", "match_type"]
    conflicts = normalized.groupby("provider_id")[comparable].nunique(dropna=False)
    if (conflicts > 1).any(axis=None):
        raise ValueError("Conflicting duplicate provider trade id")
    normalized = normalized.drop_duplicates("provider_id", keep="first")
    return normalized.sort_values(["timestamp", "provider_id"]).reset_index(drop=True)


def reconcile_against_eod(normalized: pd.DataFrame, eod: pd.DataFrame | None, message: IntradayEodJobMessage):
    if eod is None or eod.empty or "date" not in eod.columns:
        raise ValueError("Canonical EOD data unavailable for reconciliation")
    dates = pd.to_datetime(eod["date"]).dt.date
    rows = eod.loc[dates == message.trading_date]
    if len(rows) != 1:
        raise ValueError("Canonical EOD row unavailable or ambiguous for trading date")
    row = rows.iloc[0]
    close = _required_eod_value(row, ("ad_close", "close"), "close")
    volume = _required_eod_value(row, ("total_volume", "nm_volume", "volume"), "volume")
    value = _required_eod_value(row, ("nm_value", "total_value", "value"), "value")
    return reconcile_intraday_eod(
        final_price=normalized.iloc[-1]["price"], expected_close=close,
        total_volume=normalized["volume"].sum(), expected_volume=volume,
        total_value=normalized["trade_value"].sum(), expected_value=value,
    )


def _required_eod_value(row: pd.Series, names: tuple[str, ...], label: str) -> Decimal:
    for name in names:
        if name in row.index and pd.notna(row[name]):
            return Decimal(str(row[name]))
    raise ValueError(f"Canonical EOD {label} unavailable for reconciliation")


def reconciliation_manifest(reconciliation) -> dict[str, object]:
    def item(value) -> dict[str, str]:
        return {
            "actual": str(value.actual), "expected": str(value.expected),
            "absolute": str(value.absolute), "relative": str(value.relative),
            "status": value.status.value,
        }
    return {
        "status": reconciliation.status.value,
        "close": item(reconciliation.close), "volume": item(reconciliation.volume), "value": item(reconciliation.value),
    }


def _validate_parquet(raw: bytes, expected_rows: int) -> None:
    persisted = ParquetCodec.decode(raw)
    if len(persisted) != expected_rows:
        raise ValueError("Persisted intraday row count does not match candidate")
    timestamps = pd.to_datetime(persisted["timestamp"], utc=True)
    if timestamps.dt.tz is None or timestamps.dt.tz != UTC:
        raise ValueError("Persisted intraday timestamps must be UTC")
