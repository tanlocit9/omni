from __future__ import annotations

import json
from types import SimpleNamespace

import pandas as pd
import pytest

from app.signals.evaluation_kafka import SignalEvaluationKafkaService
from app.signals.evaluator import SignalOutcomeEvaluator
from app.signals.handler import SignalJobHandler
from app.signals.kafka import SignalKafkaService
from app.signals.messages import SignalEvaluationJobMessage, SignalJobMessage
from app.signals.storage import (
    SignalHistoryRepository,
    SignalOutcomeEvaluation,
    SignalTransition,
)
from app.signals.strategy import (
    MarketSignal,
    SignalResult,
    calculate_trend_momentum_v1,
)


def _job_payload(**overrides):
    payload = {
        "jobDefinitionId": "job-definition-id",
        "executionId": "execution-id",
        "parentExecutionId": "parent-execution-id",
        "source": "ANALYZER",
        "symbolKey": "HOSE-HPG",
        "timeframe": "1d",
        "strategy": "TREND_MOMENTUM_V1",
        "metadata": {"source": "test"},
    }
    payload.update(overrides)
    return payload


def _eod_frame() -> pd.DataFrame:
    dates = pd.date_range("2026-01-01", periods=60, freq="D")
    return pd.DataFrame(
        {
            "date": dates,
            "ad_close": [20.0] * 59 + [120.0],
        }
    )


def _indicator_frame() -> pd.DataFrame:
    dates = pd.date_range("2026-01-01", periods=60, freq="D")
    return pd.DataFrame(
        {
            "date": dates,
            "ma20": [20.0] * 59 + [105.0],
            "ma50": [20.0] * 59 + [100.0],
            "rsi14": [50.0] * 59 + [60.0],
            "macd": [0.0] * 59 + [2.0],
            "macd_signal": [0.0] * 59 + [1.0],
        }
    )


class FakeParquetStorage:
    def __init__(self, frames: dict[str, pd.DataFrame] | None = None) -> None:
        self.frames = frames or {}
        self.writes: dict[str, pd.DataFrame] = {}
        self.replacements: dict[str, pd.DataFrame] = {}
        self.fail_replace_paths: set[str] = set()

    async def read_dataframe(self, path: str) -> pd.DataFrame:
        if path not in self.frames:
            raise FileNotFoundError(path)
        return self.frames[path]

    async def write_dataframe(self, path: str, frame: pd.DataFrame) -> None:
        self.writes[path] = frame
        self.frames[path] = frame

    async def replace_dataframe(self, path: str, frame: pd.DataFrame, **kwargs) -> None:
        if path in self.fail_replace_paths:
            raise RuntimeError("replace failed")
        validate = kwargs.get("validate")
        if validate is not None:
            validate(frame)
        self.replacements[path] = frame
        self.frames[path] = frame


class FakePaths:
    def eod(self, exchange: str, code: str) -> str:
        return f"eod/{exchange.lower()}/{code.lower()}.parquet"

    def indicators(self, source: str, timeframe: str, exchange: str, code: str) -> str:
        return (
            f"indicators/{source}/{timeframe}/{exchange.lower()}/{code.lower()}.parquet"
        )

    def signal_history(
        self, strategy: str, timeframe: str, exchange: str, code: str | None = None
    ) -> str:
        return f"signals/{strategy.lower()}/{timeframe}/{exchange.lower()}.parquet"

    def signal_current(
        self, strategy: str, timeframe: str, exchange: str, code: str | None = None
    ) -> str:
        if code is None:
            return f"signals/{strategy.lower()}/{timeframe}/{exchange.lower()}.parquet"
        return (
            f"signals/{strategy.lower()}/{timeframe}/"
            f"{exchange.lower()}/{code.lower()}.parquet"
        )


class FakeSettings:
    stock_data_paths = FakePaths()
    topic_sync_signals = "topic-sync-signals"
    topic_signal_notifications = "topic-signal-notifications"
    topic_evaluate_signals = "topic-evaluate-signals"
    sync_job_status_topic = "topic-sync-job-status"

    class kafka:
        bootstrap_servers = "localhost:9092"


