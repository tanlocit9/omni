"""Exact-date intraday evidence for deterministic confirmed-signal composition."""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

import pandas as pd
from py_common.storage.exceptions import StorageObjectNotFoundError
from py_common.storage.parquet import ParquetStorage
from py_common.storage.ports import ReadableStorage


class IntradayDirection(StrEnum):
    BULLISH = "BULLISH"
    BEARISH = "BEARISH"
    FLAT = "FLAT"


class CloseVsVwapDirection(StrEnum):
    ABOVE = "ABOVE"
    BELOW = "BELOW"
    EQUAL = "EQUAL"


class IntradayConfirmation(StrEnum):
    BULLISH_CONFIRM = "BULLISH_CONFIRM"
    BEARISH_CONFIRM = "BEARISH_CONFIRM"
    MIXED = "MIXED"
    UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True)
class IntradayDataset:
    frame: pd.DataFrame
    dataset: str
    data_version: str
    manifest_version: int
    reconciliation_status: str
    manifest_path: str


@dataclass(frozen=True)
class IntradayResolution:
    dataset: IntradayDataset | None
    reason_codes: list[str]


@dataclass(frozen=True)
class IntradayConfirmationFacts:
    symbol_key: str
    trading_date: str
    open_price: float
    close_price: float
    session_vwap: float
    session_direction: IntradayDirection
    close_vs_vwap_direction: CloseVsVwapDirection
    close_vs_vwap_delta_pct: float
    last_30m_direction: IntradayDirection
    late_volume_share: float
    reconciliation_status: str
    dataset: str
    data_version: str
    manifest_version: int

    def to_metadata(self) -> dict[str, Any]:
        return {
            "symbolKey": self.symbol_key,
            "tradingDate": self.trading_date,
            "openPrice": self.open_price,
            "closePrice": self.close_price,
            "sessionVwap": self.session_vwap,
            "sessionDirection": self.session_direction.value,
            "closeVsVwap": {
                "direction": self.close_vs_vwap_direction.value,
                "deltaPct": self.close_vs_vwap_delta_pct,
            },
            "last30mDirection": self.last_30m_direction.value,
            "lateVolumeShare": self.late_volume_share,
            "reconciliationStatus": self.reconciliation_status,
            "source": {
                "dataset": self.dataset,
                "dataVersion": self.data_version,
                "manifestVersion": self.manifest_version,
            },
        }


@dataclass(frozen=True)
class IntradayConfirmationResult:
    result: IntradayConfirmation
    reason_codes: list[str]
    facts: IntradayConfirmationFacts | None = None

    def to_metadata(self) -> dict[str, Any]:
        metadata: dict[str, Any] = {
            "result": self.result.value,
            "reasonCodes": self.reason_codes,
        }
        if self.facts is not None:
            metadata["reconciliationStatus"] = self.facts.reconciliation_status
            metadata["facts"] = self.facts.to_metadata()
        return metadata


class IntradayDatasetResolver:
    """Resolve one exact P9 immutable intraday partition and its lineage."""

    def __init__(
        self,
        readable: ReadableStorage,
        parquet_storage: ParquetStorage,
        bucket: str,
        stock_data_paths: Any,
    ) -> None:
        self._readable = readable
        self._parquet_storage = parquet_storage
        self._bucket = bucket
        self._paths = stock_data_paths

    async def resolve(
        self,
        *,
        provider: str,
        exchange: str,
        symbol: str,
        trading_date: str,
    ) -> IntradayResolution:
        logical_path = self._paths.intraday_trades(
            provider, exchange, trading_date, symbol
        )
        partition_prefix = logical_path.rsplit("/", maxsplit=1)[0]
        ready_path = f"{partition_prefix}/READY.json"
        try:
            ready = await self._read_json(ready_path)
        except StorageObjectNotFoundError:
            return IntradayResolution(None, ["MISSING_INTRADAY"])
        except json.JSONDecodeError, TypeError, ValueError, KeyError:
            return IntradayResolution(None, ["INVALID_INTRADAY_PARTITION"])

        try:
            if ready.get("status") != "READY":
                return IntradayResolution(None, ["INVALID_INTRADAY_PARTITION"])
            data_version = _required_text(ready, "dataVersion")
            manifest_path = _required_text(ready, "manifestPath")
            manifest = await self._read_json(manifest_path)
            partition = manifest["partition"]
            expected_partition = {
                "provider": provider.lower(),
                "exchange": exchange.lower(),
                "trading_date": trading_date,
                "symbol": symbol.lower(),
            }
            if manifest.get("dataset") != "intraday-trades":
                raise ValueError("Unexpected intraday dataset")
            if any(
                partition.get(key) != value for key, value in expected_partition.items()
            ):
                return IntradayResolution(None, ["STALE_INTRADAY"])
            if manifest.get("dataVersion") != data_version:
                raise ValueError("READY and manifest versions differ")
            reconciliation_status = str(
                manifest.get("reconciliation", {}).get("status", "")
            ).upper()
            if reconciliation_status == "REJECTED":
                return IntradayResolution(None, ["REJECTED_RECONCILIATION"])
            if reconciliation_status not in {"READY", "WARNING"}:
                raise ValueError("Unsupported reconciliation status")
            frame = await self._parquet_storage.read_dataframe(
                _required_text(manifest, "path")
            )
            return IntradayResolution(
                IntradayDataset(
                    frame=frame,
                    dataset="intraday-trades",
                    data_version=data_version,
                    manifest_version=int(manifest.get("normalizationVersion", 1)),
                    reconciliation_status=reconciliation_status,
                    manifest_path=manifest_path,
                ),
                [],
            )
        except StorageObjectNotFoundError:
            return IntradayResolution(None, ["INVALID_INTRADAY_PARTITION"])
        except json.JSONDecodeError, TypeError, ValueError, KeyError:
            return IntradayResolution(None, ["INVALID_INTRADAY_PARTITION"])

    async def _read_json(self, object_name: str) -> dict[str, Any]:
        raw = await self._readable.read_bytes(self._bucket, object_name)
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise TypeError("Expected a JSON object")
        return value


