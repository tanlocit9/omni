from __future__ import annotations

import json

import pandas as pd
import pytest
from py_common.storage.exceptions import StorageObjectNotFoundError

from app.signals.intraday_confirmation import (
    CloseVsVwapDirection,
    IntradayConfirmation,
    IntradayDataset,
    IntradayDatasetResolver,
    IntradayDirection,
    calculate_intraday_facts,
    evaluate_intraday_confirmation,
)
from app.signals.strategy import (
    CONFIRMED_TREND_EQUALS,
    CONFIRMED_TREND_EQUALS_V2_MODEL_VERSION,
    MarketSignal,
    SignalResult,
    calculate_confirmed_trend_equals_v2,
)

_DATA_VERSION = f"sha256:{'a' * 64}"


def _trades(prices=(100.0, 102.0, 104.0), volumes=(10, 20, 30)):
    return pd.DataFrame(
        {
            "timestamp": pd.to_datetime(
                [
                    "2026-09-11T06:00:00Z",
                    "2026-09-11T07:00:00Z",
                    "2026-09-11T07:20:00Z",
                ],
                utc=True,
            ),
            "price": prices,
            "volume": volumes,
            "trading_date": [pd.Timestamp("2026-09-11").date()] * 3,
            "exchange": ["HOSE"] * 3,
            "symbol": ["HPG"] * 3,
        }
    )


def _dataset(frame=None, reconciliation="READY"):
    return IntradayDataset(
        frame=_trades() if frame is None else frame,
        dataset="intraday-trades",
        data_version=_DATA_VERSION,
        manifest_version=1,
        reconciliation_status=reconciliation,
        manifest_path="manifest.json",
    )


def _daily(signal: MarketSignal) -> SignalResult:
    return SignalResult(
        signal=signal,
        price=104.0,
        signal_date="2026-09-11",
        reason_codes=["EQUAL_VOTE_SCORE_1"],
        score=1.0,
        strategy=CONFIRMED_TREND_EQUALS,
        components=[
            {
                "strategy": "TREND_MOMENTUM_V1",
                "modelVersion": "TREND_MOMENTUM_V1",
                "inputVersions": {"eodDataVersion": "trend-eod"},
            },
            {
                "strategy": "ICHIMOKU_V1",
                "modelVersion": "ICHIMOKU_V1",
                "inputVersions": {"indicatorsDataVersion": "ichimoku-indicators"},
            },
        ],
    )


def test_calculates_intraday_facts_deterministically():
    facts = calculate_intraday_facts(
        _dataset(), symbol_key="HOSE-HPG", trading_date="2026-09-11"
    )

    assert facts.open_price == 100.0
    assert facts.close_price == 104.0
    assert facts.session_vwap == pytest.approx(102.6666666667)
    assert facts.session_direction == IntradayDirection.BULLISH
    assert facts.close_vs_vwap_direction == CloseVsVwapDirection.ABOVE
    expected_delta = (104 - 102.6666666667) / 102.6666666667
    assert facts.close_vs_vwap_delta_pct == pytest.approx(expected_delta)
    assert facts.last_30m_direction == IntradayDirection.BULLISH
    assert facts.late_volume_share == pytest.approx(50 / 60)


def test_confirmation_requires_two_aligned_facts_and_no_opposition():
    bullish = calculate_intraday_facts(
        _dataset(), symbol_key="HOSE-HPG", trading_date="2026-09-11"
    )
    mixed = calculate_intraday_facts(
        _dataset(_trades(prices=(100.0, 110.0, 101.0))),
        symbol_key="HOSE-HPG",
        trading_date="2026-09-11",
    )

    assert (
        evaluate_intraday_confirmation(bullish).result
        == IntradayConfirmation.BULLISH_CONFIRM
    )
    assert evaluate_intraday_confirmation(mixed).result == IntradayConfirmation.MIXED
    assert (
        evaluate_intraday_confirmation(None, ["MISSING_INTRADAY"]).result
        == IntradayConfirmation.UNAVAILABLE
    )