class FakeHandler:
    def __init__(self, transition=None, exc: Exception | None = None) -> None:
        self.transition = transition
        self.exc = exc

    async def handle(self, payload):
        if self.exc:
            raise self.exc
        return self.transition


class FakeProducer:
    def __init__(self, fail_topics: set[str] | None = None) -> None:
        self.sent = []
        self.fail_topics = fail_topics or set()

    async def send_and_wait(self, topic, payload, key=None):
        if topic in self.fail_topics:
            raise RuntimeError(f"publish failed for {topic}")
        self.sent.append((topic, payload, key))
        return SimpleNamespace(topic=topic, partition=0, offset=len(self.sent) - 1)


def test_signal_job_message_validates_contract():
    message = SignalJobMessage.model_validate(
        _job_payload(strategy="trend_momentum_v1")
    )

    assert message.strategy == "TREND_MOMENTUM_V1"
    assert message.parse_symbol_key() == ("HOSE", "HPG")


@pytest.mark.parametrize(
    "payload",
    [
        _job_payload(symbolKey="HPG"),
        _job_payload(timeframe="1h"),
        _job_payload(strategy="UNKNOWN"),
    ],
)
def test_signal_job_message_rejects_invalid_contract(payload):
    with pytest.raises(ValueError):
        SignalJobMessage.model_validate(payload)


def test_calculate_trend_momentum_v1_returns_bullish_signal():
    result = calculate_trend_momentum_v1(_eod_frame(), _indicator_frame())

    assert result.signal == MarketSignal.BULLISH
    assert result.score == 5
    assert result.price == 120.0
    assert "PRICE_ABOVE_MA50" in result.reason_codes


def test_calculate_trend_momentum_v1_returns_no_decision_for_missing_ad_close():
    result = calculate_trend_momentum_v1(
        _eod_frame().drop(columns=["ad_close"]), _indicator_frame()
    )

    assert result.signal == MarketSignal.NO_DECISION
    assert "MISSING_EOD_COLUMN_AD_CLOSE" in result.reason_codes


@pytest.mark.anyio
async def test_signal_state_storage_persists_baseline_without_change():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    storage = FakeParquetStorage()
    state_storage = SignalHistoryRepository(storage)
    result = calculate_trend_momentum_v1(_eod_frame(), _indicator_frame())

    transition = await state_storage.persist_transition(
        path,
        path,
        "HOSE-HPG",
        "1d",
        result,
        exchange="HOSE",
    )

    assert transition.signal_changed is False
    assert transition.previous_signal is None
    assert transition.new_signal == MarketSignal.BULLISH
    assert transition.metadata["signalChanged"] is False
    assert path in storage.replacements
    assert len(storage.replacements) == 1
    written = storage.replacements[path]
    assert written.iloc[0]["exchange"] == "HOSE"
    assert written.iloc[0]["signal_price"] == 120.0


@pytest.mark.anyio
async def test_signal_state_storage_detects_transition():
    existing = pd.DataFrame(
        [
            {
                "signal": "NEUTRAL",
                "generated_at": pd.Timestamp("2026-01-01T00:00:00Z"),
            }
        ]
    )
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    storage = FakeParquetStorage({path: existing})
    state_storage = SignalHistoryRepository(storage)
    result = calculate_trend_momentum_v1(_eod_frame(), _indicator_frame())

    transition = await state_storage.persist_transition(
        path, None, "HOSE-HPG", "1d", result
    )

    assert transition.signal_changed is True
    assert transition.previous_signal == MarketSignal.NEUTRAL
    assert transition.metadata["previousSignal"] == "NEUTRAL"
    assert transition.metadata["newSignal"] == "BULLISH"


