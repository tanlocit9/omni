from __future__ import annotations

import logging
from typing import Any

import pandas as pd
from py_common.storage.parquet import ParquetStorage

from app.sector_transition.calculations import (
    calculate_sector_transition_analysis,
    evaluate_sector_transition_outcomes,
)
from app.sector_transition.messages import (
    SectorTransitionAnalyzeJobMessage,
    SectorTransitionOutcomeEvaluationJobMessage,
)
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


class SectorTransitionJobHandler:
    """Process T-anchored Sector Transition analysis and outcome jobs."""

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage

    async def handle_analyze(self, payload: dict[str, Any]) -> int:
        message = SectorTransitionAnalyzeJobMessage.model_validate(payload)
        sector_frames = await self._load_sector_frames(
            message.timeframe,
            message.sector_level,
            message.sector_codes,
        )
        result = calculate_sector_transition_analysis(
            sector_frames,
            evaluation_date=message.evaluation_date,
            sector_codes=message.sector_codes,
            focus_sector_codes=message.focus_sector_codes,
            sector_level=message.sector_level,
            timeframe=message.timeframe,
            strategy=message.strategy,
            prediction_horizons=message.prediction_horizons,
        )

        predictions_path = (
            self._settings.stock_data_paths.sector_transition_predictions(
                message.strategy,
                message.timeframe,
                message.sector_level,
            )
        )
        decisions_path = self._settings.stock_data_paths.sector_transition_decisions(
            message.strategy,
            message.timeframe,
            message.sector_level,
        )
        probabilities_path = (
            self._settings.stock_data_paths.sector_transition_probabilities(
                message.strategy,
                message.timeframe,
                message.sector_level,
            )
        )
        _logger.info(
            "Writing Sector Transition analysis "
            "evaluationDate=%s universe=%s focus=%s paths=%s,%s,%s",
            message.evaluation_date,
            message.sector_codes,
            message.focus_sector_codes,
            predictions_path,
            decisions_path,
            probabilities_path,
        )
        await self._write_merged(
            predictions_path,
            result.predictions,
            key_columns=[
                "evaluation_date",
                "strategy",
                "timeframe",
                "sector_level",
                "from_sector",
                "to_sector",
                "horizon_sessions",
            ],
        )
        await self._write_merged(
            decisions_path,
            result.decisions,
            key_columns=[
                "evaluation_date",
                "strategy",
                "timeframe",
                "sector_level",
                "from_sector",
                "to_sector",
                "horizon_sessions",
            ],
        )
        await self._write_merged(
            probabilities_path,
            result.probabilities,
            key_columns=[
                "evaluation_date",
                "strategy",
                "timeframe",
                "sector_level",
                "from_sector",
                "to_sector",
                "horizon_sessions",
            ],
        )
        return (
            len(result.predictions) + len(result.decisions) + len(result.probabilities)
        )

    async def handle_evaluate_outcomes(self, payload: dict[str, Any]) -> int:
        message = SectorTransitionOutcomeEvaluationJobMessage.model_validate(payload)
        predictions_path = (
            self._settings.stock_data_paths.sector_transition_predictions(
                message.strategy,
                message.timeframe,
                message.sector_level,
            )
        )
        predictions = await self._parquet_storage.read_optional_dataframe(
            predictions_path
        )
        if predictions is None or predictions.empty:
            _logger.info(
                "Sector Transition outcomes blocked by missing predictions path=%s",
                predictions_path,
            )
            return 0

        sector_frames = await self._load_sector_frames(
            message.timeframe,
            message.sector_level,
            message.sector_codes,
        )
        outcomes = evaluate_sector_transition_outcomes(
            predictions,
            sector_frames,
            evaluation_date=message.evaluation_date,
            sector_codes=message.sector_codes,
            sector_level=message.sector_level,
            timeframe=message.timeframe,
            strategy=message.strategy,
            prediction_horizons=message.prediction_horizons,
        )
        outcomes_path = self._settings.stock_data_paths.sector_transition_outcomes(
            message.strategy,
            message.timeframe,
            message.sector_level,
        )
        await self._write_merged(
            outcomes_path,
            outcomes,
            key_columns=[
                "evaluation_date",
                "strategy",
                "timeframe",
                "sector_level",
                "from_sector",
                "to_sector",
                "horizon_sessions",
            ],
        )
        return len(outcomes)

    async def _load_sector_frames(
        self,
        timeframe: str,
        sector_level: int,
        sector_codes: list[str],
    ) -> list[pd.DataFrame]:
        frames: list[pd.DataFrame] = []
        for sector_code in sector_codes:
            path = self._settings.stock_data_paths.sector_features(
                timeframe,
                sector_level,
                sector_code,
            )
            frame = await self._parquet_storage.read_optional_dataframe(path)
            if frame is not None and not frame.empty:
                frames.append(frame)
        return frames

    async def _write_merged(
        self,
        path: str,
        new_rows: pd.DataFrame,
        *,
        key_columns: list[str],
    ) -> None:
        existing = await self._parquet_storage.read_optional_dataframe(path)
        if existing is None or existing.empty:
            merged = new_rows
        elif new_rows.empty:
            merged = existing
        else:
            merged = pd.concat([existing, new_rows], ignore_index=True)
            merged = merged.drop_duplicates(subset=key_columns, keep="last")
        await self._parquet_storage.write_dataframe(path, merged)
