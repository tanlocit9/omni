import logging

import pandas as pd
import pandas_ta_classic as ta
from py_common.config import SchedulerSettings

_logger = logging.getLogger(__name__)

REQUIRED_EOD_COLUMNS = {"date", "open", "high", "low", "close", "nmVolume"}
INDICATOR_OUTPUT_COLUMNS = {
    "MA20": ["ma20", "ma20_calculated_at"],
    "MA50": ["ma50", "ma50_calculated_at"],
    "RSI14": ["rsi14", "rsi14_calculated_at"],
    "MACD": ["macd", "macd_signal", "macd_hist", "macd_calculated_at"],
}


def prepare_eod_frame(dataframe: pd.DataFrame, indicator_source: str) -> pd.DataFrame:
    """Validate, sort, deduplicate, and coerce EOD data before calculation."""
    missing = REQUIRED_EOD_COLUMNS - set(dataframe.columns)
    if missing:
        missing_list = ", ".join(sorted(missing))
        raise ValueError(f"EOD data is missing required columns: {missing_list}")

    if indicator_source not in dataframe.columns:
        raise ValueError(
            f"Indicator source column is missing from EOD data: {indicator_source}"
        )

    prepared = dataframe.copy()
    prepared["date"] = pd.to_datetime(prepared["date"])
    prepared = prepared.sort_values("date").drop_duplicates("date", keep="last")
    prepared[indicator_source] = pd.to_numeric(
        prepared[indicator_source], errors="coerce"
    )
    if prepared[indicator_source].isna().all():
        raise ValueError(
            f"Indicator source column has no numeric values: {indicator_source}"
        )
    prepared = prepared.reset_index(drop=True)
    return prepared


def calculate_supported_indicators(
    dataframe: pd.DataFrame,
    indicator_source: str,
    indicators: list[str],
    scheduler: SchedulerSettings | None = None,
) -> pd.DataFrame:
    """Calculate the requested supported daily indicators."""
    prepared = prepare_eod_frame(dataframe, indicator_source)
    source = prepared[indicator_source]

    result = pd.DataFrame({"date": prepared["date"]})
    scheduler_settings = scheduler

    for indicator in indicators:
        calculated_at = pd.Timestamp.now(tz=scheduler_settings.zone)
        if indicator == "MA20":
            result["ma20"] = ta.sma(source, length=20)
            result["ma20_calculated_at"] = calculated_at
        elif indicator == "MA50":
            result["ma50"] = ta.sma(source, length=50)
            result["ma50_calculated_at"] = calculated_at
        elif indicator == "RSI14":
            result["rsi14"] = ta.rsi(source, length=14)
            result["rsi14_calculated_at"] = calculated_at
        elif indicator == "MACD":
            macd = ta.macd(source, fast=12, slow=26, signal=9)
            result["macd"] = macd["MACD_12_26_9"]
            result["macd_signal"] = macd["MACDs_12_26_9"]
            result["macd_hist"] = macd["MACDh_12_26_9"]
            result["macd_calculated_at"] = calculated_at

    output_columns = ["date"]
    for indicator in indicators:
        output_columns.extend(INDICATOR_OUTPUT_COLUMNS[indicator])

    return result[output_columns]