@pytest.mark.parametrize(
    ("daily", "confirmation", "expected"),
    [
        (
            MarketSignal.BULLISH,
            IntradayConfirmation.BULLISH_CONFIRM,
            MarketSignal.BULLISH,
        ),
        (
            MarketSignal.BULLISH,
            IntradayConfirmation.BEARISH_CONFIRM,
            MarketSignal.NEUTRAL,
        ),
        (MarketSignal.BULLISH, IntradayConfirmation.MIXED, MarketSignal.NEUTRAL),
        (
            MarketSignal.BULLISH,
            IntradayConfirmation.UNAVAILABLE,
            MarketSignal.NO_DECISION,
        ),
        (
            MarketSignal.BEARISH,
            IntradayConfirmation.BEARISH_CONFIRM,
            MarketSignal.BEARISH,
        ),
        (
            MarketSignal.BEARISH,
            IntradayConfirmation.BULLISH_CONFIRM,
            MarketSignal.NEUTRAL,
        ),
        (
            MarketSignal.NEUTRAL,
            IntradayConfirmation.BULLISH_CONFIRM,
            MarketSignal.NEUTRAL,
        ),
    ],
)
def test_v2_composition_matrix(daily, confirmation, expected):
    facts = None
    reasons = ["MISSING_INTRADAY"]
    if confirmation != IntradayConfirmation.UNAVAILABLE:
        facts = calculate_intraday_facts(
            _dataset(), symbol_key="HOSE-HPG", trading_date="2026-09-11"
        )
        reasons = []
    intraday = evaluate_intraday_confirmation(facts, reasons)
    if intraday.result != confirmation:
        intraday = type(intraday)(confirmation, intraday.reason_codes, intraday.facts)

    result = calculate_confirmed_trend_equals_v2(_daily(daily), intraday)

    assert result.signal == expected
    assert result.model_version == CONFIRMED_TREND_EQUALS_V2_MODEL_VERSION
    if facts is None:
        assert "intraday" not in result.input_versions
    else:
        assert result.input_versions["intraday"]["dataVersion"] == _DATA_VERSION


@pytest.mark.anyio
async def test_v2_row_coexists_with_same_date_v1_history():
    from app.signals.storage import SignalHistoryRepository

    path = "signals/confirmed_trend_equals/1d/hose.parquet"
    legacy = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": CONFIRMED_TREND_EQUALS,
                "timeframe": "1d",
                "signal_date": "2026-09-11",
                "signal": "BULLISH",
                "signal_price": 103.0,
                "score": 1.0,
                "reason_codes": ["V1"],
                "model_version": "CONFIRMED_TREND_EQUALS_V1",
            }
        ]
    )
    storage = _HistoryStorage({path: legacy})
    repository = SignalHistoryRepository(storage)
    facts = calculate_intraday_facts(
        _dataset(), symbol_key="HOSE-HPG", trading_date="2026-09-11"
    )
    result = calculate_confirmed_trend_equals_v2(
        _daily(MarketSignal.BULLISH), evaluate_intraday_confirmation(facts)
    )

    transition = await repository.persist_transition(
        path, None, "HOSE-HPG", "1d", result, exchange="HOSE"
    )

    assert transition.history_frame is not None
    assert len(transition.history_frame) == 2
    assert set(transition.history_frame["model_version"]) == {
        "CONFIRMED_TREND_EQUALS_V1",
        CONFIRMED_TREND_EQUALS_V2_MODEL_VERSION,
    }


class _HistoryStorage:
    def __init__(self, frames):
        self.frames = frames

    async def read_dataframe(self, path):
        if path not in self.frames:
            raise FileNotFoundError(path)
        return self.frames[path]

    async def replace_dataframe(self, path, frame, **kwargs):
        validate = kwargs.get("validate")
        if validate is not None:
            validate(frame)
        self.frames[path] = frame
        return type("WriteResult", (), {"object_name": path})()


