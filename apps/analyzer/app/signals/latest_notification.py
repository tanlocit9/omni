from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

import pandas as pd
from py_common.storage.exceptions import StorageObjectNotFoundError
from py_common.storage.parquet import ParquetStorage

DEFAULT_STRATEGY = "TREND_MOMENTUM_V1"
DEFAULT_TIMEFRAME = "1d"
_SYMBOL_KEY_PATTERN = re.compile(r"^[A-Z0-9]+-[A-Z0-9]+$")


class InvalidSymbolKeyError(ValueError):
    pass


@dataclass(frozen=True)
class LatestSignal:
    symbol_key: str
    signal: str
    signal_price: Any
    signal_date: str
    reason_codes: list[str]
    score: Any
    strategy: str
    timeframe: str
    generated_at: str


class LatestSignalRepository:
    """Reads the authoritative latest signal rows from exchange Parquet history."""

    def __init__(self, settings: Any, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage

    async def find_latest(self, symbol_key: str | None = None) -> LatestSignal | None:
        normalized = self.normalize_symbol_key(symbol_key)
        exchanges = (
            [normalized.split("-", 1)[0]]
            if normalized
            else list(self._settings.sector_wave_symbol_exchanges)
        )
        candidates: list[pd.Series] = []
        for exchange in exchanges:
            path = self._settings.stock_data_paths.signal_history(
                DEFAULT_STRATEGY, DEFAULT_TIMEFRAME, exchange
            )
            frame = await self._read_optional(path)
            if frame is None or frame.empty:
                continue
            if normalized:
                if "symbol_key" not in frame.columns:
                    continue
                frame = frame[frame["symbol_key"].astype(str) == normalized]
            if frame.empty:
                continue
            self._require_columns(frame)
            candidates.append(self._latest_row(frame))

        if not candidates:
            return None
        row = self._latest_row(pd.DataFrame(candidates))
        return self._map(row)

    @staticmethod
    def normalize_symbol_key(symbol_key: str | None) -> str | None:
        if symbol_key is None or not symbol_key.strip():
            return None
        normalized = symbol_key.strip().upper()
        if not _SYMBOL_KEY_PATTERN.fullmatch(normalized):
            raise InvalidSymbolKeyError("symbolKey must match <exchange>-<code>")
        return normalized

    async def _read_optional(self, path: str) -> pd.DataFrame | None:
        try:
            return await self._parquet_storage.read_dataframe(path)
        except FileNotFoundError:
            return None
        except StorageObjectNotFoundError:
            return None

    @staticmethod
    def _require_columns(frame: pd.DataFrame) -> None:
        required = {
            "symbol_key",
            "signal",
            "signal_price",
            "signal_date",
            "reason_codes",
            "score",
            "strategy",
            "timeframe",
            "generated_at",
        }
        missing = required.difference(frame.columns)
        if missing:
            raise ValueError(f"Signal history is missing columns: {sorted(missing)}")

    @staticmethod
    def _latest_row(frame: pd.DataFrame) -> pd.Series:
        sortable = frame.copy()
        sortable["_signal_date"] = pd.to_datetime(sortable["signal_date"], utc=True)
        sortable["_generated_at"] = pd.to_datetime(sortable["generated_at"], utc=True)
        return sortable.sort_values(
            ["_signal_date", "_generated_at"], kind="stable"
        ).iloc[-1]

    @staticmethod
    def _map(row: pd.Series) -> LatestSignal:
        return LatestSignal(
            symbol_key=str(row["symbol_key"]),
            signal=str(row["signal"]),
            signal_price=_scalar(row["signal_price"]),
            signal_date=_timestamp(row["signal_date"]),
            reason_codes=_reason_codes(row["reason_codes"]),
            score=_scalar(row["score"]),
            strategy=str(row["strategy"]),
            timeframe=str(row["timeframe"]),
            generated_at=_timestamp(row["generated_at"]),
        )


class LatestSignalNotificationService:
    def __init__(self, repository: LatestSignalRepository, publisher: Any) -> None:
        self._repository = repository
        self._publisher = publisher

    async def publish_latest(
        self, symbol_key: str | None = None
    ) -> LatestSignal | None:
        latest = await self._repository.find_latest(symbol_key)
        if latest is None:
            return None
        identity = str(uuid4())
        payload = {
            "type": "SIGNAL_CHANGED",
            "executionId": identity,
            "parentExecutionId": str(uuid4()),
            "source": "ANALYZER",
            "symbolKey": latest.symbol_key,
            "previousSignal": None,
            "newSignal": latest.signal,
            "price": latest.signal_price,
            "signalDate": latest.signal_date,
            "reasonCodes": latest.reason_codes,
            "score": latest.score,
            "strategy": latest.strategy,
            "timeframe": latest.timeframe,
            "signalChanged": True,
            "createdAt": datetime.now(UTC).isoformat(),
            "metadata": {"manual": True, "generatedAt": latest.generated_at},
        }
        await self._publisher.publish_signal_notification(payload)
        return latest


def _reason_codes(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, Iterable) and not isinstance(value, (str, bytes, dict)):
        return [str(item) for item in value if item is not None and not pd.isna(item)]
    if pd.isna(value):
        return []
    return [str(value)]


def _timestamp(value: Any) -> str:
    return pd.Timestamp(value).isoformat()


def _scalar(value: Any) -> Any:
    if value is None or pd.isna(value):
        return None
    return value.item() if hasattr(value, "item") else value
