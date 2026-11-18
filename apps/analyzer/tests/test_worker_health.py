from types import SimpleNamespace

import pytest
from fastapi import FastAPI, HTTPException

from main import health_check


@pytest.mark.anyio
@pytest.mark.parametrize("running", [True, False])
async def test_analyzer_health_checks_each_enabled_consumer(running):
    app = FastAPI()
    app.state.storage_registry = SimpleNamespace(_adapters={})
    app.state.indicator_kafka_service = SimpleNamespace(is_running=True)
    app.state.metadata_sync_kafka_service = SimpleNamespace(is_running=running)
    request = SimpleNamespace(app=app)
    if running:
        response = await health_check(request)
        assert response["status"] == "ok"
        assert all(response["workers"].values())
    else:
        with pytest.raises(HTTPException) as error:
            await health_check(request)
        assert error.value.status_code == 503
        assert error.value.detail["workers"]["metadata_sync_kafka_service"] is False


@pytest.mark.asyncio
@pytest.mark.parametrize("consumers_enabled", [False, True])
async def test_startup_wires_readiness_validator_and_shutdown_without_live_clients(
    monkeypatch, consumers_enabled
):
    from unittest.mock import AsyncMock, Mock

    from py_common.storage.output_readiness import (
        PostgresMetadataReader,
    )
    from pydantic import SecretStr

    import main
    from app.settings import AppSettings

    flags = {
        name: consumers_enabled
        for name in [
            "indicator_kafka_enabled",
            "metadata_kafka_enabled",
            "signal_kafka_enabled",
            "signal_evaluation_kafka_enabled",
            "sector_wave_symbol_features_kafka_enabled",
            "sector_wave_sector_features_kafka_enabled",
            "sector_rotation_backtest_kafka_enabled",
            "sector_transition_analyze_kafka_enabled",
            "sector_transition_outcome_kafka_enabled",
        ]
    }
    settings = AppSettings(
        **flags,
        output_readiness_mode="postgres",
        output_readiness_api_url="http://platform/readiness",
        output_readiness_api_token=SecretStr("fixture"),
        output_readiness_backfill_enabled=False,
    )
    registry = Mock()
    registry.validate_all = AsyncMock()
    registry.get_port.return_value = AsyncMock()
    monkeypatch.setattr(main, "settings", settings)
    monkeypatch.setattr(main, "create_storage_registry", Mock(return_value=registry))
    worker = SimpleNamespace(start=AsyncMock(), stop=AsyncMock())
    for name in [
        "IndicatorKafkaService",
        "MetadataSyncKafkaService",
        "SignalKafkaService",
        "SignalEvaluationKafkaService",
        "SectorWaveSymbolFeatureKafkaService",
        "SectorWaveSectorFeatureKafkaService",
        "SectorRotationBacktestKafkaService",
        "SectorTransitionAnalyzeKafkaService",
        "SectorTransitionOutcomeEvaluationKafkaService",
    ]:
        monkeypatch.setattr(main, name, Mock(return_value=worker))
    app = FastAPI()
    await main.startup_event(app)
    registry.validate_all.assert_awaited_once_with(fail_fast=True)
    reader = app.state.metadata_reader
    assert isinstance(reader, PostgresMetadataReader)
    intraday = await app.state.signal_handler._intraday_resolver.resolve(
        provider="VCI", exchange="HOSE", symbol="HPG", trading_date="2026-09-11"
    )
    assert intraday.dataset is None
    assert intraday.reason_codes == ["MISSING_POSTGRES_INTRADAY_PROOF"]
    registry.get_port.return_value.read_bytes.assert_not_awaited()
    assert worker.start.await_count == (9 if consumers_enabled else 0)
    await main.shutdown_event(app)
    assert worker.stop.await_count == (9 if consumers_enabled else 0)
