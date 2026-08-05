from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import pandas as pd
from py_common.storage.parquet import ParquetStorage

from app.signals.strategy import MarketSignal, SignalResult


@dataclass(frozen=True)
class SignalTransition:
    signal_changed: bool
    previous_signal: MarketSignal | None
    new_signal: MarketSignal
    state_frame: pd.DataFrame
    metadata: dict[str, Any]


class SignalStateStorage:
    """Read and write latest market signal state from Parquet storage."""

    def __init__(self, parquet_storage: ParquetStorage) -> None:
        self._parquet_storage = parquet_storage

    async def persist_transition(
        self,
        path: str,
        symbol_key: str,
        timeframe: str,
        result: SignalResult,
    ) -> SignalTransition:
        previous_signal = await self._read_previous_signal(path)
        signal_changed = (
            previous_signal is not None and previous_signal != result.signal
        )
        state_frame = self._build_state_frame(symbol_key, timeframe, result)
        await self._parquet_storage.write_dataframe(path, state_frame)

        metadata = result.to_metadata()
        metadata.update(
            {
                "signalChanged": signal_changed,
                "previousSignal": previous_signal.value if previous_signal else None,
                "timeframe": timeframe,
            }
        )
        return SignalTransition(
            signal_changed=signal_changed,
            previous_signal=previous_signal,
            new_signal=result.signal,
            state_frame=state_frame,
            metadata=metadata,
        )

    async def _read_previous_signal(self, path: str) -> MarketSignal | None:
        try:
            frame = await self._parquet_storage.read_dataframe(path)
        except FileNotFoundError:
            return None
        except Exception as exc:
            if exc.__class__.__name__ in {
                "ObjectNotFoundError",
                "StorageObjectNotFoundError",
                "NoSuchKey",
            }:
                return None
            raise

        if frame.empty or "signal" not in frame.columns:
            return None
        value = (
            frame.sort_values("generated_at").iloc[-1]["signal"]
            if "generated_at" in frame.columns
            else frame.iloc[-1]["signal"]
        )
        if pd.isna(value):
            return None
        return MarketSignal(str(value).upper())

    def _build_state_frame(
        self,
        symbol_key: str,
        timeframe: str,
        result: SignalResult,
    ) -> pd.DataFrame:
        return pd.DataFrame(
            [
                {
                    "symbol_key": symbol_key,
                    "strategy": result.strategy,
                    "timeframe": timeframe,
                    "signal": result.signal.value,
                    "price": result.price,
                    "signal_date": result.signal_date,
                    "score": result.score,
                    "reason_codes": result.reason_codes,
                    "generated_at": pd.Timestamp.utcnow(),
                }
            ]
        )