@pytest.mark.anyio
async def test_signal_repository_upserts_history_by_signal_date():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    existing = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "NEUTRAL",
                "signal_price": 100.0,
                "signal_date": "2026-03-01",
                "score": 0,
                "reason_codes": [],
                "generated_at": pd.Timestamp("2026-03-01T00:00:00Z"),
            },
            {
                "symbol_key": "HOSE-HPG",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BEARISH",
                "signal_price": 90.0,
                "signal_date": "2026-07-28",
                "score": -4,
                "reason_codes": ["OLD"],
                "generated_at": pd.Timestamp("2026-07-28T00:00:00Z"),
            },
        ]
    )
    storage = FakeParquetStorage({path: existing})
    repository = SignalHistoryRepository(storage)
    result = SignalResult(
        signal=MarketSignal.BULLISH,
        score=4,
        reason_codes=["PRICE_ABOVE_MA50"],
        price=120.0,
        signal_date="2026-07-28",
        strategy="TREND_MOMENTUM_V1",
    )

    await repository.persist_transition(path, None, "HOSE-HPG", "1d", result)

    written = storage.replacements[path]
    assert len(written) == 2
    latest = written[written["signal_date"].astype(str) == "2026-07-28"].iloc[0]
    assert latest["signal"] == "BULLISH"
    assert latest["score"] == 4
    assert latest["signal_price"] == 120.0


@pytest.mark.anyio
async def test_signal_repository_updates_available_actual_outcomes_by_trading_day():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    history = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-02",
                "score": 4,
                "reason_codes": ["PRICE_ABOVE_MA50"],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
            }
        ]
    )
    eod = pd.DataFrame(
        {
            "date": pd.to_datetime(
                [
                    "2026-01-02",
                    "2026-01-05",
                    "2026-01-06",
                    "2026-01-07",
                    "2026-01-08",
                    "2026-01-09",
                ]
            ),
            "ad_close": [100.0, 101.0, 102.0, 103.0, 104.0, 110.0],
        }
    )
    storage = FakeParquetStorage({path: history})
    repository = SignalHistoryRepository(storage)

    async def load_eod(symbol_key: str) -> pd.DataFrame:
        return eod

    evaluation = await repository.update_outcomes(path, load_eod)

    written = storage.replacements[path]
    row = written.iloc[0]
    assert evaluation.records_scanned == 1
    assert evaluation.records_updated == 1
    assert row["actual_price_t5"] == 110.0
    assert row["actual_return_t5"] == 0.1
    assert pd.isna(row["actual_price_t10"])
    assert row["signal"] == "BULLISH"
    assert row["signal_price"] == 100.0


@pytest.mark.anyio
async def test_signal_repository_preserves_existing_outcomes_on_idempotent_rerun():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    history = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-02",
                "score": 4,
                "reason_codes": ["PRICE_ABOVE_MA50"],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
                "actual_price_t5": 110.0,
                "actual_return_t5": 0.1,
                "actual_updated_at": pd.Timestamp("2026-01-09T00:00:00Z"),
            }
        ]
    )
    eod = pd.DataFrame(
        {
            "date": pd.date_range("2026-01-02", periods=6, freq="B"),
            "ad_close": [100.0, 101.0, 102.0, 103.0, 104.0, 999.0],
        }
    )
    storage = FakeParquetStorage({path: history})
    repository = SignalHistoryRepository(storage)

    async def load_eod(symbol_key: str) -> pd.DataFrame:
        return eod

    evaluation = await repository.update_outcomes(path, load_eod)

    assert evaluation.records_updated == 0
    assert path not in storage.replacements


@pytest.mark.anyio
async def test_signal_repository_does_not_persist_no_decision():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    existing = pd.DataFrame(
        [
            {
                "signal": "BULLISH",
                "signal_date": "2026-07-27",
                "generated_at": pd.Timestamp("2026-07-27T00:00:00Z"),
            }
        ]
    )
    storage = FakeParquetStorage({path: existing})
    repository = SignalHistoryRepository(storage)
    result = SignalResult(
        signal=MarketSignal.NO_DECISION,
        score=0,
        reason_codes=["MISSING_EOD_COLUMN_AD_CLOSE"],
        price=None,
        signal_date="2026-07-28",
        strategy="TREND_MOMENTUM_V1",
    )

    transition = await repository.persist_transition(
        path, None, "HOSE-HPG", "1d", result
    )

    assert transition.persisted is False
    assert transition.signal_changed is False
    assert transition.previous_signal == MarketSignal.BULLISH
    assert path not in storage.replacements


