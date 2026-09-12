from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any

import pandas as pd

REQUIRED_EOD_COLUMNS = {"date", "ad_close"}
REQUIRED_INDICATOR_COLUMNS = {"date", "ma20", "ma50", "rsi14", "macd", "macd_signal"}
REQUIRED_ICHIMOKU_COLUMNS = {
    "date",
    "ichimoku_tenkan",
    "ichimoku_kijun",
    "ichimoku_span_a",
    "ichimoku_span_b",
}
TREND_MOMENTUM_V1 = "TREND_MOMENTUM_V1"
ICHIMOKU_V1 = "ICHIMOKU_V1"
CONFIRMED_TREND_EQUALS = "CONFIRMED_TREND_EQUALS"
CONFIRMED_TREND_EQUALS_MODEL_VERSION = "CONFIRMED_TREND_EQUALS_V1"
CONFIRMED_TREND_EQUALS_V2_MODEL_VERSION = "CONFIRMED_TREND_EQUALS_V2_INTRADAY"
BULLISH_THRESHOLD = 3
BEARISH_THRESHOLD = -3


class MarketSignal(StrEnum):
    BULLISH = "BULLISH"
    NEUTRAL = "NEUTRAL"
    BEARISH = "BEARISH"
    NO_DECISION = "NO_DECISION"


@dataclass(frozen=True)
class SignalComponent:
    symbol_key: str
    timeframe: str
    result: SignalResult


@dataclass(frozen=True)
class SignalResult:
    signal: MarketSignal
    price: float | None
    signal_date: str | None
    reason_codes: list[str]
    score: float
    strategy: str = TREND_MOMENTUM_V1
    model_version: str | None = None
    components: list[dict[str, Any]] | None = None
    input_versions: dict[str, Any] | None = None
    intraday_confirmation: dict[str, Any] | None = None

    def to_metadata(self) -> dict[str, Any]:
        metadata = {
            "newSignal": self.signal.value,
            "price": self.price,
            "signalDate": self.signal_date,
            "reasonCodes": self.reason_codes,
            "score": self.score,
            "strategy": self.strategy,
        }
        if self.model_version is not None:
            metadata["modelVersion"] = self.model_version
        if self.components is not None:
            metadata["components"] = self.components
        if self.input_versions is not None:
            metadata["inputVersions"] = self.input_versions
        if self.intraday_confirmation is not None:
            metadata["intradayConfirmation"] = self.intraday_confirmation
        return metadata


