import numpy as np
import pandas as pd
from py_common.config import SchedulerSettings

REQUIRED_EOD_COLUMNS = {"date", "open", "high", "low", "close", "nmVolume"}
INDICATOR_OUTPUT_COLUMNS = [
    "date",
    "ma20",
    "ma50",
    "rsi14",
    "macd",
    "macd_signal",
    "macd_hist",
    "calculatedAt",
]


def prepare_eod_frame(dataframe: pd.DataFrame) -> pd.DataFrame:
    """Validate, sort, and deduplicate EOD data before calculation."""
    missing = REQUIRED_EOD_COLUMNS - set(dataframe.columns)
    if missing:
        missing_list = ", ".join(sorted(missing))
        raise ValueError(f"EOD data is missing required columns: {missing_list}")

    prepared = dataframe.copy()
    prepared["date"] = pd.to_datetime(prepared["date"])
    prepared = prepared.sort_values("date").drop_duplicates("date", keep="last")
    prepared["close"] = pd.to_numeric(prepared["close"], errors="coerce")
    if prepared["close"].isna().all():
        raise ValueError("EOD close column has no numeric values")
    return prepared.reset_index(drop=True)


def validate_indicator_source(
    dataframe: pd.DataFrame, indicator_source: str
) -> pd.Series:
    """Validate and return the numeric source series used for indicator calculation."""
    if indicator_source not in dataframe.columns:
        raise ValueError(
            f"Indicator source column is missing from EOD data: {indicator_source}"
        )

    source = pd.to_numeric(dataframe[indicator_source], errors="coerce")
    if source.isna().all():
        raise ValueError(
            f"Indicator source column has no numeric values: {indicator_source}"
        )

    return source


def calculate_supported_indicators(
    dataframe: pd.DataFrame,
    indicator_source: str,
    scheduler: SchedulerSettings | None = None,
) -> pd.DataFrame:
    """Calculate the complete v1 supported daily indicator set."""
    prepared = prepare_eod_frame(dataframe)
    source = validate_indicator_source(prepared, indicator_source)

    result = pd.DataFrame({"date": prepared["date"]})
    result["ma20"] = source.rolling(window=20, min_periods=20).mean()
    result["ma50"] = source.rolling(window=50, min_periods=50).mean()
    result["rsi14"] = _wilder_rsi(source, period=14)

    macd, macd_signal, macd_hist = _macd(source)
    result["macd"] = macd
    result["macd_signal"] = macd_signal
    result["macd_hist"] = macd_hist

    scheduler_settings = scheduler
    result["calculatedAt"] = pd.Timestamp.now(tz=scheduler_settings.zone)

    return result[INDICATOR_OUTPUT_COLUMNS]


def _sma_seeded_smoothing(values: pd.Series, period: int, alpha: float) -> pd.Series:
    """
    Recursive smoothing seeded by the SMA of the first `period` values.

    seed        = SMA(values[0:period])
    out[t]      = out[t-1] * (1 - alpha) + values[t] * alpha,  for t >= period

    Matches the seeding convention used by TradingView / stockcharts / most
    Vietnamese platforms (TCBS, Vietstock) for both Wilder's RSI smoothing
    (alpha = 1/period) and MACD's EMA components (alpha = 2/(period+1)).
    Rows before `period` are NaN (warm-up).
    """
    values = values.to_numpy(dtype="float64")
    n = len(values)
    out = np.full(n, np.nan)

    if n < period:
        return pd.Series(out)

    seed = np.nanmean(values[:period])
    out[period - 1] = seed
    prev = seed
    for t in range(period, n):
        prev = prev * (1 - alpha) + values[t] * alpha
        out[t] = prev

    return pd.Series(out)


def _wilder_rsi(close: pd.Series, period: int) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0).fillna(0.0)
    loss = (-delta.clip(upper=0)).fillna(0.0)

    alpha = 1 / period
    avg_gain = _sma_seeded_smoothing(gain, period, alpha)
    avg_loss = _sma_seeded_smoothing(loss, period, alpha)
    avg_gain.index = close.index
    avg_loss.index = close.index

    rs = avg_gain / avg_loss
    rsi = 100 - (100 / (1 + rs))

    # Flat-price edge case: no gains and no losses in the averaging window.
    # Convention: neutral RSI (50), not 0 or 100 — no directional pressure
    # either way. Applied only where both averages are exactly zero.
    flat = (avg_gain == 0) & (avg_loss == 0)
    rsi = rsi.mask(flat, 50.0)

    # Gains only, no losses at all -> RSI saturates at 100 (avg_loss == 0,
    # avg_gain > 0 gives rs = inf, which already yields 100 above; this
    # mask just guards the case where the division produces inf/-inf).
    rsi = rsi.replace([np.inf], 100.0)

    return rsi


def _macd(
    close: pd.Series,
    fast_period: int = 12,
    slow_period: int = 26,
    signal_period: int = 9,
) -> tuple[pd.Series, pd.Series, pd.Series]:
    fast_alpha = 2 / (fast_period + 1)
    slow_alpha = 2 / (slow_period + 1)
    signal_alpha = 2 / (signal_period + 1)

    fast = _sma_seeded_smoothing(close, fast_period, fast_alpha)
    slow = _sma_seeded_smoothing(close, slow_period, slow_alpha)
    fast.index = close.index
    slow.index = close.index

    macd = fast - slow

    # Signal line seeds off the first `signal_period` valid MACD values,
    # which only exist starting at index (slow_period - 1). Slice to that
    # valid tail before seeding, then reindex back onto the full series.
    macd_valid = macd.iloc[slow_period - 1 :].reset_index(drop=True)
    signal_on_valid = _sma_seeded_smoothing(macd_valid, signal_period, signal_alpha)
    signal = pd.Series(np.nan, index=macd.index)
    signal.iloc[slow_period - 1 :] = signal_on_valid.to_numpy()

    hist = macd - signal
    return macd, signal, hist
