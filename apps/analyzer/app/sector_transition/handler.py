from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import Any

import pandas as pd
from py_common.storage.atomic_work import cutoff_frame
from py_common.storage.parquet import ParquetStorage

from app.sector_transition.calculations import (
    calculate_sector_transition_analysis,
    evaluate_sector_transition_outcomes,
    select_outcome_predictions,
)
from app.sector_transition.messages import (
    SectorTransitionAnalyzeJobMessage,
    SectorTransitionOutcomeEvaluationJobMessage,
)
from app.sector_transition.records import TRANSITION_KEY, append_immutable_rows, missing_rows
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class OutcomeEvaluationResult:
    records_processed: int
    reason: str
    as_of_date: date


class SectorTransitionJobHandler:
    """Analyze at origin T; score saved predictions as of work day D."""

    def __init__(self, settings: AppSettings, parquet_storage: ParquetStorage) -> None:
        self._settings = settings
        self._parquet_storage = parquet_storage

    async def handle_analyze(self, payload: dict[str, Any]) -> int:
        message = SectorTransitionAnalyzeJobMessage.model_validate(payload)
        sector_frames = await self._load_sector_frames(
            message.timeframe,
            message.sector_level,
            message.sector_codes,
            message.business_date,
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
        # Validate all historical identities before writing any analysis output.
        prepared = []
        for path, frame in ((predictions_path, result.predictions),
                            (decisions_path, result.decisions),
                            (probabilities_path, result.probabilities)):
            existing = await self._parquet_storage.read_optional_dataframe(path)
            merged, inserted = append_immutable_rows(existing, frame, TRANSITION_KEY)
            prepared.append((path, merged, inserted))
        for path, merged, inserted in prepared:
            if inserted:
                await self._parquet_storage.write_dataframe(path, merged)
        return sum(inserted for _, _, inserted in prepared)

    async def handle_evaluate_outcomes(self, payload: dict[str, Any]) -> OutcomeEvaluationResult:
        message = SectorTransitionOutcomeEvaluationJobMessage.model_validate(payload)
        # Existing wire evaluationDate denotes the scan day D for outcomes, not prediction origin T.
        as_of_date = message.business_date or message.evaluation_date
        paths = self._settings.stock_data_paths
        predictions_path = paths.sector_transition_predictions(
            message.strategy, message.timeframe, message.sector_level,
        )
        predictions = await self._parquet_storage.read_optional_dataframe(predictions_path)
        if predictions is None or predictions.empty:
            return OutcomeEvaluationResult(0, "NO_PREDICTIONS", as_of_date)
        candidates = select_outcome_predictions(
            predictions, as_of_date=as_of_date, sector_codes=message.sector_codes,
            focus_sector_codes=message.focus_sector_codes, sector_level=message.sector_level,
            timeframe=message.timeframe, strategy=message.strategy,
            prediction_horizons=message.prediction_horizons,
        )
        if candidates.empty:
            return OutcomeEvaluationResult(0, "NO_ELIGIBLE_PREDICTIONS", as_of_date)
        outcomes_path = paths.sector_transition_outcomes(
            message.strategy, message.timeframe, message.sector_level,
        )
        existing = await self._parquet_storage.read_optional_dataframe(outcomes_path)
        pending = missing_rows(candidates, existing, TRANSITION_KEY)
        if pending.empty:
            return OutcomeEvaluationResult(0, "ALREADY_EVALUATED", as_of_date)
        sector_frames = await self._load_sector_frames(
            message.timeframe, message.sector_level, message.sector_codes, as_of_date,
        )
        outcomes = evaluate_sector_transition_outcomes(
            pending, sector_frames, as_of_date=as_of_date,
            sector_codes=message.sector_codes, sector_level=message.sector_level,
            timeframe=message.timeframe, strategy=message.strategy,
            prediction_horizons=message.prediction_horizons,
        )
        if outcomes.empty:
            return OutcomeEvaluationResult(0, "NO_MATURED_PREDICTIONS", as_of_date)
        # Re-read at append time to preserve rows that arrived during calculation.
        inserted = await self._write_merged(outcomes_path, outcomes, key_columns=TRANSITION_KEY)
        reason = "OUTCOMES_APPENDED" if inserted else "ALREADY_EVALUATED"
        return OutcomeEvaluationResult(inserted, reason, as_of_date)

    async def _load_sector_frames(
        self,
        timeframe: str,
        sector_level: int,
        sector_codes: list[str],
        business_date: date | None = None,
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
                frames.append(cutoff_frame(frame, business_date))
        return frames

    async def _write_merged(
        self, path: str, new_rows: pd.DataFrame, *, key_columns: list[str],
    ) -> int:
        existing = await self._parquet_storage.read_optional_dataframe(path)
        merged, inserted = append_immutable_rows(existing, new_rows, key_columns)
        if inserted:
            await self._parquet_storage.write_dataframe(path, merged)
        return inserted
