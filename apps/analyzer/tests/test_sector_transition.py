from __future__ import annotations

from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pandas as pd
import pytest

from app.settings import AppSettings

from app.sector_transition.calculations import (
    calculate_sector_transition_analysis,
    evaluate_sector_transition_outcomes,
)
from app.sector_transition.kafka import SectorTransitionAnalyzeKafkaService
from app.sector_transition.messages import (
    SectorTransitionAnalyzeJobMessage,
    SectorTransitionOutcomeEvaluationJobMessage,
)


def _sector_frame(
    sector_code: str, returns: list[float], strengths: list[float]
) -> pd.DataFrame:
    dates = pd.date_range("2026-01-01", periods=len(returns), freq="B")
    return pd.DataFrame(
        {
            "date": dates,
            "sector_code": sector_code,
            "sector_level": 2,
            "sector_index": [100 + index for index in range(len(returns))],
            "sector_return": returns,
            "relative_strength": strengths,
            "ma20": [99.0] * len(returns),
            "volume_ratio": [1.0] * len(returns),
            "breadth_above_ma20": [1.0] * len(returns),
            "coverage_ratio": [1.0] * len(returns),
            "top_3_contribution_share": [1.0] * len(returns),
            "contributors": [[] for _ in returns],
            "contributors_json": ["[]"] * len(returns),
        }
    )


def _analyze_payload() -> dict:
    return {
        "jobDefinitionId": "job-definition-id",
        "executionId": "execution-id",
        "source": "ANALYZER",
        "evaluationDate": "2026-01-08",
        "sectorCodes": ["tech", "BANKS"],
        "focusSectorCodes": [" banks "],
        "sectorLevel": 2,
        "timeframe": "1d",
        "strategy": "SECTOR_TRANSITION_V1",
        "predictionHorizons": [5, 1, 1],
    }


class _FakeProducer:
    def __init__(self) -> None:
        self.sent = []

    async def send_and_wait(self, topic, payload, key=None):
        self.sent.append((topic, payload, key))
        return SimpleNamespace(topic=topic, partition=0, offset=len(self.sent) - 1)


def test_sector_transition_analyze_message_normalizes_contract():
    message = SectorTransitionAnalyzeJobMessage.model_validate(_analyze_payload())

    assert message.evaluation_date == date(2026, 1, 8)
    assert message.sector_codes == ["BANKS", "TECH"]
    assert message.focus_sector_codes == ["BANKS"]
    assert message.prediction_horizons == [1, 5]


def test_sector_transition_message_defaults_empty_focus_to_universe():
    message = SectorTransitionAnalyzeJobMessage.model_validate(
        _analyze_payload() | {"focusSectorCodes": []}
    )

    assert message.focus_sector_codes == ["BANKS", "TECH"]


def test_sector_transition_message_rejects_focus_outside_universe():
    payload = _analyze_payload() | {"focusSectorCodes": ["OIL"]}

    with pytest.raises(ValueError, match="focusSectorCodes"):
        SectorTransitionAnalyzeJobMessage.model_validate(payload)


def test_sector_transition_outcome_message_rejects_invalid_horizons():
    payload = _analyze_payload() | {"predictionHorizons": [0]}

    with pytest.raises(ValueError):
        SectorTransitionOutcomeEvaluationJobMessage.model_validate(payload)


@pytest.mark.anyio
async def test_sector_transition_kafka_success_status_preserves_domain_metadata():
    settings = AppSettings(sector_transition_analyze_kafka_enabled=False)
    handler = AsyncMock()
    handler.handle_analyze.return_value = 3
    service = SectorTransitionAnalyzeKafkaService(settings, handler)
    producer = _FakeProducer()
    service._producer = producer

    status = await service.process_payload(_analyze_payload())

    assert status.status.value == "SUCCESS"
    assert status.records_processed == 3
    assert status.meta_json == {
        "recordsProcessed": 3,
        "evaluationDate": "2026-01-08",
        "sectorCodes": ["BANKS", "TECH"],
        "focusSectorCodes": ["BANKS"],
        "sectorLevel": 2,
        "timeframe": "1d",
        "strategy": "SECTOR_TRANSITION_V1",
        "predictionHorizons": [1, 5],
    }
    assert producer.sent


