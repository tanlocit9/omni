from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import pandas as pd
from py_common.storage.exceptions import StorageObjectNotFoundError
from py_common.storage.parquet import ParquetStorage

from app.signals.strategy import MarketSignal, SignalResult

OUTCOME_WINDOWS = (5, 10, 15, 20)
SIGNAL_KEY_COLUMNS = ["symbol_key", "strategy", "timeframe", "signal_date"]
SIGNAL_COLUMNS = [
    "symbol_key",
    "exchange",
    "strategy",
    "timeframe",
    "signal_date",
    "signal",
    "signal_price",
    "score",
    "reason_codes",
    "generated_at",
]
AUDIT_COLUMNS = ["last_recalculated_at", "revision"]
OUTCOME_COLUMNS = [
    "actual_price_t5",
    "actual_return_t5",
    "actual_price_t10",
    "actual_return_t10",
    "actual_price_t15",
    "actual_return_t15",
    "actual_price_t20",
    "actual_return_t20",
    "actual_updated_at",
]
HISTORY_COLUMNS = SIGNAL_COLUMNS + AUDIT_COLUMNS + OUTCOME_COLUMNS
IMMUTABLE_SIGNAL_COLUMNS = SIGNAL_COLUMNS
VALID_SIGNAL_VALUES = {signal.value for signal in MarketSignal}


@dataclass(frozen=True)
class SignalTransition:
    signal_changed: bool
    previous_signal: MarketSignal | None
    new_signal: MarketSignal
    state_frame: pd.DataFrame
    metadata: dict[str, Any]
    persisted: bool = True


@dataclass(frozen=True)
class SignalOutcomeEvaluation:
    records_scanned: int
    records_updated: int
    metadata: dict[str, Any]


class SignalHistoryWriteLock:
    """In-process lock registry for shared signal history writes."""

    def __init__(self) -> None:
        self._locks: dict[str, asyncio.Lock] = {}
        self._registry_lock = asyncio.Lock()

    async def run(self, key: str, operation: Callable[[], Awaitable[Any]]) -> Any:
        async with self._registry_lock:
            lock = self._locks.setdefault(key, asyncio.Lock())
        async with lock:
            return await operation()


_SIGNAL_HISTORY_LOCKS = SignalHistoryWriteLock()


