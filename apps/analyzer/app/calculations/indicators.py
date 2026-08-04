import logging

import pandas as pd
import pandas_ta_classic as ta
from py_common.config import SchedulerSettings

_logger = logging.getLogger(__name__)

REQUIRED_EOD_COLUMNS = {"date", "ad_open", "ad_high", "ad_low", "ad_close", "nm_volume"}
ICHIMOKU_LIBRARY_COLUMNS = {
    "ISA_9": "ichimoku_span_a",
    "ISB_26": "ichimoku_span_b",
    "ITS_9": "ichimoku_tenkan",
    "IKS_26": "ichimoku_kijun",
    "ICS_26": "ichimoku_chikou",
}
ICHIMOKU_EXPECTED_COLUMNS = set(ICHIMOKU_LIBRARY_COLUMNS)
INDICATOR_OUTPUT_COLUMNS = {
    "MA20": ["ma20", "ma20_calculated_at"],
    "MA50": ["ma50", "ma50_calculated_at"],
    "RSI14": ["rsi14", "rsi14_calculated_at"],
    "MACD": ["macd", "macd_signal", "macd_hist", "macd_calculated_at"],
    "ICHIMOKU": [
        "ichimoku_tenkan",
        "ichimoku_kijun",
        "ichimoku_span_a",
        "ichimoku_span_b",
        "ichimoku_chikou",
        "ichimoku_calculated_at",
    ],
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
    numeric_columns = ["ad_open", "ad_high", "ad_low", "ad_close", indicator_source]
    for column in dict.fromkeys(numeric_columns):
        prepared[column] = pd.to_numeric(prepared[column], errors="coerce")
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
    """Calculate the requested supported daily indicators.

    Notes:
        ichimoku_chikou is retained for chart visualization only. It is
        chart-shifted by the library and must not be used directly in signal
        generation or backtesting.
    """
    if scheduler is None:
        raise ValueError("Scheduler settings are required for indicator calculation")

    prepared = prepare_eod_frame(dataframe, indicator_source)
    source = prepared[indicator_source]

    result = pd.DataFrame({"date": prepared["date"]})
    calculated_at = pd.Timestamp.now(tz=scheduler.zone)

    for indicator in indicators:
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
        elif indicator == "ICHIMOKU":
            ichimoku, future_cloud = ta.ichimoku(
                prepared["ad_high"],
                prepared["ad_low"],
                source,
                tenkan=9,
                kijun=26,
                senkou=52,
            )
            # future_cloud intentionally contains projected rows beyond the
            # current EOD dataset. MVP Parquet output keeps the source shape.
            del future_cloud
            if ichimoku is None:
                _logger.warning(
                    "Insufficient EOD rows for Ichimoku calculation: rows=%s",
                    len(prepared),
                )
                normalized = pd.DataFrame(index=prepared.index)
            else:
                missing_ichimoku_columns = ICHIMOKU_EXPECTED_COLUMNS - set(
                    ichimoku.columns
                )
                if missing_ichimoku_columns:
                    missing_list = ", ".join(sorted(missing_ichimoku_columns))
                    raise ValueError(
                        "Ichimoku library output is missing expected columns: "
                        f"{missing_list}"
                    )
                normalized = ichimoku.rename(columns=ICHIMOKU_LIBRARY_COLUMNS)
            for column in INDICATOR_OUTPUT_COLUMNS["ICHIMOKU"][:-1]:
                result[column] = normalized.get(
                    column,
                    pd.Series(pd.NA, index=prepared.index),
                )
            result["ichimoku_calculated_at"] = calculated_at

    output_columns = ["date"]
    for indicator in indicators:
        output_columns.extend(INDICATOR_OUTPUT_COLUMNS[indicator])

    return result[output_columns]
