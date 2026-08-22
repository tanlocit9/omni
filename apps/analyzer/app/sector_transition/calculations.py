from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any

import pandas as pd

DEFAULT_TRANSITION_SCORE_THRESHOLD = 0.01
MODEL_VERSION = "SECTOR_TRANSITION_V1_GENERIC_UNIVERSE"


@dataclass(frozen=True)
class TransitionAnalysisResult:
    predictions: pd.DataFrame
    decisions: pd.DataFrame
    probabilities: pd.DataFrame


def calculate_sector_transition_analysis(
    sector_frames: list[pd.DataFrame],
    *,
    evaluation_date: date,
    sector_codes: list[str],
    focus_sector_codes: list[str] | None = None,
    sector_level: int,
    timeframe: str,
    strategy: str,
    prediction_horizons: list[int],
) -> TransitionAnalysisResult:
    """Calculate full-universe Sector Transition matrix and focused outputs."""
    universe = _normalize_codes(sector_codes)
    focus = _resolve_focus(focus_sector_codes, universe)
    history = _combine_sector_frames(sector_frames, sector_codes=universe)
    evaluation_ts = pd.Timestamp(evaluation_date)
    state_at_t = _state_at_or_before(history, evaluation_ts)
    _require_all_sector_states(state_at_t, universe, evaluation_date)

    historical_training_history = _historical_training_window(history, evaluation_ts)

    probabilities = _build_probability_rows(
        historical_training_history,
        evaluation_date=evaluation_date,
        sector_level=sector_level,
        timeframe=timeframe,
        strategy=strategy,
        prediction_horizons=prediction_horizons,
        sector_codes=universe,
    )
    predictions = _build_predictions(
        historical_training_history,
        state_at_t=state_at_t,
        probabilities=probabilities,
        evaluation_date=evaluation_date,
        sector_level=sector_level,
        timeframe=timeframe,
        strategy=strategy,
        prediction_horizons=prediction_horizons,
        focus_sector_codes=focus,
    )
    decisions = _build_decisions(predictions)
    return TransitionAnalysisResult(
        predictions=predictions,
        decisions=decisions,
        probabilities=probabilities,
    )


def evaluate_sector_transition_outcomes(
    predictions: pd.DataFrame,
    sector_frames: list[pd.DataFrame],
    *,
    evaluation_date: date,
    sector_codes: list[str],
    sector_level: int,
    timeframe: str,
    strategy: str,
    prediction_horizons: list[int],
) -> pd.DataFrame:
    """Attach realized outcomes to prior focused predictions without rewriting them."""
    if predictions is None or predictions.empty:
        return _empty_outcomes_frame()

    universe = _normalize_codes(sector_codes)
    history = _combine_sector_frames(sector_frames, sector_codes=universe)
    evaluation_ts = pd.Timestamp(evaluation_date)
    candidate_predictions = predictions.copy(deep=True)
    candidate_predictions = _normalize_prediction_schema(candidate_predictions)
    candidate_predictions["evaluation_date"] = pd.to_datetime(
        candidate_predictions["evaluation_date"]
    )
    candidate_predictions = candidate_predictions[
        (candidate_predictions["evaluation_date"] == evaluation_ts)
        & (candidate_predictions["sector_level"] == sector_level)
        & (candidate_predictions["timeframe"] == timeframe)
        & (candidate_predictions["strategy"] == strategy)
        & (candidate_predictions["horizon_sessions"].isin(prediction_horizons))
    ]

    rows: list[dict[str, Any]] = []
    for row in candidate_predictions.to_dict("records"):
        to_sector = str(row["to_sector"])
        horizon = int(row["horizon_sessions"])
        sector_history = history[history["sector_code"] == to_sector].sort_values(
            "date"
        )
        future = sector_history[sector_history["date"] > evaluation_ts].head(horizon)
        if len(future) < horizon:
            continue
        realized_return = float(
            (1.0 + future["sector_return"].fillna(0.0)).prod() - 1.0
        )
        predicted_return = float(row["predicted_return"])
        predicted_direction = _direction(predicted_return)
        realized_direction = _direction(realized_return)
        rows.append(
            {
                "evaluation_date": evaluation_date,
                "resolved_date": future.iloc[-1]["date"].date(),
                "target_date": row.get("target_date", future.iloc[-1]["date"].date()),
                "strategy": strategy,
                "timeframe": timeframe,
                "sector_level": sector_level,
                "sector_code": to_sector,
                "from_sector": str(row["from_sector"]),
                "to_sector": to_sector,
                "horizon_sessions": horizon,
                "predicted_return": predicted_return,
                "realized_return": realized_return,
                "predicted_direction": predicted_direction,
                "realized_direction": realized_direction,
                "direction_correct": predicted_direction == realized_direction,
                "model_version": row.get("model_version", MODEL_VERSION),
            }
        )
    return pd.DataFrame(rows) if rows else _empty_outcomes_frame()


