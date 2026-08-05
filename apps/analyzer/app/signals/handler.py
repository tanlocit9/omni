from __future__ import annotations

import logging
from typing import Any

from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.messages import SignalJobMessage
from app.signals.storage import SignalStateStorage, SignalTransition
from app.signals.strategy import TREND_MOMENTUM_V1, calculate_trend_momentum_v1

_logger = logging.getLogger(__name__)


class SignalJobHandler:
    """Process Market Signal V1 jobs using shared Parquet storage."""

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._state_storage = SignalStateStorage(parquet_storage)

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
        signals_path = self._settings.stock_data_paths.signals(
            message.strategy,
            message.timeframe,
            exchange,
            code,
        )

        _logger.info(
            "Calculating signal for symbolKey=%s eodPath=%s "
            "indicatorsPath=%s signalsPath=%s",
            message.symbol_key,
            eod_path,
            indicators_path,
            signals_path,
        )
        eod_frame = await self._parquet_storage.read_dataframe(eod_path)
        indicators_frame = await self._parquet_storage.read_dataframe(indicators_path)
        if message.strategy != TREND_MOMENTUM_V1:
            raise ValueError(f"Unsupported signal strategy: {message.strategy}")

        result = calculate_trend_momentum_v1(eod_frame, indicators_frame)
        return await self._state_storage.persist_transition(
            signals_path,
            message.symbol_key,
            message.timeframe,
            result,
        )
