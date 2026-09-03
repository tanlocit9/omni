from __future__ import annotations

import logging
from typing import Any

from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.global_metadata import GlobalMetadataReader
from py_common.storage.parquet import ParquetStorage

from app.calculations.indicators import calculate_supported_indicators
from app.indicators.messages import IndicatorJobMessage
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


class IndicatorJobHandler:
    """Process indicator calculation jobs using shared storage abstractions."""

    def __init__(
        self,
        settings: AppSettings,
        parquet_storage: ParquetStorage,
        metadata_reader: GlobalMetadataReader,
    ) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._metadata_reader = metadata_reader

    async def handle(self, payload: dict[str, Any]) -> int:
        message = IndicatorJobMessage.model_validate(payload)
        raw_exchange, raw_code = message.parse_symbol_key()
        exchange = raw_exchange.lower()
        code = raw_code.lower()
        eod_partition = {"exchange": exchange, "code": code}
        document = await self._metadata_reader.read()
        eod_manifest = document.resolve("eod", eod_partition)
        if eod_manifest is None or eod_manifest.status != "READY":
            status = eod_manifest.status if eod_manifest is not None else "MISSING"
            raise ManifestInvalidError(
                "EOD metadata must be READY for "
                f"exchange={exchange} code={code}; status={status}"
            )

        indicators_path = self._build_indicators_path(
            message.indicator_source,
            message.timeframe,
            exchange,
            code,
        )

        _logger.info(
            "Calculating indicators for symbolKey=%s eodPath=%s eodDataVersion=%s "
            "indicatorsPath=%s",
            message.symbol_key,
            eod_manifest.path,
            eod_manifest.dataVersion,
            indicators_path,
        )
        eod_frame = await self._parquet_storage.read_dataframe(eod_manifest.path)
        result = calculate_supported_indicators(
            eod_frame,
            message.indicator_source,
            message.indicators,
            self._settings.scheduler,
        )
        result = result.copy()
        result["eod_data_version"] = eod_manifest.dataVersion
        await self._parquet_storage.write_dataframe(indicators_path, result)
        _logger.info(
            "Persisted indicators partition source=%s timeframe=%s "
            "exchange=%s code=%s eodDataVersion=%s",
            message.indicator_source,
            message.timeframe,
            exchange,
            code,
            eod_manifest.dataVersion,
        )

        return len(result)

    def _build_indicators_path(
        self, source: str, timeframe: str, exchange: str, code: str
    ) -> str:
        try:
            return self._settings.stock_data_paths.indicators(
                source,
                timeframe,
                exchange,
                code,
            )
        except TypeError as exc:
            if "positional arguments" not in str(exc):
                raise

            legacy_path = self._settings.stock_data_paths.indicators(
                timeframe,
                exchange,
                code,
            )
            indicators_base = self._settings.stock_data_paths.indicators_base
            normalized_source = self._settings.stock_data_paths._normalize_path_part(
                source, "source"
            )
            return legacy_path.replace(
                indicators_base,
                f"{indicators_base}{normalized_source}/",
                1,
            )
