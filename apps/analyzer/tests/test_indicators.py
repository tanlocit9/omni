from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pandas as pd
import pytest
from fastapi.testclient import TestClient
from py_common.config import SchedulerSettings, StockDataPaths
from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.manifest import ColumnMetadata, DatasetManifest
from py_common.storage.parquet import ParquetWriteResult

from app.calculations import indicators as indicator_calculations
from app.calculations.indicators import calculate_supported_indicators
from app.indicators.handler import IndicatorJobHandler
from app.indicators.kafka import IndicatorKafkaService
from app.indicators.messages import IndicatorJobMessage
from app.settings import AppSettings
from main import app


def _job_payload(**overrides):
    payload = {
        "jobDefinitionId": "job-definition-id",
        "executionId": "execution-id",
        "parentExecutionId": "parent-execution-id",
        "source": "ANALYZER",
        "workType": "SYMBOL",
        "workKey": "HOSE-HPG",
        "indicatorSource": "ad_close",
        "symbolKey": "HOSE-HPG",
        "timeframe": "1d",
        "indicators": ["MA20", "MA50", "RSI14", "MACD", "ICHIMOKU"],
        "metadata": {"source": "test"},
    }
    payload.update(overrides)
    return payload


def _eod_frame(rows: int = 60) -> pd.DataFrame:
    dates = pd.date_range("2026-01-01", periods=rows, freq="D")
    return pd.DataFrame(
        {
            "date": dates,
            "ad_open": range(1, rows + 1),
            "ad_high": range(2, rows + 2),
            "ad_low": range(0, rows),
            "ad_close": range(1, rows + 1),
            "nm_volume": range(100, 100 + rows),
        }
    )


def _eod_manifest(
    *,
    data_version: str = f"sha256:{'a' * 64}",
    status: str = "READY",
) -> DatasetManifest:
    return DatasetManifest(
        version=1,
        dataset="eod",
        partition={"exchange": "hose", "code": "hpg"},
        status=status,
        path="canonical/eod/hose/hpg-version.parquet",
        dataVersion=data_version,
        objectCount=1,
        totalBytes=1_024,
        rowCount=60,
        columnCount=1,
        columns=[ColumnMetadata(name="date", type="TIMESTAMP", nullable=False)],
        schemaVersion=1,
        schemaHash=f"sha256:{'b' * 64}",
        generatedAt="2026-08-22T00:00:00+00:00",
    )


def _indicator_settings() -> AppSettings:
    return AppSettings(
        indicator_kafka_enabled=False,
        stock_data_paths=StockDataPaths(
            symbols_base="symbols/",
            symbols_pattern="{exchange}.parquet",
            eod_base="eod/",
            eod_pattern="{exchange}/{code}.parquet",
            indicators_base="indicators/",
            indicators_pattern="{source}/{timeframe}/{exchange}/{code}.parquet",
        ),
    )


def _indicator_handler(
    manifest: DatasetManifest | None = None,
) -> tuple[IndicatorJobHandler, AsyncMock, AsyncMock]:
    parquet_storage = AsyncMock()
    parquet_storage.read_dataframe.return_value = _eod_frame()
    parquet_storage.write_dataframe.return_value = ParquetWriteResult(
        object_name="indicators/ad_close/1d/hose/hpg.parquet",
        checksum=f"sha256:{'c' * 64}",
        total_bytes=2_048,
    )
    metadata_reader = AsyncMock()
    metadata_reader.read.return_value = SimpleNamespace(
        resolve=lambda dataset, partition: manifest or _eod_manifest()
    )
    handler = IndicatorJobHandler(
        _indicator_settings(), parquet_storage, metadata_reader
    )
    return handler, parquet_storage, metadata_reader


def test_indicator_job_message_validates_supported_indicator_subset():
    message = IndicatorJobMessage.model_validate(
        _job_payload(indicators=["ma20", "macd"])
    )

    assert message.timeframe == "1d"
    assert message.indicators == ["MA20", "MACD"]
    assert message.parse_symbol_key() == ("HOSE", "HPG")


