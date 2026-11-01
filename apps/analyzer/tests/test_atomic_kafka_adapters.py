"""Atomic attribution survives adapter validation and calculation failures."""

import json
from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from py_common.messaging import JobStatus
from py_common.messaging.atomic_work import atomic_work_id

from app.indicators.kafka import IndicatorKafkaService
from app.sector_wave.kafka import (
    SectorRotationBacktestKafkaService,
    SectorWaveSectorFeatureKafkaService,
    SectorWaveSymbolFeatureKafkaService,
)
from app.settings import AppSettings
from app.signals.evaluation_kafka import SignalEvaluationKafkaService


def command(kind, unit, key, **parameters):
    day = date(2026, 10, 30)
    parameters = {"source": "ANALYZER", "timeframe": "1d", **parameters}
    target = {"SYMBOL": "symbolKey", "SECTOR": "sectorCode", "EXCHANGE": "exchange"}
    return {
        "jobDefinitionId": "definition",
        "executionId": "attempt",
        "parentExecutionId": "parent",
        "workType": unit,
        "workKey": key,
        **({target[unit]: key} if unit in target else {}),
        **parameters,
        "parameters": parameters,
        "businessDate": day.isoformat(),
        "jobType": kind,
        "atomicWorkId": atomic_work_id(kind, unit, key, day, parameters),
    }


@pytest.mark.anyio
@pytest.mark.parametrize("failure", [False, True])
@pytest.mark.parametrize(
    "service_type,method,payload",
    [
        (
            SectorWaveSymbolFeatureKafkaService,
            "handle_symbol_features",
            command("PRECOMPUTE_SYMBOL_FEATURES", "SYMBOL", "HOSE-HPG"),
        ),
        (
            SectorWaveSectorFeatureKafkaService,
            "handle_sector_features",
            command("PRECOMPUTE_SECTOR_FEATURES", "SECTOR", "BANKS", sectorLevel=2),
        ),
        (
            SectorRotationBacktestKafkaService,
            "handle_sector_rotation_backtest",
            command(
                "SECTOR_ROTATION_BACKTEST",
                "GLOBAL",
                "SECTOR_WAVE_V1",
                strategy="SECTOR_WAVE_V1",
                sectorCodes=["BANKS"],
                sectorLevel=2,
            ),
        ),
        (
            IndicatorKafkaService,
            "handle",
            command(
                "SYNC_INDICATORS",
                "SYMBOL",
                "HOSE-HPG",
                indicatorSource="ad_close",
                indicators=["MA20"],
            ),
        ),
    ],
)
async def test_calculation_status_preserves_attempt_and_frozen_identity(
    service_type, method, payload, failure
):
    handler = AsyncMock()
    getattr(handler, method).return_value = 7
    if failure:
        getattr(handler, method).side_effect = ValueError("missing business date")
    service = service_type(AppSettings(), handler)
    service.publish_status = AsyncMock()
    status = await service.process_payload(json.dumps(payload).encode())
    getattr(handler, method).assert_awaited_once_with(payload)
    service.publish_status.assert_awaited_once_with(status)
    assert status.status == (JobStatus.ERROR if failure else JobStatus.SUCCESS)
    assert status.execution_id == "attempt"
    assert status.parent_execution_id == "parent"
    assert status.meta_json["atomicWorkId"] == payload["atomicWorkId"]
    assert status.meta_json["workBusinessDate"] == payload["businessDate"]
    if not failure:
        assert status.records_processed == 7


@pytest.mark.anyio
@pytest.mark.parametrize("encoding", ["dict", "string", "bytes"])
@pytest.mark.parametrize("mode", ["success", "calculation-error", "invalid"])
async def test_evaluation_adapter_status_and_rejected_work(encoding, mode):
    payload = command(
        "EVALUATE_SIGNALS", "EXCHANGE", "HOSE", strategy="TREND_MOMENTUM_V1"
    )
    evaluator = AsyncMock()
    evaluator.evaluate.return_value = SimpleNamespace(
        records_scanned=7, metadata={"recordsUpdated": 3}
    )
    if mode == "invalid":
        payload["strategy"] = "UNKNOWN"
    elif mode == "calculation-error":
        evaluator.evaluate.side_effect = RuntimeError("read failed")
    service = SignalEvaluationKafkaService(AppSettings(), evaluator)
    service.publish_status = AsyncMock()
    wire = payload if encoding == "dict" else json.dumps(payload)
    if encoding == "bytes":
        wire = wire.encode()
    status = await service.process_payload(wire)
    assert status.status == (
        JobStatus.SUCCESS if mode == "success" else JobStatus.ERROR
    )
    assert status.execution_id == "attempt"
    assert status.meta_json["atomicWorkId"] == payload["atomicWorkId"]
    service.publish_status.assert_awaited_once_with(status)
    if mode == "invalid":
        evaluator.evaluate.assert_not_awaited()
    else:
        evaluator.evaluate.assert_awaited_once_with(payload)
    if mode == "success":
        assert status.records_processed == 7
        assert status.meta_json["recordsUpdated"] == 3


@pytest.mark.anyio
@pytest.mark.parametrize("payload", ["not-json", {}, {"executionId": ""}])
@pytest.mark.parametrize(
    "service_type", [IndicatorKafkaService, SignalEvaluationKafkaService]
)
async def test_unattributable_poison_never_publishes_invented_status(
    service_type, payload
):
    service = service_type(AppSettings(), AsyncMock())
    service.publish_status = AsyncMock()
    assert await service.process_payload(payload) is None
    service.publish_status.assert_not_awaited()