def _combine_sector_frames(
    sector_frames: list[pd.DataFrame],
    *,
    sector_codes: list[str],
) -> pd.DataFrame:
    frames = [
        frame.copy() for frame in sector_frames if frame is not None and not frame.empty
    ]
    if not frames:
        raise ValueError("Sector Transition requires at least one sector feature frame")
    history = pd.concat(frames, ignore_index=True)
    required = {"date", "sector_code", "sector_return", "relative_strength"}
    missing = required - set(history.columns)
    if missing:
        raise ValueError(f"Sector feature frames missing columns: {sorted(missing)}")
    history["date"] = pd.to_datetime(history["date"])
    history["sector_code"] = history["sector_code"].astype(str).str.upper()
    return history[history["sector_code"].isin(sector_codes)].sort_values(
        ["date", "sector_code"]
    )


def _state_at_or_before(
    history: pd.DataFrame, evaluation_ts: pd.Timestamp
) -> pd.DataFrame:
    current = history[history["date"] <= evaluation_ts]
    if current.empty:
        raise ValueError("No sector state is available at or before evaluationDate")
    return current.sort_values("date").groupby("sector_code", as_index=False).tail(1)


def _require_all_sector_states(
    state_at_t: pd.DataFrame,
    sector_codes: list[str],
    evaluation_date: date,
) -> None:
    available = set(state_at_t["sector_code"].astype(str).str.upper())
    missing = sorted(set(sector_codes) - available)
    if missing:
        raise ValueError(
            "Sector Transition is BLOCKED: missing state at or before "
            f"{evaluation_date.isoformat()} for sectors {missing}"
        )


def _historical_training_window(
    history: pd.DataFrame,
    evaluation_ts: pd.Timestamp,
) -> pd.DataFrame:
    historical = history[history["date"] <= evaluation_ts]
    if historical.empty:
        raise ValueError(
            "No historical sector data is available at or before evaluationDate"
        )
    return historical


def _build_predictions(
    history: pd.DataFrame,
    *,
    state_at_t: pd.DataFrame,
    probabilities: pd.DataFrame,
    evaluation_date: date,
    sector_level: int,
    timeframe: str,
    strategy: str,
    prediction_horizons: list[int],
    focus_sector_codes: list[str],
) -> pd.DataFrame:
    state_by_sector = state_at_t.set_index("sector_code")
    rows: list[dict[str, Any]] = []
    for from_sector in focus_sector_codes:
        source_state = state_by_sector.loc[from_sector]
        for horizon in prediction_horizons:
            target_date = _target_trading_date(history, evaluation_date, horizon)
            horizon_probabilities = probabilities[
                (probabilities["from_sector"] == from_sector)
                & (probabilities["horizon_sessions"] == int(horizon))
            ]
            for probability_row in horizon_probabilities.to_dict("records"):
                to_sector = str(probability_row["to_sector"])
                rows.append(
                    {
                        "evaluation_date": evaluation_date,
                        "target_date": target_date,
                        "strategy": strategy,
                        "timeframe": timeframe,
                        "sector_level": sector_level,
                        "sector_code": to_sector,
                        "from_sector": from_sector,
                        "to_sector": to_sector,
                        "horizon_sessions": int(horizon),
                        "current_relative_strength": float(
                            source_state["relative_strength"]
                        ),
                        "current_sector_return": _to_float(
                            source_state.get("sector_return")
                        ),
                        "predicted_return": _historical_forward_return(
                            history, to_sector, horizon
                        ),
                        "transition_probability": float(probability_row["probability"]),
                        "sample_count": int(probability_row["sample_count"]),
                        "generated_from_date": source_state["date"].date(),
                        "model_version": MODEL_VERSION,
                    }
                )
    return pd.DataFrame(rows)


def _build_decisions(predictions: pd.DataFrame) -> pd.DataFrame:
    rows: list[dict[str, Any]] = []
    for row in predictions.to_dict("records"):
        predicted_return = float(row["predicted_return"])
        probability = float(row["transition_probability"])
        score = predicted_return * probability
        if score > DEFAULT_TRANSITION_SCORE_THRESHOLD:
            action = "BUY"
        elif score < -DEFAULT_TRANSITION_SCORE_THRESHOLD:
            action = "SELL"
        else:
            action = "HOLD"
        rows.append(
            {
                "evaluation_date": row["evaluation_date"],
                "target_date": row["target_date"],
                "strategy": row["strategy"],
                "timeframe": row["timeframe"],
                "sector_level": row["sector_level"],
                "sector_code": row["sector_code"],
                "from_sector": row["from_sector"],
                "to_sector": row["to_sector"],
                "horizon_sessions": row["horizon_sessions"],
                "action": action,
                "score": score,
                "confidence": probability,
                "sample_count": row["sample_count"],
                "reason": (
                    f"{action} {row['from_sector']} -> {row['to_sector']} over "
                    f"{row['horizon_sessions']} trading sessions from T-anchored state"
                ),
                "visibility": "PRIVATE_INTERNAL",
                "model_version": row["model_version"],
            }
        )
    return pd.DataFrame(rows)