@pytest.mark.parametrize(
    "overrides",
    [
        {"timeframe": "1h"},
        {"indicators": []},
        {"indicators": ["MA20", "UNKNOWN"]},
    ],
)
def test_indicator_job_message_rejects_unsupported_contracts(overrides):
    with pytest.raises(ValueError):
        IndicatorJobMessage.model_validate(_job_payload(**overrides))


def test_indicator_job_message_rejects_malformed_symbol_key():
    message = IndicatorJobMessage.model_validate(_job_payload(symbolKey="HPG"))

    with pytest.raises(ValueError, match="symbolKey"):
        message.parse_symbol_key()


def test_calculate_supported_indicators_returns_requested_indicator_columns():
    result = calculate_supported_indicators(
        _eod_frame(),
        "ad_close",
        ["MA20", "MACD"],
        SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
    )

    assert list(result.columns) == [
        "date",
        "ma20",
        "ma20_calculated_at",
        "macd",
        "macd_signal",
        "macd_hist",
        "macd_calculated_at",
    ]
    assert len(result) == 60
    assert str(result["ma20_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"
    assert str(result["macd_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"
    assert result["ma20"].iloc[18] != result["ma20"].iloc[18]
    assert result["ma20"].iloc[19] == pytest.approx(10.5)
    assert "ma50" not in result.columns
    assert "rsi14" not in result.columns


def test_calculate_supported_indicators_returns_full_series_columns():
    result = calculate_supported_indicators(
        _eod_frame(),
        "ad_close",
        ["MA20", "MA50", "RSI14", "MACD", "ICHIMOKU"],
        SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
    )

    assert list(result.columns) == [
        "date",
        "ma20",
        "ma20_calculated_at",
        "ma50",
        "ma50_calculated_at",
        "rsi14",
        "rsi14_calculated_at",
        "macd",
        "macd_signal",
        "macd_hist",
        "macd_calculated_at",
        "ichimoku_tenkan",
        "ichimoku_kijun",
        "ichimoku_span_a",
        "ichimoku_span_b",
        "ichimoku_chikou",
        "ichimoku_calculated_at",
    ]
    assert len(result) == 60
    assert str(result["ma20_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"
    assert str(result["ma50_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"
    assert str(result["rsi14_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"
    assert str(result["macd_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"
    assert str(result["ichimoku_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"
    assert result["ma20_calculated_at"].equals(result["ma50_calculated_at"])
    assert result["ma20_calculated_at"].equals(result["rsi14_calculated_at"])
    assert result["ma20_calculated_at"].equals(result["macd_calculated_at"])
    assert result["ma20_calculated_at"].equals(result["ichimoku_calculated_at"])
    assert result["ma20"].iloc[18] != result["ma20"].iloc[18]
    assert result["ma20"].iloc[19] == pytest.approx(10.5)
    assert result["ma50"].iloc[49] == pytest.approx(25.5)
    assert result["ichimoku_tenkan"].iloc[7] != result["ichimoku_tenkan"].iloc[7]
    assert result["ichimoku_tenkan"].iloc[8] == pytest.approx(5.0)


@pytest.mark.anyio
async def test_indicator_handler_resolves_global_metadata_and_persists_lineage():
    eod_version = f"sha256:{'d' * 64}"
    handler, parquet_storage, metadata_reader = _indicator_handler(
        _eod_manifest(data_version=eod_version)
    )

    records = await handler.handle(_job_payload())

    assert records == 60
    metadata_reader.read.assert_awaited_once()
    parquet_storage.read_dataframe.assert_awaited_once_with(
        "canonical/eod/hose/hpg-version.parquet"
    )
    written_path, written_frame = parquet_storage.write_dataframe.await_args.args
    assert written_path == "indicators/ad_close/1d/hose/hpg.parquet"
    assert len(written_frame) == 60
    assert set(written_frame["eod_data_version"]) == {eod_version}


@pytest.mark.anyio
async def test_indicator_handler_missing_global_partition_prevents_write():
    handler, parquet_storage, metadata_reader = _indicator_handler()
    metadata_reader.read.return_value = SimpleNamespace(resolve=lambda *_args: None)

    with pytest.raises(ManifestInvalidError, match="must be READY"):
        await handler.handle(_job_payload())

    parquet_storage.read_dataframe.assert_not_awaited()
    parquet_storage.write_dataframe.assert_not_awaited()


@pytest.mark.anyio
async def test_indicator_handler_metadata_read_failure_prevents_write():
    handler, parquet_storage, metadata_reader = _indicator_handler()
    metadata_reader.read.side_effect = ManifestInvalidError(
        "Invalid global metadata JSON"
    )

    with pytest.raises(ManifestInvalidError, match="Invalid global metadata JSON"):
        await handler.handle(_job_payload())

    parquet_storage.write_dataframe.assert_not_awaited()


@pytest.mark.anyio
async def test_indicator_handler_changed_eod_version_changes_persisted_lineage():
    versions = []
    for character in ("d", "e"):
        handler, parquet_storage, _ = _indicator_handler(
            _eod_manifest(data_version=f"sha256:{character * 64}")
        )
        await handler.handle(_job_payload())
        versions.append(
            parquet_storage.write_dataframe.await_args.args[1]["eod_data_version"].iloc[
                0
            ]
        )

    assert versions[0] != versions[1]


def test_calculate_ichimoku_keeps_shape_and_omni_schema_for_short_series():
    result = calculate_supported_indicators(
        _eod_frame(rows=40),
        "ad_close",
        ["ICHIMOKU"],
        SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
    )

    assert list(result.columns) == [
        "date",
        "ichimoku_tenkan",
        "ichimoku_kijun",
        "ichimoku_span_a",
        "ichimoku_span_b",
        "ichimoku_chikou",
        "ichimoku_calculated_at",
    ]
    assert len(result) == 40
    assert result["ichimoku_span_b"].isna().all()
    assert str(result["ichimoku_calculated_at"].dt.tz) == "Asia/Ho_Chi_Minh"


def test_calculate_ichimoku_sorts_and_deduplicates_by_date():
    frame = pd.concat([_eod_frame(rows=60).iloc[::-1], _eod_frame(rows=1)])

    result = calculate_supported_indicators(
        frame,
        "ad_close",
        ["ICHIMOKU"],
        SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
    )

    assert len(result) == 60
    assert result["date"].is_monotonic_increasing
    assert result["date"].is_unique


def test_calculate_ichimoku_requires_ohlc_columns():
    frame = _eod_frame().drop(columns=["ad_high"])

    with pytest.raises(ValueError, match="ad_high"):
        calculate_supported_indicators(
            frame,
            "ad_close",
            ["ICHIMOKU"],
            SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
        )


def test_calculate_ichimoku_shift_matches_pandas_ta_visible_output():
    result = calculate_supported_indicators(
        _eod_frame(rows=80),
        "ad_close",
        ["ICHIMOKU"],
        SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
    )

    assert len(result) == 80
    assert result["ichimoku_span_a"].iloc[77] == pytest.approx(43.75)
    assert result["ichimoku_span_b"].iloc[77] == pytest.approx(26.5)
    assert result["ichimoku_chikou"].iloc[0] == pytest.approx(27.0)
    assert result["ichimoku_chikou"].iloc[53] == pytest.approx(80.0)
    assert result["ichimoku_chikou"].iloc[54:].isna().all()


def test_calculate_supported_indicators_requires_scheduler():
    with pytest.raises(ValueError, match="Scheduler settings are required"):
        calculate_supported_indicators(
            _eod_frame(),
            "ad_close",
            ["MA20"],
        )


def test_calculate_ichimoku_validates_library_output_columns(monkeypatch):
    def fake_ichimoku(*args, **kwargs):
        return pd.DataFrame({"ISA_9": [1.0] * 60}), pd.DataFrame()

    monkeypatch.setattr(indicator_calculations.ta, "ichimoku", fake_ichimoku)

    with pytest.raises(ValueError) as exc_info:
        calculate_supported_indicators(
            _eod_frame(),
            "ad_close",
            ["ICHIMOKU"],
            SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
        )

    message = str(exc_info.value)
    for column in ["ICS_26", "IKS_26", "ISB_26", "ITS_9"]:
        assert column in message


def test_calculate_ichimoku_ignores_future_cloud(monkeypatch):
    visible = pd.DataFrame(
        {
            "ISA_9": [1.0] * 60,
            "ISB_26": [2.0] * 60,
            "ITS_9": [3.0] * 60,
            "IKS_26": [4.0] * 60,
            "ICS_26": [5.0] * 60,
        }
    )
    future_cloud = pd.DataFrame({"ISA_9": [999.0] * 26, "ISB_26": [999.0] * 26})

    def fake_ichimoku(*args, **kwargs):
        return visible, future_cloud

    monkeypatch.setattr(indicator_calculations.ta, "ichimoku", fake_ichimoku)

    result = calculate_supported_indicators(
        _eod_frame(),
        "ad_close",
        ["ICHIMOKU"],
        SchedulerSettings(zone="Asia/Ho_Chi_Minh"),
    )

    assert len(result) == 60
    assert result["ichimoku_span_a"].eq(1.0).all()
    assert result["ichimoku_span_b"].eq(2.0).all()
    assert 999.0 not in result["ichimoku_span_a"].to_list()
    assert 999.0 not in result["ichimoku_span_b"].to_list()


def test_sync_indicators_api_processes_payload_directly():
    handler = AsyncMock()
    handler.handle.return_value = 60
    client = TestClient(app, raise_server_exceptions=False)
    client.app.state.indicator_handler = handler

    response = client.post("/v1/indicators/sync", json=_job_payload())

    assert response.status_code == 200
    assert response.json() == {
        "accepted": True,
        "symbolKey": "HOSE-HPG",
        "indicatorSource": "ad_close",
        "timeframe": "1d",
        "recordsProcessed": 60,
    }
    handler.handle.assert_awaited_once_with(_job_payload())


def test_sync_indicators_api_rejects_invalid_payload():
    handler = AsyncMock()
    client = TestClient(app, raise_server_exceptions=False)
    client.app.state.indicator_handler = handler

    response = client.post(
        "/v1/indicators/sync",
        json=_job_payload(indicators=["UNKNOWN"]),
    )

    assert response.status_code == 422
    handler.handle.assert_not_awaited()


@pytest.mark.anyio
async def test_indicator_kafka_service_publishes_success_status():
    settings = AppSettings(indicator_kafka_enabled=False)
    handler = AsyncMock()
    handler.handle.return_value = 60
    service = IndicatorKafkaService(settings, handler)
    producer = AsyncMock()
    service._producer = producer

    status = await service.process_payload(_job_payload())

    assert status.status == "SUCCESS"
    assert status.records_processed == 60
    producer.send_and_wait.assert_awaited_once()
    topic, value = producer.send_and_wait.await_args.args
    assert topic == "topic-sync-job-status"
    assert b'"recordsProcessed":60' in value


@pytest.mark.anyio
async def test_indicator_kafka_service_skips_invalid_json_without_status():
    settings = AppSettings(indicator_kafka_enabled=False)
    handler = AsyncMock()
    service = IndicatorKafkaService(settings, handler)
    producer = AsyncMock()
    service._producer = producer

    status = await service.process_payload("not-json")

    assert status is None
    producer.send_and_wait.assert_not_awaited()
