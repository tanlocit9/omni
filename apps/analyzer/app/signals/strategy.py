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
BULLISH_THRESHOLD = 3
BEARISH_THRESHOLD = -3


class MarketSignal(StrEnum):
    BULLISH = "BULLISH"
    NEUTRAL = "NEUTRAL"
    BEARISH = "BEARISH"
    NO_DECISION = "NO_DECISION"


@dataclass(frozen=True)
class SignalResult:
    signal: MarketSignal
    price: float | None
    signal_date: str | None
    reason_codes: list[str]
    score: int
    strategy: str = TREND_MOMENTUM_V1

    def to_metadata(self) -> dict[str, Any]:
        return {
            "newSignal": self.signal.value,
            "price": self.price,
            "signalDate": self.signal_date,
            "reasonCodes": self.reason_codes,
            "score": self.score,
            "strategy": self.strategy,
        }


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


def _prepare_frame(frame: pd.DataFrame, columns: list[str]) -> pd.DataFrame:
    prepared = frame.loc[:, columns].copy()
    prepared["date"] = pd.to_datetime(prepared["date"])
    return prepared.drop_duplicates(subset=["date"], keep="last")


def _format_date(value: Any) -> str:
    return pd.Timestamp(value).date().isoformat()