def _build_probability_rows(
    history: pd.DataFrame,
    *,
    evaluation_date: date,
    sector_level: int,
    timeframe: str,
    strategy: str,
    prediction_horizons: list[int],
    sector_codes: list[str],
) -> pd.DataFrame:
    rows: list[dict[str, Any]] = []
    for from_sector in sector_codes:
        for horizon in prediction_horizons:
            counts = _transition_counts(history, from_sector, int(horizon))
            sample_count = int(sum(counts.values()))
            for to_sector in sector_codes:
                probability = (
                    counts.get(to_sector, 0) / sample_count if sample_count > 0 else 0.0
                )
                rows.append(
                    {
                        "evaluation_date": evaluation_date,
                        "target_date": _target_trading_date(
                            history, evaluation_date, int(horizon)
                        ),
                        "horizon_sessions": int(horizon),
                        "from_sector": from_sector,
                        "to_sector": to_sector,
                        "probability": float(probability),
                        "sample_count": sample_count,
                        "strategy": strategy,
                        "timeframe": timeframe,
                        "sector_level": sector_level,
                        "model_version": MODEL_VERSION,
                    }
                )
    return pd.DataFrame(rows)


def _transition_counts(
    history: pd.DataFrame,
    from_sector: str,
    horizon: int,
) -> dict[str, int]:
    leaders = (
        history.sort_values(["date", "relative_strength"], ascending=[True, False])
        .groupby("date", as_index=False)
        .first()[["date", "sector_code"]]
        .rename(columns={"sector_code": "leader"})
        .reset_index(drop=True)
    )
    leaders["future_leader"] = leaders["leader"].shift(-horizon)
    eligible = leaders[
        (leaders["leader"] == from_sector) & leaders["future_leader"].notna()
    ]
    return eligible["future_leader"].astype(str).value_counts().to_dict()


def _target_trading_date(
    history: pd.DataFrame, evaluation_date: date, horizon: int
) -> date | None:
    evaluation_ts = pd.Timestamp(evaluation_date)
    dates = sorted(history[history["date"] > evaluation_ts]["date"].drop_duplicates())
    if len(dates) < horizon:
        return None
    return dates[horizon - 1].date()


def _historical_forward_return(
    history: pd.DataFrame, sector_code: str, horizon: int
) -> float:
    sector_history = history[history["sector_code"] == sector_code].sort_values("date")
    if len(sector_history) <= horizon:
        return 0.0
    compounded = (
        sector_history["sector_return"]
        .fillna(0.0)
        .rolling(horizon)
        .apply(lambda x: (1 + x).prod() - 1)
    )
    values = compounded.dropna()
    return float(values.mean()) if not values.empty else 0.0


def _normalize_codes(values: list[str]) -> list[str]:
    return sorted({value.strip().upper() for value in values if value.strip()})


def _resolve_focus(values: list[str] | None, sector_codes: list[str]) -> list[str]:
    focus = _normalize_codes(values or []) or sector_codes
    invalid = sorted(set(focus) - set(sector_codes))
    if invalid:
        raise ValueError(f"focusSectorCodes must be within sectorCodes: {invalid}")
    return focus


def _normalize_prediction_schema(predictions: pd.DataFrame) -> pd.DataFrame:
    normalized = predictions.copy(deep=True)
    if "horizon_sessions" not in normalized.columns and "horizon" in normalized.columns:
        normalized["horizon_sessions"] = normalized["horizon"]
    if "from_sector" not in normalized.columns:
        normalized["from_sector"] = normalized["sector_code"]
    if "to_sector" not in normalized.columns:
        normalized["to_sector"] = normalized["sector_code"]
    return normalized


def _direction(value: float) -> str:
    if value > DEFAULT_TRANSITION_SCORE_THRESHOLD:
        return "UP"
    if value < -DEFAULT_TRANSITION_SCORE_THRESHOLD:
        return "DOWN"
    return "FLAT"


def _to_float(value: Any) -> float | None:
    if pd.isna(value):
        return None
    return float(value)


def _empty_outcomes_frame() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "evaluation_date",
            "resolved_date",
            "target_date",
            "strategy",
            "timeframe",
            "sector_level",
            "sector_code",
            "from_sector",
            "to_sector",
            "horizon_sessions",
            "predicted_return",
            "realized_return",
            "predicted_direction",
            "realized_direction",
            "direction_correct",
            "model_version",
        ]
    )