def calculate_trend_momentum_v1(
    eod_frame: pd.DataFrame,
    indicators_frame: pd.DataFrame,
) -> SignalResult:
    """Calculate Market Signal V1 using adjusted close and daily indicators.

    Scoring rules are deterministic:
    - +2 / -2 for adjusted close above/below MA50.
    - +1 / -1 for MA20 above/below MA50.
    - +1 / -1 for RSI14 above 55 / below 45.
    - +1 / -1 for MACD above/below MACD signal.
    - score >= 3 -> BULLISH, score <= -3 -> BEARISH, otherwise NEUTRAL.
    - missing required columns/data -> NO_DECISION with structured reason codes.
    """
    missing_eod = sorted(REQUIRED_EOD_COLUMNS - set(eod_frame.columns))
    missing_indicators = sorted(
        REQUIRED_INDICATOR_COLUMNS - set(indicators_frame.columns)
    )
    if missing_eod or missing_indicators:
        return SignalResult(
            signal=MarketSignal.NO_DECISION,
            price=None,
            signal_date=None,
            reason_codes=[
                *(f"MISSING_EOD_COLUMN_{column.upper()}" for column in missing_eod),
                *(
                    f"MISSING_INDICATOR_COLUMN_{column.upper()}"
                    for column in missing_indicators
                ),
            ],
            score=0,
        )

    eod = _prepare_frame(eod_frame, ["date", "ad_close"])
    indicators = _prepare_frame(indicators_frame, sorted(REQUIRED_INDICATOR_COLUMNS))
    merged = eod.merge(indicators, on="date", how="inner").sort_values("date")
    if merged.empty:
        return SignalResult(
            signal=MarketSignal.NO_DECISION,
            price=None,
            signal_date=None,
            reason_codes=["NO_OVERLAPPING_EOD_INDICATOR_DATES"],
            score=0,
        )

    latest = merged.iloc[-1]
    required_values = ["ad_close", "ma20", "ma50", "rsi14", "macd", "macd_signal"]
    missing_values = [column for column in required_values if pd.isna(latest[column])]
    signal_date = _format_date(latest["date"])
    price = None if pd.isna(latest["ad_close"]) else float(latest["ad_close"])
    if missing_values:
        return SignalResult(
            signal=MarketSignal.NO_DECISION,
            price=price,
            signal_date=signal_date,
            reason_codes=[
                f"MISSING_VALUE_{column.upper()}" for column in missing_values
            ],
            score=0,
        )

    score = 0
    reason_codes: list[str] = []

    ad_close = float(latest["ad_close"])
    ma20 = float(latest["ma20"])
    ma50 = float(latest["ma50"])
    rsi14 = float(latest["rsi14"])
    macd = float(latest["macd"])
    macd_signal = float(latest["macd_signal"])

    if ad_close > ma50:
        score += 2
        reason_codes.append("PRICE_ABOVE_MA50")
    elif ad_close < ma50:
        score -= 2
        reason_codes.append("PRICE_BELOW_MA50")
    else:
        reason_codes.append("PRICE_EQUALS_MA50")

    if ma20 > ma50:
        score += 1
        reason_codes.append("MA20_ABOVE_MA50")
    elif ma20 < ma50:
        score -= 1
        reason_codes.append("MA20_BELOW_MA50")
    else:
        reason_codes.append("MA20_EQUALS_MA50")

    if rsi14 > 55:
        score += 1
        reason_codes.append("RSI14_ABOVE_55")
    elif rsi14 < 45:
        score -= 1
        reason_codes.append("RSI14_BELOW_45")
    else:
        reason_codes.append("RSI14_NEUTRAL")

    if macd > macd_signal:
        score += 1
        reason_codes.append("MACD_ABOVE_SIGNAL")
    elif macd < macd_signal:
        score -= 1
        reason_codes.append("MACD_BELOW_SIGNAL")
    else:
        reason_codes.append("MACD_EQUALS_SIGNAL")

    if score >= BULLISH_THRESHOLD:
        signal = MarketSignal.BULLISH
    elif score <= BEARISH_THRESHOLD:
        signal = MarketSignal.BEARISH
    else:
        signal = MarketSignal.NEUTRAL

    reason_codes.append(f"SCORE_{score}")
    return SignalResult(
        signal=signal,
        price=ad_close,
        signal_date=signal_date,
        reason_codes=reason_codes,
        score=score,
    )