class _Readable:
    def __init__(self, objects):
        self.objects = objects

    async def read_bytes(self, bucket, object_name):
        if object_name not in self.objects:
            raise StorageObjectNotFoundError(bucket, object_name)
        return self.objects[object_name]


class _Parquet:
    def __init__(self, frames):
        self.frames = frames

    async def read_dataframe(self, path):
        if path not in self.frames:
            raise StorageObjectNotFoundError("stock-data", path)
        return self.frames[path]


class _Paths:
    def intraday_trades(self, provider, exchange, trading_date, symbol):
        return (
            f"intraday/trades/provider={provider.lower()}/"
            f"exchange={exchange.lower()}/trading_date={trading_date}/"
            f"symbol={symbol.lower()}/trades.parquet"
        )


@pytest.mark.anyio
async def test_resolver_reads_exact_ready_partition_and_propagates_warning():
    prefix = (
        "intraday/trades/provider=vci/exchange=hose/trading_date=2026-09-11/symbol=hpg"
    )
    manifest_path = f"{prefix}/_versions/a/manifest.json"
    data_path = f"{prefix}/_versions/a/trades.parquet"
    readable = _Readable(
        {
            f"{prefix}/READY.json": json.dumps(
                {
                    "status": "READY",
                    "dataVersion": _DATA_VERSION,
                    "manifestPath": manifest_path,
                }
            ).encode(),
            manifest_path: json.dumps(
                {
                    "dataset": "intraday-trades",
                    "partition": {
                        "provider": "vci",
                        "exchange": "hose",
                        "trading_date": "2026-09-11",
                        "symbol": "hpg",
                    },
                    "normalizationVersion": 1,
                    "dataVersion": _DATA_VERSION,
                    "path": data_path,
                    "reconciliation": {"status": "WARNING"},
                }
            ).encode(),
        }
    )
    resolver = IntradayDatasetResolver(
        readable,
        _Parquet({data_path: _trades()}),
        "stock-data",
        _Paths(),
    )

    resolution = await resolver.resolve(
        provider="VCI",
        exchange="HOSE",
        symbol="HPG",
        trading_date="2026-09-11",
    )

    assert resolution.dataset is not None
    assert resolution.dataset.reconciliation_status == "WARNING"
    assert resolution.dataset.data_version == _DATA_VERSION


@pytest.mark.anyio
async def test_resolver_never_falls_back_from_missing_exact_date():
    resolver = IntradayDatasetResolver(
        _Readable({}), _Parquet({}), "stock-data", _Paths()
    )

    resolution = await resolver.resolve(
        provider="VCI",
        exchange="HOSE",
        symbol="HPG",
        trading_date="2026-09-11",
    )

    assert resolution.dataset is None
    assert resolution.reason_codes == ["MISSING_INTRADAY"]


@pytest.mark.anyio
async def test_resolver_does_not_use_another_symbols_ready_pointer():
    other_prefix = (
        "intraday/trades/provider=vci/exchange=hose/trading_date=2026-09-11/symbol=fpt"
    )
    resolver = IntradayDatasetResolver(
        _Readable(
            {
                f"{other_prefix}/READY.json": json.dumps(
                    {
                        "status": "READY",
                        "dataVersion": _DATA_VERSION,
                        "manifestPath": f"{other_prefix}/manifest.json",
                    }
                ).encode()
            }
        ),
        _Parquet({}),
        "stock-data",
        _Paths(),
    )

    resolution = await resolver.resolve(
        provider="VCI",
        exchange="HOSE",
        symbol="HPG",
        trading_date="2026-09-11",
    )

    assert resolution.dataset is None
    assert resolution.reason_codes == ["MISSING_INTRADAY"]