def calculate_intraday_facts(
    dataset: IntradayDataset,
    *,
    symbol_key: str,
    trading_date: str,
) -> IntradayConfirmationFacts:
    """Derive deterministic facts from one completed normalized trade partition."""
    required = {"timestamp", "price", "volume", "trading_date", "exchange", "symbol"}
    if dataset.frame.empty or not required.issubset(dataset.frame.columns):
        raise ValueError("Invalid intraday partition schema")

    frame = dataset.frame.loc[:, sorted(required)].copy()
    frame["timestamp"] = pd.to_datetime(frame["timestamp"], utc=True, errors="coerce")
    frame["price"] = pd.to_numeric(frame["price"], errors="coerce")
    frame["volume"] = pd.to_numeric(frame["volume"], errors="coerce")
    frame["trading_date"] = pd.to_datetime(
        frame["trading_date"], errors="coerce"
    ).dt.date
    frame = frame.dropna(subset=["timestamp", "price", "volume", "trading_date"])
    if frame.empty or (frame["volume"] < 0).any():
        raise ValueError("Invalid intraday trade values")

    exchange, symbol = symbol_key.split("-", maxsplit=1)
    expected_date = pd.Timestamp(trading_date).date()
    if (
        set(frame["trading_date"]) != {expected_date}
        or set(frame["exchange"].astype(str).str.upper()) != {exchange.upper()}
        or set(frame["symbol"].astype(str).str.upper()) != {symbol.upper()}
    ):
        raise ValueError("Intraday rows do not match the requested partition")

    frame = frame.sort_values("timestamp").reset_index(drop=True)
    total_volume = float(frame["volume"].sum())
    if total_volume <= 0:
        raise ValueError("Intraday partition total volume must be positive")
    open_price = float(frame.iloc[0]["price"])
    close_price = float(frame.iloc[-1]["price"])
    session_vwap = float((frame["price"] * frame["volume"]).sum() / total_volume)
    final_window_start = frame.iloc[-1]["timestamp"] - pd.Timedelta(minutes=30)
    late = frame[frame["timestamp"] >= final_window_start]
    late_open = float(late.iloc[0]["price"])
    late_close = float(late.iloc[-1]["price"])

    return IntradayConfirmationFacts(
        symbol_key=symbol_key,
        trading_date=trading_date,
        open_price=open_price,
        close_price=close_price,
        session_vwap=session_vwap,
        session_direction=_price_direction(open_price, close_price),
        close_vs_vwap_direction=(
            CloseVsVwapDirection.ABOVE
            if close_price > session_vwap
            else CloseVsVwapDirection.BELOW
            if close_price < session_vwap
            else CloseVsVwapDirection.EQUAL
        ),
        close_vs_vwap_delta_pct=(close_price - session_vwap) / session_vwap,
        last_30m_direction=_price_direction(late_open, late_close),
        late_volume_share=float(late["volume"].sum()) / total_volume,
        reconciliation_status=dataset.reconciliation_status,
        dataset=dataset.dataset,
        data_version=dataset.data_version,
        manifest_version=dataset.manifest_version,
    )


def evaluate_intraday_confirmation(
    facts: IntradayConfirmationFacts | None,
    unavailable_reasons: list[str] | None = None,
) -> IntradayConfirmationResult:
    """Classify intraday evidence without creating a directional vote."""
    if facts is None:
        return IntradayConfirmationResult(
            IntradayConfirmation.UNAVAILABLE,
            unavailable_reasons or ["MISSING_INTRADAY"],
        )

    bullish = sum(
        (
            facts.session_direction == IntradayDirection.BULLISH,
            facts.close_vs_vwap_direction == CloseVsVwapDirection.ABOVE,
            facts.last_30m_direction == IntradayDirection.BULLISH,
        )
    )
    bearish = sum(
        (
            facts.session_direction == IntradayDirection.BEARISH,
            facts.close_vs_vwap_direction == CloseVsVwapDirection.BELOW,
            facts.last_30m_direction == IntradayDirection.BEARISH,
        )
    )
    reasons = [f"INTRADAY_BULLISH_FACTS_{bullish}", f"INTRADAY_BEARISH_FACTS_{bearish}"]
    if facts.reconciliation_status == "WARNING":
        reasons.append("INTRADAY_RECONCILIATION_WARNING")
    result = (
        IntradayConfirmation.BULLISH_CONFIRM
        if bullish >= 2 and bearish == 0
        else IntradayConfirmation.BEARISH_CONFIRM
        if bearish >= 2 and bullish == 0
        else IntradayConfirmation.MIXED
    )
    return IntradayConfirmationResult(result, reasons, facts)


def _price_direction(open_price: float, close_price: float) -> IntradayDirection:
    return (
        IntradayDirection.BULLISH
        if close_price > open_price
        else IntradayDirection.BEARISH
        if close_price < open_price
        else IntradayDirection.FLAT
    )


def _required_text(value: dict[str, Any], key: str) -> str:
    result = value[key]
    if not isinstance(result, str) or not result:
        raise ValueError(f"{key} must be a non-empty string")
    return result