def calculate_ichimoku_v1(
    eod_frame: pd.DataFrame,
    indicators_frame: pd.DataFrame,
) -> SignalResult:
    """Score current, non-projected Ichimoku conditions without using Chikou."""
    missing_eod = sorted(REQUIRED_EOD_COLUMNS - set(eod_frame.columns))
    missing_indicators = sorted(
        REQUIRED_ICHIMOKU_COLUMNS - set(indicators_frame.columns)
    )
    if missing_eod or missing_indicators:
        return SignalResult(
            signal=MarketSignal.NO_DECISION,
            price=None,
            signal_date=None,
            reason_codes=[
                *(f"MISSING_EOD_COLUMN_{column.upper()}" for column in missing_eod),
                *(
                    f"MISSING_INDICATOR_COLUMN_{column.upper()}"
                    for column in missing_indicators
                ),
            ],
            score=0,
            strategy=ICHIMOKU_V1,
        )

    eod = _prepare_frame(eod_frame, ["date", "ad_close"])
    indicators = _prepare_frame(indicators_frame, sorted(REQUIRED_ICHIMOKU_COLUMNS))
    merged = eod.merge(indicators, on="date", how="inner").sort_values("date")
    if merged.empty:
        return SignalResult(
            MarketSignal.NO_DECISION,
            None,
            None,
            ["NO_OVERLAPPING_EOD_INDICATOR_DATES"],
            0,
            ICHIMOKU_V1,
        )

    latest = merged.iloc[-1]
    value_columns = [
        "ad_close",
        "ichimoku_tenkan",
        "ichimoku_kijun",
        "ichimoku_span_a",
        "ichimoku_span_b",
    ]
    signal_date = _format_date(latest["date"])
    price = None if pd.isna(latest["ad_close"]) else float(latest["ad_close"])
    missing_values = [column for column in value_columns if pd.isna(latest[column])]
    if missing_values:
        return SignalResult(
            MarketSignal.NO_DECISION,
            price,
            signal_date,
            [f"MISSING_VALUE_{column.upper()}" for column in missing_values],
            0,
            ICHIMOKU_V1,
        )

    price_value = float(latest["ad_close"])
    tenkan = float(latest["ichimoku_tenkan"])
    kijun = float(latest["ichimoku_kijun"])
    span_a = float(latest["ichimoku_span_a"])
    span_b = float(latest["ichimoku_span_b"])
    cloud_top, cloud_bottom = max(span_a, span_b), min(span_a, span_b)
    score = 0
    reason_codes: list[str] = []

    if price_value > cloud_top:
        score += 2
        reason_codes.append("PRICE_ABOVE_CLOUD")
    elif price_value < cloud_bottom:
        score -= 2
        reason_codes.append("PRICE_BELOW_CLOUD")
    else:
        reason_codes.append("PRICE_INSIDE_CLOUD")

    if tenkan > kijun:
        score += 1
        reason_codes.append("TENKAN_ABOVE_KIJUN")
    elif tenkan < kijun:
        score -= 1
        reason_codes.append("TENKAN_BELOW_KIJUN")
    else:
        reason_codes.append("TENKAN_EQUALS_KIJUN")

    if span_a > span_b:
        score += 1
        reason_codes.append("SPAN_A_ABOVE_SPAN_B")
    elif span_a < span_b:
        score -= 1
        reason_codes.append("SPAN_A_BELOW_SPAN_B")
    else:
        reason_codes.append("SPAN_A_EQUALS_SPAN_B")

    signal = (
        MarketSignal.BULLISH
        if score >= BULLISH_THRESHOLD
        else MarketSignal.BEARISH
        if score <= BEARISH_THRESHOLD
        else MarketSignal.NEUTRAL
    )
    reason_codes.append(f"SCORE_{score}")
    return SignalResult(
        signal,
        price_value,
        signal_date,
        reason_codes,
        score,
        ICHIMOKU_V1,
    )


def calculate_confirmed_trend_equals(
    trend_momentum: SignalComponent,
    ichimoku: SignalComponent,
    expected_signal_date: str | None = None,
) -> SignalResult:
    """Combine the two fixed component strategies using an equal vote."""
    components = [trend_momentum, ichimoku]
    expected_strategies = {TREND_MOMENTUM_V1, ICHIMOKU_V1}
    actual_strategies = {component.result.strategy for component in components}
    invalid_reasons: list[str] = []

    if actual_strategies != expected_strategies:
        invalid_reasons.append("INVALID_COMPONENT_STRATEGIES")
    if len({component.symbol_key for component in components}) != 1:
        invalid_reasons.append("COMPONENT_SYMBOL_MISMATCH")
    if len({component.timeframe for component in components}) != 1:
        invalid_reasons.append("COMPONENT_TIMEFRAME_MISMATCH")
    if len({component.result.signal_date for component in components}) != 1:
        invalid_reasons.append("COMPONENT_DATE_MISMATCH")
    elif expected_signal_date is not None and any(
        component.result.signal_date != expected_signal_date for component in components
    ):
        invalid_reasons.append("STALE_COMPONENT_DATE")
    if any(
        component.result.signal == MarketSignal.NO_DECISION for component in components
    ):
        invalid_reasons.append("COMPONENT_NO_DECISION")
    if any(component.result.signal_date is None for component in components):
        invalid_reasons.append("MISSING_COMPONENT_DATE")

    mapped_values = {
        MarketSignal.BULLISH: 1,
        MarketSignal.NEUTRAL: 0,
        MarketSignal.BEARISH: -1,
    }
    component_details = [
        {
            "strategy": component.result.strategy,
            "signal": component.result.signal.value,
            "mappedValue": mapped_values.get(component.result.signal),
            "score": component.result.score,
            "signalDate": component.result.signal_date,
            "reasonCodes": component.result.reason_codes,
            "modelVersion": component.result.model_version,
            "inputVersions": component.result.input_versions,
        }
        for component in components
    ]
    if invalid_reasons:
        return SignalResult(
            MarketSignal.NO_DECISION,
            None,
            None,
            invalid_reasons,
            0.0,
            CONFIRMED_TREND_EQUALS,
            CONFIRMED_TREND_EQUALS_MODEL_VERSION,
            component_details,
        )

    score = sum(mapped_values[component.result.signal] for component in components) / 2
    signal = (
        MarketSignal.BULLISH
        if score >= 0.5
        else MarketSignal.BEARISH
        if score <= -0.5
        else MarketSignal.NEUTRAL
    )
    prices = [component.result.price for component in components]
    price = prices[0] if prices[0] == prices[1] else None
    return SignalResult(
        signal,
        price,
        trend_momentum.result.signal_date,
        [
            f"{TREND_MOMENTUM_V1}_{trend_momentum.result.signal.value}",
            f"{ICHIMOKU_V1}_{ichimoku.result.signal.value}",
            f"EQUAL_VOTE_SCORE_{score:g}",
        ],
        score,
        CONFIRMED_TREND_EQUALS,
        CONFIRMED_TREND_EQUALS_MODEL_VERSION,
        component_details,
    )