@pytest.mark.anyio
async def test_sector_transition_kafka_error_status_preserves_domain_metadata():
    settings = AppSettings(sector_transition_analyze_kafka_enabled=False)
    handler = AsyncMock()
    handler.handle_analyze.side_effect = RuntimeError("sector frame missing")
    service = SectorTransitionAnalyzeKafkaService(settings, handler)
    producer = _FakeProducer()
    service._producer = producer

    status = await service.process_payload(_analyze_payload())

    assert status.status.value == "ERROR"
    assert status.records_processed == 0
    assert status.error_message == "sector frame missing"
    assert status.meta_json["recordsProcessed"] == 0
    assert status.meta_json["errorMessage"] == "sector frame missing"
    assert status.meta_json["evaluationDate"] == "2026-01-08"
    assert status.meta_json["sectorCodes"] == ["tech", "BANKS"]
    assert status.meta_json["focusSectorCodes"] == [" banks "]
    assert status.meta_json["sectorLevel"] == 2
    assert status.meta_json["timeframe"] == "1d"
    assert status.meta_json["strategy"] == "SECTOR_TRANSITION_V1"
    assert status.meta_json["predictionHorizons"] == [5, 1, 1]
    assert producer.sent


def test_calculate_sector_transition_analysis_is_t_anchored_and_private_decision():
    banks = _sector_frame(
        "BANKS",
        returns=[0.01, 0.02, 0.01, 0.01, 0.02, 0.10, -0.50],
        strengths=[0.01, 0.02, 0.04, 0.03, 0.07, 0.50, -0.50],
    )
    tech = _sector_frame(
        "TECH",
        returns=[0.01, 0.01, 0.03, 0.03, 0.01, -0.50, 0.50],
        strengths=[0.02, 0.03, 0.01, 0.04, 0.02, -0.50, 0.90],
    )

    result = calculate_sector_transition_analysis(
        [banks, tech],
        evaluation_date=date(2026, 1, 7),
        sector_codes=["BANKS", "TECH"],
        focus_sector_codes=["BANKS"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[1, 5],
    )

    assert set(result.predictions["from_sector"]) == {"BANKS"}
    assert set(result.predictions["to_sector"]) == {"BANKS", "TECH"}
    assert set(result.predictions["horizon_sessions"]) == {1, 5}
    assert set(result.decisions["visibility"]) == {"PRIVATE_INTERNAL"}
    assert set(result.probabilities["from_sector"]) == {"BANKS", "TECH"}
    banks_t1 = result.predictions[
        (result.predictions["to_sector"] == "BANKS")
        & (result.predictions["horizon_sessions"] == 1)
    ].iloc[0]
    assert banks_t1["generated_from_date"] == date(2026, 1, 7)
    assert banks_t1["target_date"] is None
    assert banks_t1["current_relative_strength"] == pytest.approx(0.07)


def test_sector_transition_analysis_excludes_post_t_rows_from_model_training():
    banks = _sector_frame(
        "BANKS",
        returns=[0.01, 0.01, 0.01, 0.01, 0.01, 0.90, 0.90],
        strengths=[0.01, 0.02, 0.03, 0.04, 0.05, 0.95, 0.95],
    )
    tech = _sector_frame(
        "TECH",
        returns=[0.02, 0.02, 0.02, 0.02, 0.02, -0.90, -0.90],
        strengths=[0.05, 0.04, 0.03, 0.02, 0.01, -0.95, -0.95],
    )

    baseline = calculate_sector_transition_analysis(
        [banks.iloc[:5], tech.iloc[:5]],
        evaluation_date=date(2026, 1, 7),
        sector_codes=["BANKS", "TECH"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[1],
    )
    with_future_rows = calculate_sector_transition_analysis(
        [banks, tech],
        evaluation_date=date(2026, 1, 7),
        sector_codes=["BANKS", "TECH"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[1],
    )

    baseline_predictions = baseline.predictions.sort_values(
        ["from_sector", "to_sector"]
    ).reset_index(drop=True)
    future_predictions = with_future_rows.predictions.sort_values(
        ["from_sector", "to_sector"]
    ).reset_index(drop=True)
    pd.testing.assert_series_equal(
        future_predictions["predicted_return"],
        baseline_predictions["predicted_return"],
        check_names=False,
    )
    pd.testing.assert_frame_equal(
        with_future_rows.probabilities.sort_values(
            ["from_sector", "to_sector", "horizon_sessions"]
        ).reset_index(drop=True),
        baseline.probabilities.sort_values(
            ["from_sector", "to_sector", "horizon_sessions"]
        ).reset_index(drop=True),
    )


def test_sector_transition_probabilities_include_self_transitions_and_sum_to_one():
    banks = _sector_frame(
        "BANKS",
        returns=[0.01, 0.01, 0.01, 0.01, 0.01],
        strengths=[0.9, 0.8, 0.1, 0.2, 0.9],
    )
    tech = _sector_frame(
        "TECH",
        returns=[0.02, 0.02, 0.02, 0.02, 0.02],
        strengths=[0.1, 0.2, 0.9, 0.8, 0.1],
    )

    result = calculate_sector_transition_analysis(
        [banks, tech],
        evaluation_date=date(2026, 1, 7),
        sector_codes=["BANKS", "TECH"],
        focus_sector_codes=["BANKS"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[1],
    )

    rows = result.probabilities[result.probabilities["from_sector"] == "BANKS"]
    assert set(rows["to_sector"]) == {"BANKS", "TECH"}
    assert rows["probability"].sum() == pytest.approx(1.0)
    assert rows[rows["to_sector"] == "BANKS"].iloc[0]["probability"] > 0.0
    assert set(result.predictions["from_sector"]) == {"BANKS"}
    assert set(result.predictions["to_sector"]) == {"BANKS", "TECH"}


def test_sector_transition_blocks_when_universe_sector_state_missing():
    banks = _sector_frame("BANKS", returns=[0.01], strengths=[0.1])

    with pytest.raises(ValueError, match="BLOCKED"):
        calculate_sector_transition_analysis(
            [banks],
            evaluation_date=date(2026, 1, 7),
            sector_codes=["BANKS", "TECH"],
            sector_level=2,
            timeframe="1d",
            strategy="SECTOR_TRANSITION_V1",
            prediction_horizons=[1],
        )


def test_evaluate_sector_transition_outcomes_does_not_rewrite_predictions():
    banks = _sector_frame(
        "BANKS",
        returns=[0.01, 0.02, 0.01, 0.01, 0.02, 0.10],
        strengths=[0.01, 0.02, 0.04, 0.03, 0.07, 0.50],
    )
    tech = _sector_frame(
        "TECH",
        returns=[0.01, 0.01, 0.03, 0.03, 0.01, -0.05],
        strengths=[0.02, 0.03, 0.01, 0.04, 0.02, -0.50],
    )
    analysis = calculate_sector_transition_analysis(
        [banks, tech],
        evaluation_date=date(2026, 1, 7),
        sector_codes=["BANKS", "TECH"],
        focus_sector_codes=["BANKS"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[1],
    )
    predictions_before = analysis.predictions.copy(deep=True)

    outcomes = evaluate_sector_transition_outcomes(
        analysis.predictions,
        [banks, tech],
        evaluation_date=date(2026, 1, 7),
        sector_codes=["BANKS", "TECH"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[1],
    )

    pd.testing.assert_frame_equal(analysis.predictions, predictions_before)
    assert len(outcomes) == 2
    banks_outcome = outcomes[outcomes["to_sector"] == "BANKS"].iloc[0]
    assert banks_outcome["resolved_date"] == date(2026, 1, 8)
    assert banks_outcome["realized_return"] == pytest.approx(0.10)
