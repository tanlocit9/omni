from __future__ import annotations

import logging
from typing import Any
from types import SimpleNamespace

import pandas as pd
from py_common.storage.atomic_work import cutoff_frame, day_rows, write_day
from py_common.storage.parquet import ParquetStorage
from py_common.messaging.approved_inputs import approved_manifest_version
from py_common.storage.output_readiness import read_input_document

from app.sector_wave.calculations import (
    SECTOR_FEATURES_SCHEMA,
    aggregate_sector_features,
    calculate_sector_rotation_backtest,
    calculate_symbol_features,
    filter_symbols_for_sector,
)
from app.sector_wave.messages import (
    SectorRotationBacktestJobMessage,
    SectorWaveSectorFeatureJobMessage,
    SectorWaveSymbolFeatureJobMessage,
)
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


class SectorWaveJobHandler:
    """Process Sector Wave precompute and backtest jobs using Parquet storage."""

    def __init__(
        self,
        settings: AppSettings,
        parquet_storage: ParquetStorage,
        metadata_reader=None,
    ) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage
        self._metadata_reader = metadata_reader

    async def handle_symbol_features(self, payload: dict[str, Any]) -> int:
        message = SectorWaveSymbolFeatureJobMessage.model_validate(payload)
        exchange, code = message.parse_symbol_key()
        eod_path = self._settings.stock_data_paths.eod(exchange, code)
        features_path = self._settings.stock_data_paths.symbol_features(
            message.timeframe,
            exchange,
            code,
        )

        _logger.info(
            "Precomputing symbol features symbolKey=%s eodPath=%s featuresPath=%s",
            message.symbol_key,
            eod_path,
            features_path,
        )
        document = await self._input_document(message.metadata)
        eod_frame = await self._read_input(
            "eod", {"exchange": exchange.lower(), "code": code.lower()},
            eod_path, message.metadata, document,
        )
        eod_frame = cutoff_frame(eod_frame, message.business_date, require_day=True)
        result = calculate_symbol_features(
            eod_frame,
            symbol_key=message.symbol_key,
            exchange=exchange,
            code=code,
        )
        await write_day(
            self._parquet_storage, features_path, result, message.business_date
        )
        return len(day_rows(result, message.business_date))

    async def handle_sector_features(self, payload: dict[str, Any]) -> int:
        message = SectorWaveSectorFeatureJobMessage.model_validate(payload)
        document = await self._input_document(message.metadata)
        if "approvedInputs" in message.metadata:
            members = [
                SimpleNamespace(exchange=binding["partition"]["exchange"].upper(), code=binding["partition"]["code"].upper())
                for binding in message.metadata["approvedInputs"]
                if binding["dataset"] == "symbol-features"
                and binding["partition"]["timeframe"] == message.timeframe
            ]
            if not members:
                raise ValueError("Sector work has no approved symbol feature inputs")
        else:
            members = await self._load_sector_members(
                message.sector_code, message.sector_level
            )
        symbol_frames: list[pd.DataFrame] = []
        for member in members:
            path = self._settings.stock_data_paths.symbol_features(
                message.timeframe,
                member.exchange,
                member.code,
            )
            frame = await self._read_input(
                "symbol-features", {"exchange": member.exchange.lower(), "code": member.code.lower(), "timeframe": message.timeframe},
                path, message.metadata, document, optional=True,
            )
            if frame is not None and not frame.empty:
                symbol_frames.append(cutoff_frame(frame, message.business_date))

        result = aggregate_sector_features(
            symbol_frames,
            sector_code=message.sector_code,
            sector_level=message.sector_level,
        )
        path = self._settings.stock_data_paths.sector_features(
            message.timeframe,
            message.sector_level,
            message.sector_code,
        )
        _logger.info(
            "Precomputing sector features "
            "sectorCode=%s sectorLevel=%s members=%s path=%s",
            message.sector_code,
            message.sector_level,
            len(members),
            path,
        )
        await write_day(
            self._parquet_storage,
            path,
            result,
            message.business_date,
            schema=SECTOR_FEATURES_SCHEMA,
        )
        return len(day_rows(result, message.business_date))

    async def handle_sector_rotation_backtest(self, payload: dict[str, Any]) -> int:
        message = SectorRotationBacktestJobMessage.model_validate(payload)
        document = await self._input_document(message.metadata)
        sector_frames: list[pd.DataFrame] = []
        for sector_code in message.sector_codes:
            path = self._settings.stock_data_paths.sector_features(
                message.timeframe,
                message.sector_level,
                sector_code,
            )
            frame = await self._read_input(
                "sector-features", {"timeframe": message.timeframe, "sector_level": str(message.sector_level), "sector_code": sector_code.lower()},
                path, message.metadata, document, optional=True,
            )
            if frame is not None and not frame.empty:
                sector_frames.append(cutoff_frame(frame, message.business_date))

        result = calculate_sector_rotation_backtest(
            sector_frames,
            strategy=message.strategy,
            sector_level=message.sector_level,
        )
        path = self._settings.stock_data_paths.sector_rotation_backtest(
            message.strategy,
            message.timeframe,
            message.sector_level,
        )
        _logger.info(
            "Writing sector rotation backtest "
            "strategy=%s sectorLevel=%s sectors=%s path=%s",
            message.strategy,
            message.sector_level,
            message.sector_codes,
            path,
        )
        await write_day(self._parquet_storage, path, result, message.business_date)
        return len(day_rows(result, message.business_date))

    async def _input_document(self, metadata):
        if "approvedInputs" in metadata or getattr(self._settings, "output_readiness_mode", "manifest") == "postgres":
            if self._metadata_reader is None:
                raise ValueError("Bound input metadata reader unavailable")
            return await read_input_document(self._metadata_reader, metadata)
        return None

    async def _read_input(self, dataset, partition, path, metadata, document, *, optional=False):
        if document is not None:
            manifest = document.resolve(dataset, partition)
            if manifest is None or manifest.path != path:
                raise ValueError("Required input partition is not READY at the canonical path")
            expected = (
                approved_manifest_version(metadata, dataset, partition, manifest, required=True)
                if "approvedInputs" in metadata else manifest.dataVersion
            )
            return await self._parquet_storage.read_bound_dataframe(dataset, manifest, expected)
        if optional:
            return await self._parquet_storage.read_optional_dataframe(path)
        return await self._parquet_storage.read_dataframe(path)

    async def _load_sector_members(self, sector_code: str, sector_level: int):
        members = []
        exchanges = self._resolve_symbol_metadata_exchanges()
        for exchange in exchanges:
            path = self._settings.stock_data_paths.symbols(exchange)
            frame = await self._parquet_storage.read_optional_dataframe(path)
            if frame is not None and not frame.empty:
                members.extend(
                    filter_symbols_for_sector(
                        frame,
                        sector_code=sector_code,
                        sector_level=sector_level,
                    )
                )
        return members

    def _resolve_symbol_metadata_exchanges(self) -> list[str]:
        configured = getattr(self._settings, "sector_wave_symbol_exchanges", None)
        if configured:
            return [str(exchange).upper() for exchange in configured]
        return ["HOSE", "HNX", "UPCOM"]
