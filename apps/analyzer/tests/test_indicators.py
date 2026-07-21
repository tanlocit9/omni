from __future__ import annotations

from unittest.mock import AsyncMock

import pandas as pd
import pytest

from app.calculations.indicators import calculate_supported_indicators
from app.indicators.handler import IndicatorJobHandler
from app.indicators.kafka import IndicatorKafkaService
from app.indicators.messages import IndicatorJobMessage
from app.settings import AppSettings
from py_common.config import StockDataPaths


def _job_payload(**overrides):
    payload = {
        "jobDefinitionId": "job-definition-id",
        "executionId": "execution-id",
        "parentExecutionId": "parent-execution-id",
        "source": "ANALYZER",
        "symbolKey": "HOSE-HPG",
        "timeframe": "1d",
        "indicators": ["MA20", "MA50", "RSI14", "MACD"],
        "metadata": {"source": "test"},
    }
    payload.update(overrides)
    return payload


def _eod_frame(rows: int = 60) -> pd.DataFrame:
    dates = pd.date_range("2026-01-01", periods=rows, freq="D")
    return pd.DataFrame(
        {
            "date": dates,
            "open": range(1, rows + 1),
            "high": range(2, rows + 2),
            "low": range(0, rows),
            "close": range(1, rows + 1),
            "volume": range(100, 100 + rows),
        }
    )


def test_indicator_job_message_validates_complete_supported_set():
    message = IndicatorJobMessage.model_validate(_job_payload())

    assert message.timeframe == "1d"
    assert message.indicators == ["MA20", "MA50", "RSI14", "MACD"]
    assert message.parse_symbol_key() == ("HOSE", "HPG")


@pytest.mark.parametrize(
    "overrides",
    [
        {"timeframe": "1h"},
        {"indicators": ["MA20"]},
    ],
)
def test_indicator_job_message_rejects_unsupported_contracts(overrides):
    with pytest.raises(ValueError):
        IndicatorJobMessage.model_validate(_job_payload(**overrides))


def test_indicator_job_message_rejects_malformed_symbol_key():
    message = IndicatorJobMessage.model_validate(_job_payload(symbolKey="HPG"))

    with pytest.raises(ValueError, match="symbolKey"):
        message.parse_symbol_key()


def test_calculate_supported_indicators_returns_full_series_columns():
    result = calculate_supported_indicators(_eod_frame())

    assert list(result.columns) == [
        "date",
        "ma20",
        "ma50",
        "rsi14",
        "macd",
        "macd_signal",
        "macd_hist",
    ]
    assert len(result) == 60
    assert result["ma20"].iloc[18] != result["ma20"].iloc[18]
    assert result["ma20"].iloc[19] == pytest.approx(10.5)
    assert result["ma50"].iloc[49] == pytest.approx(25.5)


@pytest.mark.anyio
async def test_indicator_handler_reads_eod_and_writes_indicator_path():
    settings = AppSettings(
        indicator_kafka_enabled=False,
        stock_data_paths=StockDataPaths(
            symbols_base="symbols/",
            symbols_pattern="{exchange}.parquet",
            eod_base="eod/",
            eod_pattern="{exchange}/{code}.parquet",
            indicators_base="indicators/",
            indicators_pattern="{timeframe}/{exchange}/{code}.parquet",
        ),
    )
    parquet_storage = AsyncMock()
    parquet_storage.read_dataframe.return_value = _eod_frame()
    handler = IndicatorJobHandler(settings, parquet_storage)

    records = await handler.handle(_job_payload())

    assert records == 60
    parquet_storage.read_dataframe.assert_awaited_once_with("eod/hose/hpg.parquet")
    written_path, written_frame = parquet_storage.write_dataframe.await_args.args
    assert written_path == "indicators/1d/hose/hpg.parquet"
    assert len(written_frame) == 60


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
    assert status.recordsProcessed == 60
    producer.send_and_wait.assert_awaited_once()
    topic, value = producer.send_and_wait.await_args.args
    assert topic == "topic-sync-job-status"
    assert b'"recordsProcessed":60' in value


@pytest.mark.anyio
async def test_indicator_kafka_service_publishes_error_status_for_invalid_json():
    settings = AppSettings(indicator_kafka_enabled=False)
    handler = AsyncMock()
    service = IndicatorKafkaService(settings, handler)
    producer = AsyncMock()
    service._producer = producer

    status = await service.process_payload("not-json")

    assert status.status == "ERROR"
    assert status.recordsProcessed == 0
    producer.send_and_wait.assert_awaited_once()