@pytest.mark.anyio
async def test_signal_repository_same_day_rerun_preserves_outcomes_and_signal():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    updated_at = pd.Timestamp("2026-01-09T00:00:00Z")
    existing = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BEARISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-02",
                "score": -4,
                "reason_codes": ["OLD"],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
                "revision": 3,
                "actual_price_t5": 110.0,
                "actual_return_t5": 0.1,
                "actual_updated_at": updated_at,
            }
        ]
    )
    storage = FakeParquetStorage({path: existing})
    repository = SignalHistoryRepository(storage)
    result = SignalResult(
        signal=MarketSignal.BULLISH,
        score=5,
        reason_codes=["PRICE_ABOVE_MA50", "MACD_BULLISH"],
        price=120.0,
        signal_date="2026-01-02",
        strategy="TREND_MOMENTUM_V1",
    )

    await repository.persist_transition(path, None, "HOSE-HPG", "1d", result)

    written = storage.replacements[path]
    assert len(written) == 1
    row = written.iloc[0]
    assert row["signal"] == "BULLISH"
    assert row["score"] == 5
    assert row["signal_price"] == 120.0
    assert row["reason_codes"] == ["PRICE_ABOVE_MA50", "MACD_BULLISH"]
    assert row["actual_price_t5"] == 110.0
    assert row["actual_return_t5"] == 0.1
    assert row["actual_updated_at"] == updated_at
    assert row["revision"] == 4
    assert pd.notna(row["last_recalculated_at"])


@pytest.mark.anyio
async def test_signal_repository_new_trading_day_appends_new_row():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    existing = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-02",
                "score": 4,
                "reason_codes": ["OLD"],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
            }
        ]
    )
    storage = FakeParquetStorage({path: existing})
    repository = SignalHistoryRepository(storage)
    result = SignalResult(
        signal=MarketSignal.NEUTRAL,
        score=0,
        reason_codes=["FLAT"],
        price=101.0,
        signal_date="2026-01-05",
        strategy="TREND_MOMENTUM_V1",
    )

    await repository.persist_transition(path, None, "HOSE-HPG", "1d", result)

    written = storage.replacements[path]
    assert len(written) == 2
    assert set(written["signal_date"].astype(str)) == {"2026-01-02", "2026-01-05"}


@pytest.mark.anyio
async def test_signal_handler_reads_eod_indicators_and_writes_signal_path():
    eod_path = "eod/hose/hpg.parquet"
    indicators_path = "indicators/ad_close/1d/hose/hpg.parquet"
    signals_path = "signals/trend_momentum_v1/1d/hose.parquet"
    storage = FakeParquetStorage(
        {
            eod_path: _eod_frame(),
            indicators_path: _indicator_frame(),
        }
    )
    handler = SignalJobHandler(FakeSettings(), storage)

    transition = await handler.handle(_job_payload())

    assert transition.new_signal == MarketSignal.BULLISH
    assert signals_path in storage.replacements
    assert "signals/trend_momentum_v1/1d/hose/hpg.parquet" in storage.replacements
    assert len(storage.replacements) == 2


