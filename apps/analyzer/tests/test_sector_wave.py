from __future__ import annotations

import io
import json

import pandas as pd
import pyarrow.parquet as pq
import pytest
from py_common.storage.parquet import ParquetCodec

from app.sector_wave.calculations import (
    SECTOR_FEATURES_SCHEMA,
    aggregate_sector_features,
    calculate_sector_rotation_backtest,
    calculate_symbol_features,
    filter_symbols_for_sector,
)
from app.sector_wave.messages import (
    SectorRotationBacktestJobMessage,
    SectorWaveSectorFeatureJobMessage,
    SectorWaveSymbolFeatureJobMessage,
)


def _eod_frame(rows: int = 30, start: float = 100.0) -> pd.DataFrame:
    dates = pd.date_range("2026-01-01", periods=rows, freq="B")
    return pd.DataFrame(
        {
            "date": dates,
            "ad_close": [start + index for index in range(rows)],
            "close": [9999.0] * rows,
            "nm_volume": [1000 + index * 10 for index in range(rows)],
            "volume": [1] * rows,
        }
    )


def test_sector_wave_symbol_message_validates_contract():
    message = SectorWaveSymbolFeatureJobMessage.model_validate(
        {
            "jobDefinitionId": "job-definition-id",
            "executionId": "execution-id",
            "parentExecutionId": "parent-execution-id",
            "source": "ANALYZER",
            "workType": "SYMBOL",
            "workKey": "HOSE-HPG",
            "symbolKey": "HOSE-HPG",
            "timeframe": "1d",
            "metadata": {},
        }
    )

    assert message.parse_symbol_key() == ("HOSE", "HPG")


@pytest.mark.parametrize(
    "model,payload",
    [
        (
            SectorWaveSymbolFeatureJobMessage,
            {
                "jobDefinitionId": "job-definition-id",
                "executionId": "execution-id",
                "source": "ANALYZER",
                "workType": "SYMBOL",
                "workKey": "HOSE-HPG",
                "symbolKey": "HPG",
                "timeframe": "1d",
            },
        ),
        (
            SectorWaveSectorFeatureJobMessage,
            {
                "jobDefinitionId": "job-definition-id",
                "executionId": "execution-id",
                "source": "ANALYZER",
                "workType": "SECTOR",
                "workKey": "BANKS",
                "sectorCode": "BANKS",
                "sectorLevel": 0,
                "timeframe": "1d",
            },
        ),
        (
            SectorRotationBacktestJobMessage,
            {
                "jobDefinitionId": "job-definition-id",
                "executionId": "execution-id",
                "source": "ANALYZER",
                "workType": "GLOBAL",
                "workKey": "SECTOR_WAVE_V1",
                "sectorCodes": [],
                "sectorLevel": 2,
                "timeframe": "1d",
                "strategy": "UNKNOWN",
            },
        ),
    ],
)
def test_sector_wave_messages_reject_invalid_contracts(model, payload):
    with pytest.raises(ValueError):
        model.model_validate(payload)


def test_calculate_symbol_features_uses_adjusted_trading_session_offsets():
    result = calculate_symbol_features(
        _eod_frame(rows=30),
        symbol_key="HOSE-HPG",
        exchange="HOSE",
        code="HPG",
    )

    assert len(result) == 30
    assert result["date"].is_monotonic_increasing
    assert result.iloc[0]["close"] == pytest.approx(100.0)
    assert result.iloc[0]["volume"] == pytest.approx(1000.0)
    assert result.iloc[1]["return_1d"] == pytest.approx(1 / 100)
    assert result.iloc[5]["return_5d"] == pytest.approx(5 / 100)
    assert result.iloc[19]["ma20"] == pytest.approx(109.5)
    assert result.iloc[0]["forward_return_t5"] == pytest.approx(5 / 100)
    assert pd.isna(result.iloc[-1]["forward_return_t5"])


def test_calculate_symbol_features_requires_adjusted_close():
    frame = _eod_frame(rows=30).drop(columns=["ad_close"])

    with pytest.raises(ValueError, match="ad_close"):
        calculate_symbol_features(
            frame,
            symbol_key="HOSE-HPG",
            exchange="HOSE",
            code="HPG",
        )


