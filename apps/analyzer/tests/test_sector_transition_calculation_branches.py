from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from app.sector_transition.calculations import (
    MODEL_VERSION,
    _build_decisions,
    _combine_sector_frames,
    _direction,
    _historical_forward_return,
    _normalize_prediction_schema,
    _resolve_focus,
    _state_at_or_before,
    _to_float,
    evaluate_sector_transition_outcomes,
)


def history() -> pd.DataFrame:
    return pd.DataFrame(
        {
            "date": pd.to_datetime(["2026-01-08", "2026-01-09"]),
            "sector_code": ["BANKS", "BANKS"],
            "sector_return": [0.0, 0.02],
            "relative_strength": [0.1, 0.2],
        }
    )


def prediction(score: float) -> dict[str, object]:
    return {
        "evaluation_date": date(2026, 1, 8),
        "target_date": None,
        "strategy": "SECTOR_TRANSITION_V1",
        "timeframe": "1d",
        "sector_level": 2,
        "sector_code": "BANKS",
        "from_sector": "BANKS",
        "to_sector": "BANKS",
        "horizon_sessions": 1,
        "predicted_return": score,
        "transition_probability": 1.0,
        "sample_count": 1,
        "model_version": MODEL_VERSION,
    }


def test_decisions_cover_buy_sell_and_hold_boundaries() -> None:
    decisions = _build_decisions(
        pd.DataFrame([prediction(0.02), prediction(-0.02), prediction(0.01)])
    )
    assert decisions["action"].tolist() == ["BUY", "SELL", "HOLD"]
    assert [_direction(value) for value in (0.02, -0.02, 0.01)] == [
        "UP",
        "DOWN",
        "FLAT",
    ]


def test_legacy_prediction_schema_and_incomplete_horizon_are_compatible() -> None:
    legacy = pd.DataFrame(
        [
            {
                "evaluation_date": "2026-01-08",
                "sector_level": 2,
                "timeframe": "1d",
                "strategy": "SECTOR_TRANSITION_V1",
                "sector_code": "BANKS",
                "horizon": 2,
                "predicted_return": 0.02,
            }
        ]
    )
    normalized = _normalize_prediction_schema(legacy)
    assert normalized.loc[0, "from_sector"] == "BANKS"
    assert normalized.loc[0, "to_sector"] == "BANKS"
    assert normalized.loc[0, "horizon_sessions"] == 2

    outcomes = evaluate_sector_transition_outcomes(
        legacy,
        [history()],
        evaluation_date=date(2026, 1, 8),
        sector_codes=["BANKS"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[2],
    )
    assert outcomes.empty


def test_empty_predictions_return_stable_outcome_schema() -> None:
    outcomes = evaluate_sector_transition_outcomes(
        pd.DataFrame(),
        [history()],
        evaluation_date=date(2026, 1, 8),
        sector_codes=["BANKS"],
        sector_level=2,
        timeframe="1d",
        strategy="SECTOR_TRANSITION_V1",
        prediction_horizons=[1],
    )
    assert "direction_correct" in outcomes.columns


@pytest.mark.parametrize("frames", [[], [pd.DataFrame()]])
def test_combine_requires_nonempty_frames(frames: list[pd.DataFrame]) -> None:
    with pytest.raises(ValueError, match="at least one"):
        _combine_sector_frames(frames, sector_codes=["BANKS"])


def test_combine_rejects_missing_columns_and_state_before_date() -> None:
    with pytest.raises(ValueError, match="missing columns"):
        _combine_sector_frames(
            [pd.DataFrame({"date": ["2026-01-08"], "sector_code": ["BANKS"]})],
            sector_codes=["BANKS"],
        )
    with pytest.raises(ValueError, match="No sector state"):
        _state_at_or_before(history(), pd.Timestamp("2026-01-01"))


def test_focus_short_history_and_nullable_value_boundaries() -> None:
    assert _resolve_focus(None, ["BANKS"]) == ["BANKS"]
    with pytest.raises(ValueError, match="within sectorCodes"):
        _resolve_focus(["TECH"], ["BANKS"])
    assert _historical_forward_return(history().iloc[:1], "BANKS", 1) == 0.0
    assert _to_float(float("nan")) is None
    assert _to_float("1.5") == 1.5