@pytest.mark.anyio
async def test_signal_handler_multiple_symbols_share_one_exchange_history_file():
    storage = FakeParquetStorage(
        {
            "eod/hose/acb.parquet": _eod_frame(),
            "indicators/ad_close/1d/hose/acb.parquet": _indicator_frame(),
            "eod/hose/mbb.parquet": _eod_frame(),
            "indicators/ad_close/1d/hose/mbb.parquet": _indicator_frame(),
        }
    )
    handler = SignalJobHandler(FakeSettings(), storage)

    await handler.handle(_job_payload(symbolKey="HOSE-ACB"))
    await handler.handle(_job_payload(symbolKey="HOSE-MBB"))

    history_path = "signals/trend_momentum_v1/1d/hose.parquet"
    assert history_path in storage.replacements
    written = storage.replacements[history_path]
    assert set(written["symbol_key"]) == {"HOSE-ACB", "HOSE-MBB"}
    assert all(
        not path.endswith("hose/acb.parquet")
        for path in storage.replacements
        if path == history_path
    )
    assert all(
        not path.endswith("hose/mbb.parquet")
        for path in storage.replacements
        if path == history_path
    )


@pytest.mark.anyio
async def test_signal_evaluator_reads_same_shared_path_used_by_persistence():
    history_path = "signals/trend_momentum_v1/1d/hose.parquet"
    storage = FakeParquetStorage(
        {
            history_path: pd.DataFrame(
                [
                    {
                        "symbol_key": "HOSE-ACB",
                        "exchange": "HOSE",
                        "strategy": "TREND_MOMENTUM_V1",
                        "timeframe": "1d",
                        "signal": "BULLISH",
                        "signal_price": 100.0,
                        "signal_date": "2026-01-02",
                        "score": 4,
                        "reason_codes": ["PRICE_ABOVE_MA50"],
                        "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
                    }
                ]
            ),
            "eod/hose/acb.parquet": pd.DataFrame(
                {
                    "date": pd.date_range("2026-01-05", periods=5, freq="B"),
                    "ad_close": [101.0, 102.0, 103.0, 104.0, 105.0],
                }
            ),
        }
    )
    evaluator = SignalOutcomeEvaluator(FakeSettings(), storage)

    await evaluator.evaluate(
        {
            "jobDefinitionId": "job-definition-id",
            "executionId": "execution-id",
            "source": "ANALYZER",
            "exchange": "HOSE",
            "timeframe": "1d",
            "strategy": "TREND_MOMENTUM_V1",
        }
    )

    assert history_path in storage.replacements
    assert "signals/trend_momentum_v1/1d/hose/acb.parquet" not in storage.replacements


@pytest.mark.anyio
async def test_signal_repository_t_windows_use_strict_future_trading_sessions():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    history = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-02",
                "score": 4,
                "reason_codes": ["PRICE_ABOVE_MA50"],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
            }
        ]
    )
    dates = pd.date_range("2026-01-05", periods=20, freq="B")
    eod = pd.DataFrame({"date": dates, "ad_close": [100 + i for i in range(1, 21)]})
    storage = FakeParquetStorage({path: history})
    repository = SignalHistoryRepository(storage)

    async def load_eod(symbol_key: str) -> pd.DataFrame:
        return eod

    await repository.update_outcomes(path, load_eod)

    row = storage.replacements[path].iloc[0]
    assert row["actual_price_t5"] == 105.0
    assert row["actual_price_t10"] == 110.0
    assert row["actual_price_t15"] == 115.0
    assert row["actual_price_t20"] == 120.0


@pytest.mark.anyio
async def test_signal_repository_normalizes_duplicate_eod_dates_keep_last():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    history = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-03",
                "score": 4,
                "reason_codes": ["PRICE_ABOVE_MA50"],
                "generated_at": pd.Timestamp("2026-01-03T00:00:00Z"),
            }
        ]
    )
    eod = pd.DataFrame(
        {
            "date": [
                "bad-date",
                "2026-01-05",
                "2026-01-05",
                "2026-01-06",
                "2026-01-07",
                "2026-01-08",
                "2026-01-09",
                "2026-01-12",
            ],
            "ad_close": [999.0, 101.0, 111.0, 102.0, 103.0, None, 105.0, 106.0],
        }
    )
    storage = FakeParquetStorage({path: history})
    repository = SignalHistoryRepository(storage)

    async def load_eod(symbol_key: str) -> pd.DataFrame:
        return eod

    await repository.update_outcomes(path, load_eod)

    assert storage.replacements[path].iloc[0]["actual_price_t5"] == 106.0


