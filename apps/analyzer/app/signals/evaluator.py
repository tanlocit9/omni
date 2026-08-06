from __future__ import annotations

from typing import Any

import pandas as pd
from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.messages import SignalEvaluationJobMessage
from app.signals.storage import SignalHistoryRepository, SignalOutcomeEvaluation


class SignalOutcomeEvaluator:
    """Evaluate actual outcomes for shared signal history files."""

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._history_repository = SignalHistoryRepository(parquet_storage)

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
            return await self._parquet_storage.read_dataframe(eod_path)

        return await self._history_repository.update_outcomes(history_path, _load_eod)
