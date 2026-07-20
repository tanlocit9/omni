from __future__ import annotations

import logging
from typing import Any

from py_common.storage.parquet import ParquetStorage

from app.calculations.indicators import calculate_supported_indicators
from app.indicators.messages import IndicatorJobMessage
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


class IndicatorJobHandler:
    """Process indicator calculation jobs using shared storage abstractions."""

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage

    async def handle(self, payload: dict[str, Any]) -> int:
        message = IndicatorJobMessage.model_validate(payload)
        exchange, code = message.parse_symbol_key()

        eod_path = self._settings.stock_data_paths.eod(exchange, code)
        indicators_path = self._settings.stock_data_paths.indicators(
            message.timeframe,
            exchange,
            code,
        )

        _logger.info(
            "Calculating indicators for symbolKey=%s eodPath=%s indicatorsPath=%s",
            message.symbol_key,
            eod_path,
            indicators_path,
        )
        eod_frame = await self._parquet_storage.read_dataframe(eod_path)
        result = calculate_supported_indicators(eod_frame)
        await self._parquet_storage.write_dataframe(indicators_path, result)
        return len(result)