@pytest.mark.anyio
async def test_signal_repository_missing_eod_one_symbol_does_not_fail_batch():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    history = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-ACB",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-02",
                "score": 4,
                "reason_codes": [],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
            },
            {
                "symbol_key": "HOSE-MBB",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 200.0,
                "signal_date": "2026-01-02",
                "score": 4,
                "reason_codes": [],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
            },
        ]
    )
    storage = FakeParquetStorage({path: history})
    repository = SignalHistoryRepository(storage)

    async def load_eod(symbol_key: str) -> pd.DataFrame:
        if symbol_key == "HOSE-ACB":
            raise FileNotFoundError(symbol_key)
        return pd.DataFrame(
            {
                "date": pd.date_range("2026-01-05", periods=5, freq="B"),
                "ad_close": [201.0, 202.0, 203.0, 204.0, 210.0],
            }
        )

    evaluation = await repository.update_outcomes(path, load_eod)

    assert evaluation.records_scanned == 2
    assert evaluation.records_updated == 1
    assert evaluation.metadata["missingEodCount"] == 1
    assert evaluation.metadata["missingEodSymbols"] == ["HOSE-ACB"]
    written = storage.replacements[path]
    acb = written[written["symbol_key"] == "HOSE-ACB"].iloc[0]
    mbb = written[written["symbol_key"] == "HOSE-MBB"].iloc[0]
    assert pd.isna(acb["actual_price_t5"])
    assert mbb["actual_price_t5"] == 210.0


@pytest.mark.anyio
async def test_signal_repository_safe_replacement_preserves_old_file_on_failure():
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    existing = pd.DataFrame(
        [
            {
                "symbol_key": "HOSE-HPG",
                "exchange": "HOSE",
                "strategy": "TREND_MOMENTUM_V1",
                "timeframe": "1d",
                "signal": "BULLISH",
                "signal_price": 100.0,
                "signal_date": "2026-01-02",
                "score": 4,
                "reason_codes": [],
                "generated_at": pd.Timestamp("2026-01-02T00:00:00Z"),
            }
        ]
    )
    storage = FakeParquetStorage({path: existing.copy()})
    storage.fail_replace_paths.add(path)
    repository = SignalHistoryRepository(storage)
    result = SignalResult(
        signal=MarketSignal.BEARISH,
        score=-4,
        reason_codes=["REVERSAL"],
        price=90.0,
        signal_date="2026-01-02",
        strategy="TREND_MOMENTUM_V1",
    )

    with pytest.raises(RuntimeError):
        await repository.persist_transition(path, None, "HOSE-HPG", "1d", result)

    assert storage.frames[path].equals(existing)
    assert path not in storage.replacements


@pytest.mark.anyio
async def test_signal_evaluation_kafka_status_uses_scanned_as_processed():
    service = SignalEvaluationKafkaService(FakeSettings(), evaluator=None)
    evaluation = SignalOutcomeEvaluation(
        records_scanned=7,
        records_updated=3,
        metadata={"recordsScanned": 7, "recordsUpdated": 3},
    )
    message = SignalEvaluationJobMessage.model_validate(
        {
            "jobDefinitionId": "job-definition-id",
            "executionId": "execution-id",
            "source": "ANALYZER",
            "exchange": "HOSE",
            "timeframe": "1d",
            "strategy": "TREND_MOMENTUM_V1",
        }
    )

    status = service._build_status(
        message,
        pd.Timestamp.now(),
        pd.Timestamp.now(),
        evaluation,
    )

    assert status.records_processed == 7
    assert status.meta_json["recordsProcessed"] == 7
    assert status.meta_json["recordsScanned"] == 7
    assert status.meta_json["recordsUpdated"] == 3