def test_filter_symbols_for_sector_uses_symbols_parquet_metadata():
    symbols = pd.DataFrame(
        {
            "exchange": ["HOSE", "HOSE", "HNX"],
            "code": ["MBB", "FPT", "ACB"],
            "sectorLv2Code": ["BANKS", "TECH", "BANKS"],
        }
    )

    members = filter_symbols_for_sector(symbols, sector_code="BANKS", sector_level=2)

    assert [member.symbol_key for member in members] == ["HNX-ACB", "HOSE-MBB"]


def test_aggregate_sector_features_builds_contributors_and_equal_weight_index():
    mbb = calculate_symbol_features(
        _eod_frame(rows=25, start=100.0),
        symbol_key="HOSE-MBB",
        exchange="HOSE",
        code="MBB",
    )
    acb = calculate_symbol_features(
        _eod_frame(rows=25, start=200.0),
        symbol_key="HOSE-ACB",
        exchange="HOSE",
        code="ACB",
    )

    result = aggregate_sector_features(
        [mbb, acb],
        sector_code="BANKS",
        sector_level=2,
    )

    assert len(result) == 25
    assert result.iloc[0]["sector_index"] == pytest.approx(100.0)
    assert result.iloc[1]["sector_return"] == pytest.approx(((1 / 100) + (1 / 200)) / 2)
    contributors = result.iloc[1]["contributors"]
    assert [item["symbol"] for item in contributors] == ["MBB", "ACB"]
    assert contributors[0]["weight"] == pytest.approx(0.5)
    assert json.loads(result.iloc[1]["contributors_json"]) == contributors
    assert result.iloc[1]["coverage_ratio"] == pytest.approx(1.0)


def test_sector_feature_parquet_schema_preserves_contributor_field_names():
    mbb = calculate_symbol_features(
        _eod_frame(rows=25, start=100.0),
        symbol_key="HOSE-MBB",
        exchange="HOSE",
        code="MBB",
    )
    acb = calculate_symbol_features(
        _eod_frame(rows=25, start=200.0),
        symbol_key="HOSE-ACB",
        exchange="HOSE",
        code="ACB",
    )
    result = aggregate_sector_features(
        [mbb, acb],
        sector_code="BANKS",
        sector_level=2,
    )

    encoded = ParquetCodec.encode(result, schema=SECTOR_FEATURES_SCHEMA)
    parquet_schema = pq.read_schema(io.BytesIO(encoded))
    contributor_type = parquet_schema.field("contributors").type.value_type

    assert [field.name for field in contributor_type] == [
        "symbol",
        "weight",
        "return",
        "contribution",
        "contribution_share",
        "above_ma20",
    ]
    assert str(parquet_schema.field("contributors_json").type) == "string"


def test_sector_rotation_backtest_ranks_daily_winner_and_forward_returns():
    dates = pd.date_range("2026-01-01", periods=8, freq="B")
    banks = pd.DataFrame(
        {
            "date": dates,
            "sector_code": "BANKS",
            "sector_level": 2,
            "sector_index": range(100, 108),
            "sector_return": [0.01] * 8,
            "ma20": [99.0] * 8,
            "relative_strength": [0.02] * 8,
            "volume_ratio": [1.0] * 8,
            "breadth_above_ma20": [1.0] * 8,
            "coverage_ratio": [1.0] * 8,
            "top_3_contribution_share": [1.0] * 8,
            "contributors": [[] for _ in range(8)],
            "contributors_json": ["[]"] * 8,
        }
    )
    tech = banks.copy()
    tech["sector_code"] = "TECH"
    tech["relative_strength"] = [0.01] * 8

    result = calculate_sector_rotation_backtest(
        [banks, tech],
        strategy="SECTOR_WAVE_V1",
        sector_level=2,
    )

    assert len(result) == 8
    assert set(result["sector_code"]) == {"BANKS"}
    assert result.iloc[0]["rank"] == 1
    assert result.iloc[0]["sector_wave"] == "LEADING"
    assert result.iloc[0]["forward_return_t5"] == pytest.approx((1.01**5) - 1)
