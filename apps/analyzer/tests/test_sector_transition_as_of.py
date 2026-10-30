from datetime import date
from unittest.mock import AsyncMock, patch

import pandas as pd
import pytest
from py_common.messaging.atomic_work import atomic_work_id

from app.sector_transition.calculations import (
    TransitionAnalysisResult, evaluate_sector_transition_outcomes,
)
from app.sector_transition.handler import OutcomeEvaluationResult, SectorTransitionJobHandler
from app.sector_transition.kafka import SectorTransitionOutcomeEvaluationKafkaService
from app.sector_transition.records import TRANSITION_KEY, append_immutable_rows
from app.settings import AppSettings

DAYS = pd.bdate_range("2026-10-01", periods=14)
AS_OF = DAYS[5].date()
STRATEGY = "SECTOR_TRANSITION_V1"


def prediction(origin=0, horizon=1, value=0.02):
    return {
        "evaluation_date": DAYS[origin].date(), "target_date": None,
        "strategy": STRATEGY, "timeframe": "1d", "sector_level": 2,
        "from_sector": "BANKS", "to_sector": "TECH", "horizon_sessions": horizon,
        "predicted_return": value, "model_version": "v1",
    }


def features(sector):
    return pd.DataFrame({
        "date": DAYS, "sector_code": sector,
        "sector_return": [0.01 if i <= 5 else 0.99 for i in range(len(DAYS))],
        "relative_strength": 0.02,
    })


def options():
    return dict(as_of_date=AS_OF, sector_codes=["BANKS", "TECH"], sector_level=2,
                timeframe="1d", strategy=STRATEGY, prediction_horizons=[1, 5, 10])


def command():
    parameters = dict(source="ANALYZER", sectorCodes=["BANKS", "TECH"],
                      focusSectorCodes=["BANKS", "TECH"], sectorLevel=2,
                      timeframe="1d", strategy=STRATEGY, predictionHorizons=[1, 5, 10])
    kind = "SECTOR_TRANSITION_EVALUATE_OUTCOMES"
    return {
        "jobDefinitionId": "job", "executionId": "execution", "source": "ANALYZER",
        "workType": "GLOBAL", "workKey": STRATEGY, "jobType": kind,
        "businessDate": AS_OF.isoformat(), "evaluationDate": AS_OF.isoformat(),
        **{key: value for key, value in parameters.items() if key != "source"},
        "parameters": parameters,
        "atomicWorkId": atomic_work_id(kind, "GLOBAL", STRATEGY, AS_OF, parameters),
    }


class MemoryStorage:
    def __init__(self):
        self.frames = {}
        self.writes = []

    async def read_optional_dataframe(self, path):
        frame = self.frames.get(path)
        return None if frame is None else frame.copy(deep=True)

    async def write_dataframe(self, path, frame):
        self.frames[path] = frame.copy(deep=True)
        self.writes.append(path)


def environment(predictions):
    settings = AppSettings()
    storage = MemoryStorage()
    paths = settings.stock_data_paths
    prediction_path = paths.sector_transition_predictions(STRATEGY, "1d", 2)
    outcome_path = paths.sector_transition_outcomes(STRATEGY, "1d", 2)
    if predictions is not None:
        storage.frames[prediction_path] = predictions.copy(deep=True)
    for sector in ["BANKS", "TECH"]:
        storage.frames[paths.sector_features("1d", 2, sector)] = features(sector)
    return settings, storage, SectorTransitionJobHandler(settings, storage), prediction_path, outcome_path


def test_scores_multiple_old_origins_only_after_horizon_matures_by_d():
    predictions = pd.DataFrame([prediction(0, 1), prediction(0, 5), prediction(0, 10),
                                prediction(1, 1), prediction(8, 1)])
    before = predictions.copy(deep=True)
    result = evaluate_sector_transition_outcomes(predictions, [features("TECH")], **options())
    assert len(result) == 3
    assert set(result["evaluation_date"]) == {DAYS[0].date(), DAYS[1].date()}
    assert result["resolved_date"].max() <= AS_OF
    assert set(result["horizon_sessions"]) == {1, 5}
    assert result.loc[result["horizon_sessions"] == 5, "realized_return"].iloc[0] == pytest.approx(1.01**5 - 1)
    assert result.loc[result["horizon_sessions"] == 5, "target_date"].iloc[0] == AS_OF
    pd.testing.assert_frame_equal(predictions, before)


