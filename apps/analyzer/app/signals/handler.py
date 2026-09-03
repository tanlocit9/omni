from __future__ import annotations

import logging
from typing import Any

from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
)
from py_common.storage.global_metadata import GlobalMetadataReader
from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.messages import SignalJobMessage
from app.signals.storage import SignalHistoryRepository, SignalTransition
from app.signals.strategy import (
    ICHIMOKU_V1,
    TREND_MOMENTUM_V1,
    MarketSignal,
    SignalResult,
    calculate_ichimoku_v1,
    calculate_trend_momentum_v1,
)

_logger = logging.getLogger(__name__)


class SignalJobHandler:
    """Process versioned market-signal strategies using shared Parquet storage."""

    def __init__(
        self,
        settings: AppSettings,
        parquet_storage: ParquetStorage,
        metadata_reader: GlobalMetadataReader,
    ) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._metadata_reader = metadata_reader
        self._signal_repository = SignalHistoryRepository(parquet_storage)

    async def handle(self, payload: dict[str, Any]) -> SignalTransition:
        message = SignalJobMessage.model_validate(payload)
        exchange, code = message.parse_symbol_key()

        eod_partition = {"exchange": exchange.lower(), "code": code.lower()}
        indicator_partition = {
            "source": "ad_close",
            "timeframe": message.timeframe,
            **eod_partition,
        }
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
        document = await self._metadata_reader.read()
        eod_manifest = document.resolve("eod", eod_partition)
        indicator_manifest = document.resolve("indicators", indicator_partition)
        if (
            eod_manifest is None
            or indicator_manifest is None
            or eod_manifest.status != "READY"
            or indicator_manifest.status != "READY"
        ):
            raise ManifestInvalidError("Signal inputs must have READY metadata")
        eod_path = eod_manifest.path
        indicators_path = indicator_manifest.path

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
            if message.strategy == TREND_MOMENTUM_V1:
                result = calculate_trend_momentum_v1(eod_frame, indicators_frame)
            elif message.strategy == ICHIMOKU_V1:
                result = calculate_ichimoku_v1(eod_frame, indicators_frame)
            else:
                raise ValueError(f"Unsupported signal strategy: {message.strategy}")

        return await self._signal_repository.persist_transition(
            history_path,
            current_path,
            message.symbol_key,
            message.timeframe,
            result,
            exchange=exchange,
            eod_data_version=eod_manifest.dataVersion,
            indicators_data_version=indicator_manifest.dataVersion,
        )