@pytest.mark.anyio
async def test_signal_kafka_service_publishes_success_with_transition_metadata():
    transition = SignalTransition(
        signal_changed=True,
        previous_signal=MarketSignal.NEUTRAL,
        new_signal=MarketSignal.BULLISH,
        state_frame=pd.DataFrame(),
        metadata={
            **SignalResult(
                signal=MarketSignal.BULLISH,
                score=4,
                reason_codes=["PRICE_ABOVE_MA50", "MACD_BULLISH"],
                price=28000.0,
                signal_date="2026-07-28",
                strategy="TREND_MOMENTUM_V1",
            ).to_metadata(),
            "signalChanged": True,
            "previousSignal": "NEUTRAL",
            "timeframe": "1d",
        },
    )
    service = SignalKafkaService(FakeSettings(), FakeHandler(transition=transition))
    producer = FakeProducer()
    service._producer = producer

    status = await service.process_payload(_job_payload())

    assert status is not None
    assert status.status.value == "SUCCESS"
    assert status.execution_id == "execution-id"
    assert status.parent_execution_id == "parent-execution-id"
    assert status.symbol_key == "HOSE-HPG"
    assert status.records_processed == 1
    assert status.meta_json == {
        "newSignal": "BULLISH",
        "price": 28000.0,
        "signalDate": "2026-07-28",
        "reasonCodes": ["PRICE_ABOVE_MA50", "MACD_BULLISH"],
        "score": 4,
        "strategy": "TREND_MOMENTUM_V1",
        "signalChanged": True,
        "previousSignal": "NEUTRAL",
        "timeframe": "1d",
        "recordsProcessed": 1,
    }
    assert producer.sent
    status_topic, _, _ = producer.sent[0]
    assert status_topic == "topic-sync-job-status"


@pytest.mark.anyio
async def test_signal_kafka_service_publishes_notification_for_changed_signal():
    transition = SignalTransition(
        signal_changed=True,
        previous_signal=MarketSignal.NEUTRAL,
        new_signal=MarketSignal.BULLISH,
        state_frame=pd.DataFrame(),
        metadata={
            **SignalResult(
                signal=MarketSignal.BULLISH,
                score=4,
                reason_codes=["PRICE_ABOVE_MA50", "MACD_BULLISH"],
                price=28000.0,
                signal_date="2026-07-28",
                strategy="TREND_MOMENTUM_V1",
            ).to_metadata(),
            "signalChanged": True,
            "previousSignal": "NEUTRAL",
            "timeframe": "1d",
        },
    )
    service = SignalKafkaService(FakeSettings(), FakeHandler(transition=transition))
    producer = FakeProducer()
    service._producer = producer

    status = await service.process_payload(_job_payload())

    assert status is not None
    assert [sent[0] for sent in producer.sent] == [
        "topic-sync-job-status",
        "topic-signal-notifications",
    ]
    notification_topic, notification_payload, notification_key = producer.sent[1]
    notification = json.loads(notification_payload.decode("utf-8"))
    assert notification_topic == "topic-signal-notifications"
    assert notification_key == b"HOSE-HPG"
    assert notification == {
        "type": "SIGNAL_CHANGED",
        "jobDefinitionId": "job-definition-id",
        "executionId": "execution-id",
        "parentExecutionId": "parent-execution-id",
        "source": "ANALYZER",
        "symbolKey": "HOSE-HPG",
        "timeframe": "1d",
        "strategy": "TREND_MOMENTUM_V1",
        "previousSignal": "NEUTRAL",
        "newSignal": "BULLISH",
        "price": 28000.0,
        "signalDate": "2026-07-28",
        "reasonCodes": ["PRICE_ABOVE_MA50", "MACD_BULLISH"],
        "score": 4,
        "signalChanged": True,
        "createdAt": notification["createdAt"],
        "metadata": {
            "newSignal": "BULLISH",
            "price": 28000.0,
            "signalDate": "2026-07-28",
            "reasonCodes": ["PRICE_ABOVE_MA50", "MACD_BULLISH"],
            "score": 4,
            "strategy": "TREND_MOMENTUM_V1",
            "signalChanged": True,
            "previousSignal": "NEUTRAL",
            "timeframe": "1d",
        },
    }