@pytest.mark.parametrize("invalid", ["duplicate", "nan"])
def test_invalid_sessions_cannot_create_a_mature_outcome(invalid):
    history = features("TECH")
    if invalid == "duplicate":
        history = pd.concat([history, history.iloc[[1]]], ignore_index=True)
    else:
        history.loc[1, "sector_return"] = float("nan")
    with pytest.raises(ValueError, match="Duplicate|Non-finite"):
        evaluate_sector_transition_outcomes(pd.DataFrame([prediction()]), [history], **options())


@pytest.mark.anyio
async def test_atomic_day_scores_pending_history_then_replay_is_no_write():
    predictions = pd.DataFrame([prediction(0, 1), prediction(0, 5), prediction(0, 10)])
    settings, storage, handler, path, out = environment(predictions)
    first = await handler.handle_evaluate_outcomes(command())
    assert first == OutcomeEvaluationResult(2, "OUTCOMES_APPENDED", AS_OF)
    saved = storage.frames[out].copy(deep=True)
    assert saved["evaluation_date"].tolist() == [DAYS[0].date(), DAYS[0].date()]
    assert len(storage.writes) == 1
    # Later feature corrections must not revise already scored historical outcomes.
    storage.frames[settings.stock_data_paths.sector_features("1d", 2, "TECH")]["sector_return"] = 0.5
    repeat = await handler.handle_evaluate_outcomes(command())
    assert repeat == OutcomeEvaluationResult(0, "NO_MATURED_PREDICTIONS", AS_OF)
    pd.testing.assert_frame_equal(saved, storage.frames[out])
    pd.testing.assert_frame_equal(predictions, storage.frames[path])
    assert len(storage.writes) == 1


@pytest.mark.anyio
@pytest.mark.parametrize("case,reason", [
    ("missing", "NO_PREDICTIONS"), ("immature", "NO_MATURED_PREDICTIONS"),
    ("future", "NO_ELIGIBLE_PREDICTIONS"), ("done", "ALREADY_EVALUATED"),
])
async def test_zero_output_is_success_with_reason_and_atomic_attribution(case, reason):
    row = prediction(8 if case == "future" else 0, 10 if case == "immature" else 1)
    settings, storage, handler, _, out = environment(None if case == "missing" else pd.DataFrame([row]))
    if case == "done":
        storage.frames[out] = evaluate_sector_transition_outcomes(pd.DataFrame([row]), [features("TECH")], **options())
    service = SectorTransitionOutcomeEvaluationKafkaService(settings, handler)
    service.publish_status = AsyncMock()
    status = await service.process_payload(command())
    assert status.status.value == "SUCCESS"
    assert status.records_processed == 0
    assert status.meta_json["resultReason"] == reason
    assert status.meta_json["asOfDate"] == AS_OF.isoformat()
    assert status.meta_json["evaluationDateRole"] == "AS_OF_DATE"
    assert status.meta_json["atomicWorkId"] == command()["atomicWorkId"]
    assert not storage.writes
    service.publish_status.assert_awaited_once()


@pytest.mark.anyio
async def test_newly_mature_horizon_appends_without_replacing_existing_outcome():
    predictions = pd.DataFrame([prediction(0, 1), prediction(0, 5)])
    _, storage, handler, _, out = environment(predictions)
    saved = evaluate_sector_transition_outcomes(predictions.iloc[[0]], [features("TECH")], **options())
    storage.frames[out] = saved.copy(deep=True)
    result = await handler.handle_evaluate_outcomes(command())
    assert result.records_processed == 1
    assert len(storage.frames[out]) == 2
    pd.testing.assert_frame_equal(storage.frames[out].iloc[[0]].reset_index(drop=True), saved.reset_index(drop=True))


