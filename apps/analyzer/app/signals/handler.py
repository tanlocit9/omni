from __future__ import annotations

import logging
from typing import Any

from py_common.storage.exceptions import StorageObjectNotFoundError
from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.messages import SignalJobMessage
from app.signals.storage import SignalHistoryRepository, SignalTransition
from app.signals.strategy import (
    TREND_MOMENTUM_V1,
    MarketSignal,
    SignalResult,
    calculate_trend_momentum_v1,
)

_logger = logging.getLogger(__name__)


class SignalJobHandler:
    """Process Market Signal V1 jobs using shared Parquet storage."""

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._signal_repository = SignalHistoryRepository(parquet_storage)

    async def handle(self, payload: dict[str, Any]) -> SignalTransition:
        message = SignalJobMessage.model_validate(payload)
        exchange, code = message.parse_symbol_key()

        eod_path = self._settings.stock_data_paths.eod(exchange, code)
        indicators_path = self._settings.stock_data_paths.indicators(
            "ad_close",
            message.timeframe,
            exchange,
            code,
        )
        history_path = self._settings.stock_data_paths.signal_history(
            message.strategy,
            message.timeframe,
            exchange,
        )
        current_path = self._settings.stock_data_paths.signal_current(
            message.strategy,
            message.timeframe,
            exchange,
            code,
        )

        _logger.info(
            "Calculating signal symbolKey=%s timeframe=%s strategy=%s "
            "eodPath=%s indicatorsPath=%s",
            message.symbol_key,
            message.timeframe,
            message.strategy,
            eod_path,
            indicators_path,
        )
        eod_frame = await self._parquet_storage.read_dataframe(eod_path)
        try:
            indicators_frame = await self._parquet_storage.read_dataframe(
                indicators_path
            )
        except StorageObjectNotFoundError as exc:
            _logger.warning(
                "Skipping signal calculation because prerequisite indicator object "
                "is missing symbolKey=%s timeframe=%s strategy=%s bucket=%s "
                "objectName=%s; verify the indicator job completed for this symbol "
                "before signal dispatch",
                message.symbol_key,
                message.timeframe,
                message.strategy,
                exc.bucket,
                exc.object_name,
            )
            result = SignalResult(
                signal=MarketSignal.NO_DECISION,
                price=None,
                signal_date=None,
                reason_codes=["MISSING_INDICATOR_OBJECT"],
                score=0,
                strategy=message.strategy,
            )
        else:
            result = calculate_trend_momentum_v1(eod_frame, indicators_frame)
        if message.strategy != TREND_MOMENTUM_V1:
            raise ValueError(f"Unsupported signal strategy: {message.strategy}")
        return await self._signal_repository.persist_transition(
            history_path,
            current_path,
            message.symbol_key,
            message.timeframe,
            result,
            exchange=exchange,
        )
