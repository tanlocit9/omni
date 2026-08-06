from pathlib import Path

import pytest
import yaml

from py_common.config import StockDataPaths, Timeframe, validate_indicator_timeframe


@pytest.fixture()
def paths() -> StockDataPaths:
    return StockDataPaths(
        symbols_base="symbols/",
        symbols_pattern="{exchange}.parquet",
        eod_base="eod/",
        eod_pattern="{exchange}/{code}.parquet",
        indicators_base="indicators/",
        indicators_pattern="{source}/{timeframe}/{exchange}/{code}.parquet",
        signals_base="signals/",
        signals_pattern="{strategy}/{timeframe}/{exchange}.parquet",
        signal_current_base="signals/",
        signal_current_pattern="{strategy}/{timeframe}/{exchange}.parquet",
    )


def test_indicators_happy_path(paths: StockDataPaths):
    assert (
        paths.indicators("close", "1d", "HOSE", "HPG")
        == "indicators/close/1d/hose/hpg.parquet"
    )


def test_signals_happy_path(paths: StockDataPaths):
    assert (
        paths.signals("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "signals/trend_momentum_v1/1d/hose.parquet"
    )
    assert (
        paths.signal_history("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "signals/trend_momentum_v1/1d/hose.parquet"
    )
    assert (
        paths.signal_current("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "signals/trend_momentum_v1/1d/hose.parquet"
    )


def test_eod_and_indicators_normalize_exchange_and_code_consistently(
    paths: StockDataPaths,
):
    assert paths.eod(" HOSE ", " HPG ") == "eod/hose/hpg.parquet"
    assert (
        paths.indicators(" close ", Timeframe.ONE_DAY, " HOSE ", " HPG ")
        == "indicators/close/1d/hose/hpg.parquet"
    )


def test_production_yaml_contains_indicators_path_and_composes_exactly():
    repo_root = Path(__file__).resolve().parents[4]
    config_path = repo_root / "configs" / "shared" / "s3-paths.yaml"
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))["stock-data"]

    paths = StockDataPaths.from_config(config)

    assert config["paths"]["indicators"] == {
        "base": "indicators/",
        "pattern": "{source}/{timeframe}/{exchange}/{code}.parquet",
    }
    assert config["paths"]["signals"] == {
        "base": "signals/",
        "pattern": "{strategy}/{timeframe}/{exchange}.parquet",
    }
    assert config["paths"]["signal-current"] == {
        "base": "signals/",
        "pattern": "{strategy}/{timeframe}/{exchange}.parquet",
    }
    assert paths.eod("HOSE", "HPG") == "eod/hose/hpg.parquet"
    assert (
        paths.indicators("close", "1d", "HOSE", "HPG")
        == "indicators/close/1d/hose/hpg.parquet"
    )
    assert (
        paths.signals("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "signals/trend_momentum_v1/1d/hose.parquet"
    )
    assert (
        paths.signal_current("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG")
        == "signals/trend_momentum_v1/1d/hose.parquet"
    )


def test_raw_string_timeframe_validation_uses_canonical_enum():
    assert Timeframe.validate("1d") is Timeframe.ONE_DAY
    with pytest.raises(ValueError, match="Invalid timeframe"):
        Timeframe.validate("bad")


@pytest.mark.parametrize("timeframe", ["bad", "", "  "])
def test_indicators_rejects_invalid_timeframes(paths: StockDataPaths, timeframe: str):
    with pytest.raises(ValueError):
        paths.indicators("close", timeframe, "HOSE", "HPG")


@pytest.mark.parametrize("timeframe", ["5m", "1h", Timeframe.ONE_HOUR])
def test_indicators_rejects_known_but_disabled_timeframes(
    paths: StockDataPaths, timeframe: Timeframe | str
):
    with pytest.raises(ValueError, match="not enabled"):
        paths.indicators("close", timeframe, "HOSE", "HPG")


@pytest.mark.parametrize("timeframe", ["5m", "1h", Timeframe.ONE_HOUR])
def test_indicator_timeframe_rule_rejects_known_but_disabled_values(
    timeframe: Timeframe | str,
):
    with pytest.raises(ValueError, match="not enabled"):
        validate_indicator_timeframe(timeframe)


@pytest.mark.parametrize("method", ["eod", "indicators", "signals"])
@pytest.mark.parametrize("bad_value", [None, "", "   "])
def test_exchange_rejects_none_empty_and_whitespace_only(
    paths: StockDataPaths,
    method: str,
    bad_value: str | None,
):
    with pytest.raises(ValueError):
        if method == "eod":
            paths.eod(bad_value, "HPG")  # type: ignore[arg-type]
        elif method == "indicators":
            paths.indicators("close", "1d", bad_value, "HPG")  # type: ignore[arg-type]
        else:
            paths.signals("TREND_MOMENTUM_V1", "1d", bad_value, "HPG")  # type: ignore[arg-type]


@pytest.mark.parametrize("method", ["eod", "indicators"])
@pytest.mark.parametrize("bad_value", [None, "", "   "])
def test_code_rejects_none_empty_and_whitespace_only(
    paths: StockDataPaths,
    method: str,
    bad_value: str | None,
):
    with pytest.raises(ValueError):
        if method == "eod":
            paths.eod("HOSE", bad_value)  # type: ignore[arg-type]
        else:
            paths.indicators("close", "1d", "HOSE", bad_value)  # type: ignore[arg-type]


def test_eod_indicators_and_signals_return_strings(paths: StockDataPaths):
    assert isinstance(paths.eod("HOSE", "HPG"), str)
    assert isinstance(paths.indicators("close", "1d", "HOSE", "HPG"), str)
    assert isinstance(paths.signals("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG"), str)