@pytest.mark.anyio
async def test_analysis_replay_preserves_predictions_and_conflict_writes_nothing():
    _, storage, handler, prediction_path, _ = environment(None)
    frame = pd.DataFrame([prediction()])
    analysis = TransitionAnalysisResult(frame, frame.copy(), frame.copy())
    raw = command()
    raw["evaluationDate"] = DAYS[0].date().isoformat()
    for key in ["jobType", "parameters", "atomicWorkId", "businessDate"]:
        raw.pop(key)
    with patch("app.sector_transition.handler.calculate_sector_transition_analysis", return_value=analysis):
        await handler.handle_analyze(raw)
        writes = len(storage.writes)
        assert await handler.handle_analyze(raw) == 0
        assert len(storage.writes) == writes
    conflicting = frame.copy()
    conflicting["predicted_return"] = 0.9
    with patch("app.sector_transition.handler.calculate_sector_transition_analysis",
               return_value=TransitionAnalysisResult(conflicting, frame, frame)):
        with pytest.raises(ValueError, match="Immutable"):
            await handler.handle_analyze(raw)
    assert len(storage.writes) == writes
    assert storage.frames[prediction_path]["predicted_return"].tolist() == frame["predicted_return"].tolist()
    assert storage.frames[prediction_path]["evaluation_date"].tolist() == frame["evaluation_date"].tolist()


def test_merge_normalizes_parquet_dates_without_replacing_existing_values():
    first = pd.DataFrame([prediction()])
    saved = first.copy()
    saved["evaluation_date"] = pd.to_datetime(saved["evaluation_date"])
    merged, inserted = append_immutable_rows(saved, first, TRANSITION_KEY)
    assert inserted == 0
    assert merged["evaluation_date"].iloc[0] == DAYS[0].date()
    with pytest.raises(ValueError, match="Duplicate"):
        append_immutable_rows(None, pd.concat([first, first]), TRANSITION_KEY)


def test_missing_sector_session_does_not_shift_the_realized_horizon():
    history = features("TECH").drop(index=2)
    with pytest.raises(ValueError, match="Non-finite"):
        evaluate_sector_transition_outcomes(pd.DataFrame([prediction(0, 5)]),
                                           [history, features("BANKS")], **options())


@pytest.mark.anyio
async def test_bad_realized_inputs_report_error_instead_of_empty_success():
    settings, storage, handler, _, out = environment(pd.DataFrame([prediction()]))
    path = settings.stock_data_paths.sector_features("1d", 2, "TECH")
    storage.frames[path].loc[1, "sector_return"] = float("nan")
    service = SectorTransitionOutcomeEvaluationKafkaService(settings, handler)
    service.publish_status = AsyncMock()
    status = await service.process_payload(command())
    assert status.status.value == "ERROR"
    assert status.meta_json["asOfDate"] == AS_OF.isoformat()
    assert "Non-finite" in status.error_message
    assert out not in storage.frames
    assert not storage.writes


def test_truncated_origin_history_does_not_score_from_a_later_start():
    with pytest.raises(ValueError, match="origin"):
        evaluate_sector_transition_outcomes(pd.DataFrame([prediction()]),
                                           [features("TECH").iloc[1:]], **options())


@pytest.mark.anyio
async def test_late_analysis_conflict_does_not_publish_other_prepared_outputs():
    settings, storage, handler, prediction_path, _ = environment(None)
    frame = pd.DataFrame([prediction()])
    conflicting = frame.copy()
    conflicting["predicted_return"] = 0.9
    probability_path = settings.stock_data_paths.sector_transition_probabilities(STRATEGY, "1d", 2)
    storage.frames[probability_path] = conflicting
    raw = command()
    for key in ["jobType", "parameters", "atomicWorkId", "businessDate"]:
        raw.pop(key)
    with patch("app.sector_transition.handler.calculate_sector_transition_analysis",
               return_value=TransitionAnalysisResult(frame, frame, frame)):
        with pytest.raises(ValueError, match="Immutable"):
            await handler.handle_analyze(raw)
    assert prediction_path not in storage.frames
    assert not storage.writes