def calculate_confirmed_trend_equals_v2(
    daily_candidate: SignalResult,
    intraday_confirmation: Any,
) -> SignalResult:
    """Confirm or suppress a daily candidate using exact-date intraday evidence."""
    from app.signals.intraday_confirmation import IntradayConfirmation

    intraday_metadata = intraday_confirmation.to_metadata()
    input_versions = {
        "trendMomentum": _component_version(daily_candidate, TREND_MOMENTUM_V1),
        "ichimoku": _component_version(daily_candidate, ICHIMOKU_V1),
    }
    facts = intraday_confirmation.facts
    if facts is not None:
        input_versions["intraday"] = {
            "dataset": facts.dataset,
            "dataVersion": facts.data_version,
        }

    reasons = [*daily_candidate.reason_codes, *intraday_confirmation.reason_codes]
    if daily_candidate.signal == MarketSignal.NO_DECISION:
        final_signal = MarketSignal.NO_DECISION
        reasons.append("DAILY_CANDIDATE_UNAVAILABLE")
    elif daily_candidate.signal == MarketSignal.NEUTRAL:
        final_signal = MarketSignal.NEUTRAL
        reasons.append("INTRADAY_CANNOT_PROMOTE_NEUTRAL")
    elif intraday_confirmation.result == IntradayConfirmation.UNAVAILABLE:
        final_signal = MarketSignal.NO_DECISION
        reasons.append("INTRADAY_UNAVAILABLE_BLOCKS_DIRECTIONAL_CANDIDATE")
    elif (
        daily_candidate.signal == MarketSignal.BULLISH
        and intraday_confirmation.result == IntradayConfirmation.BULLISH_CONFIRM
    ) or (
        daily_candidate.signal == MarketSignal.BEARISH
        and intraday_confirmation.result == IntradayConfirmation.BEARISH_CONFIRM
    ):
        final_signal = daily_candidate.signal
        reasons.append("INTRADAY_CONFIRMED_DAILY_CANDIDATE")
    else:
        final_signal = MarketSignal.NEUTRAL
        reasons.append("INTRADAY_SUPPRESSED_DAILY_CANDIDATE")

    return SignalResult(
        signal=final_signal,
        price=daily_candidate.price,
        signal_date=daily_candidate.signal_date,
        reason_codes=reasons,
        score=daily_candidate.score,
        strategy=CONFIRMED_TREND_EQUALS,
        model_version=CONFIRMED_TREND_EQUALS_V2_MODEL_VERSION,
        components=daily_candidate.components,
        input_versions=input_versions,
        intraday_confirmation=intraday_metadata,
    )


def _component_version(result: SignalResult, strategy: str) -> dict[str, Any]:
    component = next(
        (item for item in result.components or [] if item.get("strategy") == strategy),
        {},
    )
    return {
        "modelVersion": component.get("modelVersion"),
        **(component.get("inputVersions") or {}),
    }


def _prepare_frame(frame: pd.DataFrame, columns: list[str]) -> pd.DataFrame:
    prepared = frame.loc[:, columns].copy()
    prepared["date"] = pd.to_datetime(prepared["date"])
    return prepared.drop_duplicates(subset=["date"], keep="last")


def _format_date(value: Any) -> str:
    return pd.Timestamp(value).date().isoformat()
