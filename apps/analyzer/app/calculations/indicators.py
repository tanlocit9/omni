import pandas as pd

REQUIRED_EOD_COLUMNS = {"date", "open", "high", "low", "close", "nmVolume"}
INDICATOR_OUTPUT_COLUMNS = [
    "date",
    "ma20",
    "ma50",
    "rsi14",
    "macd",
    "macd_signal",
    "macd_hist",
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


def calculate_supported_indicators(dataframe: pd.DataFrame) -> pd.DataFrame:
    """Calculate the complete v1 supported daily indicator set."""
    prepared = prepare_eod_frame(dataframe)
    close = prepared["close"]

    result = pd.DataFrame({"date": prepared["date"]})
    result["ma20"] = close.rolling(window=20, min_periods=20).mean()
    result["ma50"] = close.rolling(window=50, min_periods=50).mean()
    result["rsi14"] = _wilder_rsi(close, period=14)

    macd, macd_signal, macd_hist = _macd(close)
    result["macd"] = macd
    result["macd_signal"] = macd_signal
    result["macd_hist"] = macd_hist

    return result[INDICATOR_OUTPUT_COLUMNS]


def _wilder_rsi(close: pd.Series, period: int) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0)
    loss = -delta.clip(upper=0)

    avg_gain = gain.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()
    avg_loss = loss.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()

    rs = avg_gain / avg_loss
    rsi = 100 - (100 / (1 + rs))
    rsi = rsi.mask(avg_loss == 0, 100)
    rsi = rsi.mask(avg_gain == 0, 0)
    return rsi


def _macd(
    close: pd.Series,
    fast_period: int = 12,
    slow_period: int = 26,
    signal_period: int = 9,
) -> tuple[pd.Series, pd.Series, pd.Series]:
    fast = close.ewm(span=fast_period, adjust=False, min_periods=fast_period).mean()
    slow = close.ewm(span=slow_period, adjust=False, min_periods=slow_period).mean()
    macd = fast - slow
    signal = macd.ewm(
        span=signal_period,
        adjust=False,
        min_periods=signal_period,
    ).mean()
    hist = macd - signal
    return macd, signal, hist
