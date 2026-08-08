from __future__ import annotations

import logging
from typing import Any

import pandas as pd
from py_common.storage.parquet import ParquetStorage

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

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage

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
        eod_frame = await self._parquet_storage.read_dataframe(eod_path)
        result = calculate_symbol_features(
            eod_frame,
            symbol_key=message.symbol_key,
            exchange=exchange,
            code=code,
        )
        await self._parquet_storage.write_dataframe(features_path, result)
        return len(result)

    async def handle_sector_features(self, payload: dict[str, Any]) -> int:
        message = SectorWaveSectorFeatureJobMessage.model_validate(payload)
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
            frame = await self._parquet_storage.read_optional_dataframe(path)
            if frame is not None and not frame.empty:
                symbol_frames.append(frame)

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
        await self._parquet_storage.write_dataframe(
            path,
            result,
            schema=SECTOR_FEATURES_SCHEMA,
        )
        return len(result)

    async def handle_sector_rotation_backtest(self, payload: dict[str, Any]) -> int:
        message = SectorRotationBacktestJobMessage.model_validate(payload)
        sector_frames: list[pd.DataFrame] = []
        for sector_code in message.sector_codes:
            path = self._settings.stock_data_paths.sector_features(
                message.timeframe,
                message.sector_level,
                sector_code,
            )
            frame = await self._parquet_storage.read_optional_dataframe(path)
            if frame is not None and not frame.empty:
                sector_frames.append(frame)

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
        await self._parquet_storage.write_dataframe(path, result)
        return len(result)

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
