from __future__ import annotations

import logging
from typing import Any

import pandas as pd
from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
)
from py_common.storage.global_metadata import GlobalMetadataReader
from py_common.storage.parquet import ParquetStorage

from app.settings import AppSettings
from app.signals.intraday_confirmation import (
    IntradayDatasetResolver,
    calculate_intraday_facts,
    evaluate_intraday_confirmation,
)
from app.signals.messages import SignalJobMessage
from app.signals.storage import SignalHistoryRepository, SignalTransition
from app.signals.strategy import (
    CONFIRMED_TREND_EQUALS,
    ICHIMOKU_V1,
    TREND_MOMENTUM_V1,
    MarketSignal,
    SignalComponent,
    SignalResult,
    calculate_confirmed_trend_equals,
    calculate_confirmed_trend_equals_v2,
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
        intraday_resolver: IntradayDatasetResolver | None = None,
    ) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._metadata_reader = metadata_reader
        self._intraday_resolver = intraday_resolver
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
        if eod_manifest is None or eod_manifest.status != "READY":
            raise ManifestInvalidError("Signal EOD input must have READY metadata")
        eod_frame = await self._parquet_storage.read_dataframe(eod_manifest.path)

        if message.strategy == CONFIRMED_TREND_EQUALS:
            return await self._handle_confirmed_trend_equals(
                message.symbol_key,
                message.timeframe,
                exchange,
                history_path,
                current_path,
                eod_frame,
                eod_manifest.dataVersion,
            )

        indicator_manifest = document.resolve("indicators", indicator_partition)
        if indicator_manifest is None or indicator_manifest.status != "READY":
            raise ManifestInvalidError(
                "Signal indicator input must have READY metadata"
            )
        indicators_path = indicator_manifest.path

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

        _logger.info(
            "Calculated signal symbolKey=%s strategy=%s modelVersion=%s "
            "signal=%s score=%s reasonCodes=%s",
            message.symbol_key,
            result.strategy,
            result.model_version,
            result.signal.value,
            result.score,
            result.reason_codes,
        )
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

    async def _handle_confirmed_trend_equals(
        self,
        symbol_key: str,
        timeframe: str,
        exchange: str,
        history_path: str,
        current_path: str,
        eod_frame: pd.DataFrame,
        eod_data_version: str,
    ) -> SignalTransition:
        component_results = []
        for strategy in (TREND_MOMENTUM_V1, ICHIMOKU_V1):
            component_path = self._settings.stock_data_paths.signal_history(
                strategy,
                timeframe,
                exchange,
            )
            result = await self._signal_repository.latest_result(
                component_path,
                symbol_key,
                timeframe,
                strategy,
            )
            if result is None:
                result = SignalResult(
                    MarketSignal.NO_DECISION,
                    None,
                    None,
                    [f"MISSING_COMPONENT_{strategy}"],
                    0,
                    strategy,
                )
            component_results.append(SignalComponent(symbol_key, timeframe, result))

        expected_signal_date = None
        if {"date", "ad_close"}.issubset(eod_frame.columns):
            valid_eod = eod_frame.dropna(subset=["date", "ad_close"])
            if not valid_eod.empty:
                expected_signal_date = (
                    pd.Timestamp(valid_eod["date"].max()).date().isoformat()
                )
        daily_candidate = calculate_confirmed_trend_equals(
            *component_results,
            expected_signal_date=expected_signal_date,
        )
        if self._intraday_resolver is None:
            return await self._signal_repository.persist_transition(
                history_path,
                current_path,
                symbol_key,
                timeframe,
                daily_candidate,
                exchange=exchange,
                eod_data_version=eod_data_version,
            )
        intraday_result = await self._resolve_intraday_confirmation(
            symbol_key=symbol_key,
            exchange=exchange,
            signal_date=daily_candidate.signal_date or expected_signal_date,
        )
        result = calculate_confirmed_trend_equals_v2(
            daily_candidate,
            intraday_result,
        )
        _logger.info(
            "Calculated confirmed signal symbolKey=%s modelVersion=%s "
            "dailySignal=%s intradayResult=%s finalSignal=%s score=%s "
            "reasonCodes=%s",
            symbol_key,
            result.model_version,
            daily_candidate.signal.value,
            intraday_result.result.value,
            result.signal.value,
            result.score,
            result.reason_codes,
        )
        return await self._signal_repository.persist_transition(
            history_path,
            current_path,
            symbol_key,
            timeframe,
            result,
            exchange=exchange,
            eod_data_version=eod_data_version,
        )

    async def _resolve_intraday_confirmation(
        self,
        *,
        symbol_key: str,
        exchange: str,
        signal_date: str | None,
    ):
        if signal_date is None:
            return evaluate_intraday_confirmation(None, ["MISSING_INTRADAY"])
        symbol = symbol_key.split("-", maxsplit=1)[1]
        resolution = await self._intraday_resolver.resolve(
            provider="VCI",
            exchange=exchange,
            symbol=symbol,
            trading_date=signal_date,
        )
        if resolution.dataset is None:
            return evaluate_intraday_confirmation(None, resolution.reason_codes)
        try:
            facts = calculate_intraday_facts(
                resolution.dataset,
                symbol_key=symbol_key,
                trading_date=signal_date,
            )
        except ValueError:
            return evaluate_intraday_confirmation(
                None,
                ["INVALID_INTRADAY_PARTITION"],
            )
        return evaluate_intraday_confirmation(facts)