@pytest.mark.anyio
async def test_signal_kafka_service_keeps_status_when_notification_publish_fails():
    transition = SignalTransition(
        signal_changed=True,
        previous_signal=MarketSignal.NEUTRAL,
        new_signal=MarketSignal.BULLISH,
        state_frame=pd.DataFrame(),
        metadata={
            **SignalResult(
                signal=MarketSignal.BULLISH,
                score=4,
                reason_codes=["PRICE_ABOVE_MA50"],
                price=28000.0,
                signal_date="2026-07-28",
                strategy="TREND_MOMENTUM_V1",
            ).to_metadata(),
            "signalChanged": True,
            "previousSignal": "NEUTRAL",
            "timeframe": "1d",
        },
    )
    service = SignalKafkaService(FakeSettings(), FakeHandler(transition=transition))
    producer = FakeProducer(fail_topics={"topic-signal-notifications"})
    service._producer = producer

    status = await service.process_payload(_job_payload())

    assert status is not None
    assert status.status.value == "SUCCESS"
    assert [sent[0] for sent in producer.sent] == ["topic-sync-job-status"]


@pytest.mark.anyio
async def test_signal_kafka_service_skips_notification_when_signal_unchanged():
    transition = SignalTransition(
        signal_changed=False,
        previous_signal=MarketSignal.BULLISH,
        new_signal=MarketSignal.BULLISH,
        state_frame=pd.DataFrame(),
        metadata={
            **SignalResult(
                signal=MarketSignal.BULLISH,
                score=4,
                reason_codes=["PRICE_ABOVE_MA50", "MACD_BULLISH"],
                price=28000.0,
                signal_date="2026-07-28",
                strategy="TREND_MOMENTUM_V1",
            ).to_metadata(),
            "signalChanged": False,
            "previousSignal": "BULLISH",
            "timeframe": "1d",
        },
    )
    service = SignalKafkaService(FakeSettings(), FakeHandler(transition=transition))
    producer = FakeProducer()
    service._producer = producer

    status = await service.process_payload(_job_payload())

    assert status is not None
    assert [sent[0] for sent in producer.sent] == ["topic-sync-job-status"]


@pytest.mark.anyio
async def test_signal_kafka_service_skips_malformed_payload_without_status():
    service = SignalKafkaService(FakeSettings(), FakeHandler())
    service._producer = FakeProducer()

    status = await service.process_payload("not-json")

    assert status is None
    assert service._producer.sent == []


@pytest.mark.anyio
async def test_signal_kafka_service_skips_payload_without_execution_id():
    service = SignalKafkaService(FakeSettings(), FakeHandler())
    service._producer = FakeProducer()

    status = await service.process_payload(_job_payload(executionId=""))

    assert status is None
    assert service._producer.sent == []


@pytest.mark.anyio
async def test_signal_kafka_service_publishes_error_status_for_invalid_payload():
    service = SignalKafkaService(FakeSettings(), FakeHandler())
    producer = FakeProducer()
    service._producer = producer

    status = await service.process_payload(_job_payload(symbolKey="HPG"))

    assert status is not None
    assert status.status.value == "ERROR"
    assert status.execution_id == "execution-id"
    assert status.symbol_key == "HPG"
    assert status.records_processed == 0
    assert status.meta_json == {"recordsProcessed": 0}
    assert status.error_message
    assert producer.sent


@pytest.mark.anyio
async def test_signal_kafka_service_publishes_error_status_for_valid_execution_id():
    service = SignalKafkaService(FakeSettings(), FakeHandler(exc=RuntimeError("boom")))
    producer = FakeProducer()
    service._producer = producer

    status = await service.process_payload(_job_payload())

    assert status is not None
    assert status.status.value == "ERROR"
    assert status.execution_id == "execution-id"
    assert producer.sent
