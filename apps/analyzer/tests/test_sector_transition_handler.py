from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pandas as pd
import pytest

from app.sector_transition.calculations import TransitionAnalysisResult
from app.sector_transition.handler import SectorTransitionJobHandler
from app.settings import AppSettings


def payload() -> dict[str, object]:
    return {
        "jobDefinitionId": "job-definition-id",
        "executionId": "execution-id",
        "source": "ANALYZER",
        "workType": "GLOBAL",
        "workKey": "SECTOR_TRANSITION_V1",
        "evaluationDate": "2026-01-08",
        "sectorCodes": ["BANKS", "TECH"],
        "focusSectorCodes": ["BANKS"],
        "sectorLevel": 2,
        "timeframe": "1d",
        "strategy": "SECTOR_TRANSITION_V1",
        "predictionHorizons": [1],
    }


def result_frame(value: int) -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "evaluation_date": "2026-01-08",
                "strategy": "SECTOR_TRANSITION_V1",
                "timeframe": "1d",
                "sector_level": 2,
                "from_sector": "BANKS",
                "to_sector": "TECH",
                "horizon_sessions": 1,
                "value": value,
            }
        ]
    )


@pytest.mark.anyio
async def test_analyze_loads_universe_and_writes_each_output_once() -> None:
    storage = AsyncMock()
    storage.read_optional_dataframe.side_effect = [
        pd.DataFrame({"sector_code": ["BANKS"]}),
        None,
        None,
        None,
        None,
    ]
    storage.write_dataframe = AsyncMock()
    handler = SectorTransitionJobHandler(AppSettings(), storage)
    result = TransitionAnalysisResult(
        predictions=result_frame(1),
        decisions=result_frame(2),
        probabilities=result_frame(3),
    )

    with patch(
        "app.sector_transition.handler.calculate_sector_transition_analysis",
        return_value=result,
    ) as calculate:
        count = await handler.handle_analyze(payload())

    assert count == 3
    assert calculate.call_args.args[0][0]["sector_code"].tolist() == ["BANKS"]
    assert storage.write_dataframe.await_count == 3
    written_paths = [call.args[0] for call in storage.write_dataframe.await_args_list]
    assert len(set(written_paths)) == 3


@pytest.mark.anyio
async def test_evaluate_outcomes_blocks_without_predictions() -> None:
    storage = AsyncMock()
    storage.read_optional_dataframe.return_value = None
    handler = SectorTransitionJobHandler(AppSettings(), storage)

    count = await handler.handle_evaluate_outcomes(payload())

    assert count.records_processed == 0
    assert count.reason == "NO_PREDICTIONS"
    storage.write_dataframe.assert_not_awaited()


@pytest.mark.anyio
async def test_evaluate_outcomes_skips_saved_identity_without_rewriting() -> None:
    storage = AsyncMock()
    existing = result_frame(1)
    existing["evaluation_date"] = "2026-01-07"
    predictions = result_frame(0)
    predictions["evaluation_date"] = "2026-01-07"
    storage.read_optional_dataframe.side_effect = [predictions, existing]
    handler = SectorTransitionJobHandler(AppSettings(), storage)
    with patch("app.sector_transition.handler.evaluate_sector_transition_outcomes") as evaluate:
        result = await handler.handle_evaluate_outcomes(payload())
    assert result.records_processed == 0
    assert result.reason == "ALREADY_EVALUATED"
    evaluate.assert_not_called()
    storage.write_dataframe.assert_not_awaited()


@pytest.mark.anyio
async def test_write_merged_preserves_existing_when_retry_has_no_rows() -> None:
    storage = AsyncMock()
    existing = result_frame(7)
    storage.read_optional_dataframe.return_value = existing
    handler = SectorTransitionJobHandler(AppSettings(), storage)

    await handler._write_merged(
        "shared.parquet",
        pd.DataFrame(),
        key_columns=["evaluation_date"],
    )

    storage.write_dataframe.assert_not_awaited()
