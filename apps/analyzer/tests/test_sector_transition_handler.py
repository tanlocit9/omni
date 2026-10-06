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

    assert count == 0
    storage.write_dataframe.assert_not_awaited()


@pytest.mark.anyio
async def test_evaluate_outcomes_preserves_lineage_and_merges_retry() -> None:
    storage = AsyncMock()
    existing = result_frame(1)
    replacement = result_frame(2)
    storage.read_optional_dataframe.side_effect = [
        result_frame(0),
        pd.DataFrame({"sector_code": ["BANKS"]}),
        pd.DataFrame({"sector_code": ["TECH"]}),
        existing,
    ]
    storage.write_dataframe = AsyncMock()
    handler = SectorTransitionJobHandler(AppSettings(), storage)

    with patch(
        "app.sector_transition.handler.evaluate_sector_transition_outcomes",
        return_value=replacement,
    ) as evaluate:
        count = await handler.handle_evaluate_outcomes(payload())

    assert count == 1
    assert evaluate.call_args.kwargs["evaluation_date"].isoformat() == "2026-01-08"
    written = storage.write_dataframe.await_args.args[1]
    assert len(written) == 1
    assert written.iloc[0]["value"] == 2


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

    pd.testing.assert_frame_equal(storage.write_dataframe.await_args.args[1], existing)
