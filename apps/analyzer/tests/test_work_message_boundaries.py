"""Worker contracts reject unsupported capabilities before calculation or writes."""

import pytest

from app.sector_transition.messages import (
    SectorTransitionAnalyzeJobMessage,
    SectorTransitionOutcomeEvaluationJobMessage,
)
from app.sector_wave.messages import (
    SectorRotationBacktestJobMessage,
    SectorWaveSectorFeatureJobMessage,
    SectorWaveSymbolFeatureJobMessage,
)
from app.signals.messages import SignalEvaluationJobMessage


def base():
    return {
        "jobDefinitionId": "definition",
        "executionId": "execution",
        "source": "ANALYZER",
        "workType": "GLOBAL",
        "workKey": "SECTOR_TRANSITION_V1",
        "timeframe": "1d",
    }


@pytest.mark.parametrize(
    "model",
    [SectorTransitionAnalyzeJobMessage, SectorTransitionOutcomeEvaluationJobMessage],
)
@pytest.mark.parametrize(
    "bad",
    [
        {"strategy": "UNKNOWN"},
        {"sectorLevel": 0},
        {"sectorCodes": [" "]},
        {"focusSectorCodes": ["OUTSIDE"]},
        {"predictionHorizons": []},
        {"predictionHorizons": [0]},
    ],
)
def test_transition_request_rejects_invalid_strategy_scope_or_horizon(model, bad):
    payload = {
        **base(),
        "sectorCodes": ["BANKS"],
        "focusSectorCodes": [],
        "sectorLevel": 2,
        "predictionHorizons": [1, 5],
        "evaluationDate": "2026-10-30",
        **bad,
    }
    with pytest.raises(ValueError):
        model.model_validate(payload)


@pytest.mark.parametrize(
    "model",
    [SectorTransitionAnalyzeJobMessage, SectorTransitionOutcomeEvaluationJobMessage],
)
def test_transition_request_freezes_normalized_scope_and_offsets(model):
    payload = {
        **base(),
        "sectorCodes": [" banks ", "TECH", "BANKS", ""],
        "focusSectorCodes": [" tech ", "TECH", ""],
        "sectorLevel": 2,
        "predictionHorizons": [5, 1, 5],
        "evaluationDate": "2026-10-30",
    }
    parsed = model.model_validate(payload)
    assert parsed.sector_codes == ["BANKS", "TECH"]
    assert parsed.focus_sector_codes == ["TECH"]
    assert parsed.prediction_horizons == [1, 5]
    assert payload["sectorCodes"] == [" banks ", "TECH", "BANKS", ""]


@pytest.mark.parametrize(
    "bad", [{"sectorLevel": 0}, {"sectorCodes": [" "]}, {"strategy": "UNKNOWN"}]
)
def test_backtest_request_rejects_invalid_capability(bad):
    with pytest.raises(ValueError):
        SectorRotationBacktestJobMessage.model_validate(
            {
                **base(),
                "workKey": "SECTOR_WAVE_V1",
                "sectorCodes": ["BANKS"],
                "sectorLevel": 2,
                "strategy": "SECTOR_WAVE_V1",
                **bad,
            }
        )


@pytest.mark.parametrize("symbol", ["-HPG", "HOSE-"])
def test_symbol_request_rejects_missing_exchange_or_code(symbol):
    with pytest.raises(ValueError):
        SectorWaveSymbolFeatureJobMessage.model_validate(
            {**base(), "workType": "SYMBOL", "workKey": symbol, "symbolKey": symbol}
        )


def test_sector_request_rejects_empty_scope():
    with pytest.raises(ValueError):
        SectorWaveSectorFeatureJobMessage.model_validate(
            {
                **base(),
                "workType": "SECTOR",
                "workKey": "BANKS",
                "sectorCode": " ",
                "sectorLevel": 2,
            }
        )


@pytest.mark.parametrize("bad", [{"exchange": " "}, {"strategy": "UNKNOWN"}])
def test_signal_evaluation_rejects_invalid_exchange_or_strategy(bad):
    with pytest.raises(ValueError):
        SignalEvaluationJobMessage.model_validate(
            {
                **base(),
                "workType": "EXCHANGE",
                "workKey": "HOSE",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                **bad,
            }
        )
