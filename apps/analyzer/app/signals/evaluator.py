from __future__ import annotations

from typing import Any

import pandas as pd
from py_common.storage.atomic_work import cutoff_frame
from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.messages import SignalEvaluationJobMessage
from app.signals.storage import SignalHistoryRepository, SignalOutcomeEvaluation


class SignalOutcomeEvaluator:
    """Evaluate actual outcomes for shared signal history files."""

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage, metadata_reader=None) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._metadata_reader = metadata_reader
        self._history_repository = SignalHistoryRepository(
            parquet_storage, snapshots_enabled=getattr(settings, "output_snapshots_enabled", False) is True
        )

    async def evaluate(self, payload: dict[str, Any]) -> SignalOutcomeEvaluation:
        message = SignalEvaluationJobMessage.model_validate(payload)
        history_path = self._settings.stock_data_paths.signal_history(
            message.strategy,
            message.timeframe,
            message.exchange,
        )

        async def _load_eod(symbol_key: str) -> pd.DataFrame:
            exchange, code = symbol_key.split("-", maxsplit=1)
            eod_path = self._settings.stock_data_paths.eod(exchange, code)
            if getattr(self._settings, "output_readiness_mode", "manifest") == "postgres":
                if self._metadata_reader is None:
                    raise ValueError("PostgreSQL EOD reader unavailable")
                document = await self._metadata_reader.read()
                manifest = document.resolve("eod", {"exchange": exchange.lower(), "code": code.lower()})
                if manifest is None:
                    raise ValueError("EOD partition is not READY")
                frame = await self._parquet_storage.read_bound_dataframe("eod", manifest, manifest.dataVersion)
            else:
                frame = await self._parquet_storage.read_dataframe(eod_path)
            return cutoff_frame(frame, message.business_date)

        return await self._history_repository.update_outcomes(history_path, _load_eod)