class SignalHistoryRepository:
    """Read/write shared Market Signal V1 history and actual outcome fields.

    Signal history is stored as one Parquet file per strategy + timeframe + exchange.
    The repository is the single Analyzer boundary allowed to mutate that shared file.
    It serializes read-modify-write operations by object path inside one process.

    V1 concurrency constraint: this in-process lock is not a distributed lock. Deploy
    exactly one Analyzer writer for a shared signal history path. Multiple Analyzer
    processes must not mutate the same history path concurrently.
    """

    def __init__(self, parquet_storage: ParquetStorage) -> None:
        self._parquet_storage = parquet_storage
        self._locks = _SIGNAL_HISTORY_LOCKS

    async def persist_transition(
        self,
        history_path: str,
        current_path: str | None,
        symbol_key: str,
        timeframe: str,
        result: SignalResult,
        exchange: str | None = None,
    ) -> SignalTransition:
        exchange = exchange or self._exchange_from_symbol_key(symbol_key)

        async def _persist() -> SignalTransition:
            history = await self._read_history(history_path)
            previous_signal = self._previous_signal(history, symbol_key)
            signal_changed = (
                result.signal != MarketSignal.NO_DECISION
                and previous_signal is not None
                and previous_signal != result.signal
            )

            state_frame = self._build_state_frame(
                symbol_key,
                exchange,
                timeframe,
                result,
            )
            persisted = result.signal != MarketSignal.NO_DECISION
            if persisted:
                history_frame = self._upsert_signal_row(history, state_frame)
                await self._replace_history(history_path, history_frame)
                if current_path and current_path != history_path:
                    current_frame = self._latest_current_frame(
                        history_frame,
                        symbol_key,
                    )
                    await self._replace_history(current_path, current_frame)

            metadata = result.to_metadata()
            metadata.update(
                {
                    "signalChanged": signal_changed,
                    "previousSignal": (
                        previous_signal.value if previous_signal else None
                    ),
                    "timeframe": timeframe,
                    "persisted": persisted,
                }
            )
            return SignalTransition(
                signal_changed=signal_changed,
                previous_signal=previous_signal,
                new_signal=result.signal,
                state_frame=state_frame,
                metadata=metadata,
                persisted=persisted,
            )

        return await self._locks.run(history_path, _persist)

    async def update_outcomes(
        self,
        history_path: str,
        eod_loader: Callable[[str], Awaitable[pd.DataFrame]],
    ) -> SignalOutcomeEvaluation:
        async def _update() -> SignalOutcomeEvaluation:
            history = await self._read_history(history_path)
            if history.empty:
                return SignalOutcomeEvaluation(
                    records_scanned=0,
                    records_updated=0,
                    metadata={
                        "recordsScanned": 0,
                        "recordsUpdated": 0,
                        "missingEodCount": 0,
                        "missingEodSymbols": [],
                    },
                )

            history = self._ensure_schema(history)
            updated = history.copy()
            records_updated = 0
            eod_cache: dict[str, pd.DataFrame] = {}
            missing_eod_symbols: set[str] = set()

            for index, row in history.iterrows():
                if str(row.get("signal", "")).upper() == MarketSignal.NO_DECISION.value:
                    continue
                missing_windows = [
                    window
                    for window in OUTCOME_WINDOWS
                    if pd.isna(row.get(f"actual_price_t{window}"))
                    or pd.isna(row.get(f"actual_return_t{window}"))
                ]
                if not missing_windows:
                    continue

                symbol_key = str(row["symbol_key"])
                if symbol_key in missing_eod_symbols:
                    continue
                if symbol_key not in eod_cache:
                    try:
                        eod_cache[symbol_key] = await eod_loader(symbol_key)
                    except Exception as exc:
                        if self._is_object_not_found(exc):
                            missing_eod_symbols.add(symbol_key)
                            continue
                        raise
                outcomes = self._resolve_outcomes(
                    row,
                    eod_cache[symbol_key],
                    missing_windows,
                )
                if outcomes:
                    for column, value in outcomes.items():
                        updated.at[index, column] = value
                    updated.at[index, "actual_updated_at"] = pd.Timestamp.now(tz="UTC")
                    records_updated += 1

            if records_updated:
                await self._replace_history(history_path, updated)

            missing_symbols = sorted(missing_eod_symbols)
            return SignalOutcomeEvaluation(
                records_scanned=len(history),
                records_updated=records_updated,
                metadata={
                    "recordsScanned": len(history),
                    "recordsUpdated": records_updated,
                    "outcomeWindows": list(OUTCOME_WINDOWS),
                    "missingEodCount": len(missing_symbols),
                    "missingEodSymbols": missing_symbols,
                },
            )

        return await self._locks.run(history_path, _update)

    async def _read_history(self, path: str) -> pd.DataFrame:
        try:
            return await self._parquet_storage.read_dataframe(path)
        except FileNotFoundError:
            return pd.DataFrame()
        except Exception as exc:
            if self._is_object_not_found(exc):
                return pd.DataFrame()
            raise

    async def _replace_history(self, path: str, frame: pd.DataFrame) -> None:
        await self._parquet_storage.replace_dataframe(
            path,
            self._sort_history(self._ensure_schema(frame)),
            validate=self._validate_history_frame,
        )

    def _upsert_signal_row(
        self, history: pd.DataFrame, state_frame: pd.DataFrame
    ) -> pd.DataFrame:
        history = self._ensure_schema(history)
        incoming = self._ensure_schema(state_frame).iloc[0].copy()
        now = pd.Timestamp.now(tz="UTC")
        incoming["last_recalculated_at"] = now
        incoming["revision"] = 1

        if not history.empty and set(SIGNAL_KEY_COLUMNS).issubset(history.columns):
            history = history.dropna(subset=SIGNAL_KEY_COLUMNS)
            same_key = self._same_signal_key(history, incoming)
            if same_key.any():
                existing = history.loc[same_key].iloc[-1]
                for column in OUTCOME_COLUMNS:
                    incoming[column] = existing.get(column, pd.NA)
                existing_revision = existing.get("revision", 0)
                incoming["revision"] = self._next_revision(existing_revision)
                incoming["last_recalculated_at"] = now
                history = history.loc[~same_key]
        merged = pd.concat(
            [history, pd.DataFrame([incoming])],
            ignore_index=True,
        )
        return self._sort_history(merged)

    def _same_signal_key(self, history: pd.DataFrame, incoming: pd.Series) -> pd.Series:
        return (
            (history["symbol_key"].astype(str) == str(incoming["symbol_key"]))
            & (history["strategy"].astype(str) == str(incoming["strategy"]))
            & (history["timeframe"].astype(str) == str(incoming["timeframe"]))
            & (history["signal_date"].astype(str) == str(incoming["signal_date"]))
        )

    def _previous_signal(
        self, history: pd.DataFrame, symbol_key: str
    ) -> MarketSignal | None:
        if history.empty or "signal" not in history.columns:
            return None
        frame = history
        if "symbol_key" in frame.columns:
            frame = frame[frame["symbol_key"].astype(str) == symbol_key]
        valid_frame = frame[
            frame["signal"].astype(str).str.upper() != MarketSignal.NO_DECISION.value
        ]
        if valid_frame.empty:
            return None
        value = (
            valid_frame.sort_values("signal_date").iloc[-1]["signal"]
            if "signal_date" in valid_frame.columns
            else valid_frame.iloc[-1]["signal"]
        )
        if pd.isna(value):
            return None
        return MarketSignal(str(value).upper())

    def _build_state_frame(
        self,
        symbol_key: str,
        exchange: str,
        timeframe: str,
        result: SignalResult,
    ) -> pd.DataFrame:
        row = {
            "symbol_key": symbol_key,
            "exchange": exchange.upper(),
            "strategy": result.strategy,
            "timeframe": timeframe,
            "signal": result.signal.value,
            "signal_price": result.price,
            "signal_date": result.signal_date,
            "score": result.score,
            "reason_codes": result.reason_codes,
            "generated_at": pd.Timestamp.now(tz="UTC"),
            "last_recalculated_at": pd.NA,
            "revision": 1,
        }
        for column in OUTCOME_COLUMNS:
            row[column] = pd.NA
        return pd.DataFrame([row])

    def _ensure_schema(self, frame: pd.DataFrame) -> pd.DataFrame:
        if frame.empty:
            return pd.DataFrame(columns=HISTORY_COLUMNS)
        normalized = frame.copy()
        if "price" in normalized.columns and "signal_price" not in normalized.columns:
            normalized["signal_price"] = normalized["price"]
        for column in HISTORY_COLUMNS:
            if column not in normalized.columns:
                normalized[column] = pd.NA
        normalized["signal"] = normalized["signal"].astype(str).str.upper()
        normalized["revision"] = normalized["revision"].apply(self._coerce_revision)
        return normalized[HISTORY_COLUMNS]

    def _sort_history(self, frame: pd.DataFrame) -> pd.DataFrame:
        sort_columns = [
            column
            for column in [
                "strategy",
                "timeframe",
                "exchange",
                "symbol_key",
                "signal_date",
            ]
            if column in frame.columns
        ]
        if sort_columns:
            return frame.sort_values(sort_columns).reset_index(drop=True)
        return frame.reset_index(drop=True)

    def _latest_current_frame(
        self,
        history: pd.DataFrame,
        symbol_key: str,
    ) -> pd.DataFrame:
        frame = history[history["symbol_key"].astype(str) == symbol_key]
        if frame.empty:
            return frame
        return frame.sort_values("signal_date").tail(1).reset_index(drop=True)

    def _resolve_outcomes(
        self,
        signal_row: pd.Series,
        eod_frame: pd.DataFrame,
        windows: list[int],
    ) -> dict[str, Any]:
        has_required_columns = {"date", "ad_close"}.issubset(eod_frame.columns)
        if eod_frame.empty or not has_required_columns:
            return {}
        signal_price = signal_row.get("signal_price")
        if pd.isna(signal_price) or float(signal_price) == 0:
            return {}

        prices = eod_frame.copy()
        prices["date"] = pd.to_datetime(prices["date"], errors="coerce").dt.date
        prices = (
            prices.dropna(subset=["date", "ad_close"])
            .drop_duplicates(subset=["date"], keep="last")
            .sort_values("date")
            .reset_index(drop=True)
        )
        if prices.empty:
            return {}

        signal_date = pd.to_datetime(signal_row["signal_date"]).date()
        future_prices = prices[prices["date"] > signal_date].reset_index(drop=True)

        outcomes: dict[str, Any] = {}
        for window in windows:
            target_index = window - 1
            if target_index >= len(future_prices):
                continue
            price_row = future_prices.iloc[target_index]
            actual_price = float(price_row["ad_close"])
            outcomes[f"actual_price_t{window}"] = actual_price
            outcomes[f"actual_return_t{window}"] = (
                actual_price - float(signal_price)
            ) / float(signal_price)
        return outcomes

    def _validate_history_frame(self, frame: pd.DataFrame) -> None:
        missing_columns = [
            column for column in HISTORY_COLUMNS if column not in frame.columns
        ]
        if missing_columns:
            raise ValueError(f"Signal history is missing columns: {missing_columns}")
        if frame[SIGNAL_KEY_COLUMNS].isna().any().any():
            raise ValueError("Signal history contains null signal key values")
        duplicate_keys = frame.duplicated(subset=SIGNAL_KEY_COLUMNS, keep=False)
        if duplicate_keys.any():
            raise ValueError("Signal history contains duplicate signal key records")
        invalid_signals = sorted(
            set(frame["signal"].astype(str).str.upper()) - VALID_SIGNAL_VALUES
        )
        if invalid_signals:
            raise ValueError(
                f"Signal history contains invalid signals: {invalid_signals}"
            )

    @staticmethod
    def _exchange_from_symbol_key(symbol_key: str) -> str:
        return symbol_key.split("-", maxsplit=1)[0]

    @staticmethod
    def _is_object_not_found(exc: Exception) -> bool:
        object_not_found_names = {
            "ObjectNotFoundError",
            "StorageObjectNotFoundError",
            "NoSuchKey",
        }
        return (
            isinstance(exc, (FileNotFoundError, StorageObjectNotFoundError))
            or exc.__class__.__name__ in object_not_found_names
        )

    @staticmethod
    def _coerce_revision(value: Any) -> int:
        if pd.isna(value):
            return 1
        try:
            return max(1, int(value))
        except TypeError, ValueError:
            return 1

    def _next_revision(self, value: Any) -> int:
        return self._coerce_revision(value) + 1


SignalRepository = SignalHistoryRepository
