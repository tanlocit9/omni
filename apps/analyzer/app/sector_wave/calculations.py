from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

import pandas as pd
import pyarrow as pa

RETURN_WINDOWS = (1, 5, 10, 20)
FORWARD_WINDOWS = (5, 10, 15, 20)
SYMBOL_FEATURE_COLUMNS = [
    "date",
    "symbol_key",
    "exchange",
    "symbol",
    "close",
    "volume",
    "return_1d",
    "return_5d",
    "return_10d",
    "return_20d",
    "ma20",
    "volume_ratio",
    "above_ma20",
    "forward_return_t5",
    "forward_return_t10",
    "forward_return_t15",
    "forward_return_t20",
]
SECTOR_FEATURE_COLUMNS = [
    "date",
    "sector_code",
    "sector_level",
    "sector_index",
    "sector_return",
    "ma20",
    "relative_strength",
    "volume_ratio",
    "breadth_above_ma20",
    "coverage_ratio",
    "top_3_contribution_share",
    "contributors",
    "contributors_json",
]
SECTOR_CONTRIBUTORS_TYPE = pa.list_(
    pa.struct(
        [
            pa.field("symbol", pa.string()),
            pa.field("weight", pa.float64()),
            pa.field("return", pa.float64()),
            pa.field("contribution", pa.float64()),
            pa.field("contribution_share", pa.float64()),
            pa.field("above_ma20", pa.bool_()),
        ]
    )
)
SECTOR_FEATURES_SCHEMA = pa.schema(
    [
        pa.field("date", pa.timestamp("ns")),
        pa.field("sector_code", pa.string()),
        pa.field("sector_level", pa.int64()),
        pa.field("sector_index", pa.float64()),
        pa.field("sector_return", pa.float64()),
        pa.field("ma20", pa.float64()),
        pa.field("relative_strength", pa.float64()),
        pa.field("volume_ratio", pa.float64()),
        pa.field("breadth_above_ma20", pa.float64()),
        pa.field("coverage_ratio", pa.float64()),
        pa.field("top_3_contribution_share", pa.float64()),
        pa.field("contributors", SECTOR_CONTRIBUTORS_TYPE),
        pa.field("contributors_json", pa.string()),
    ]
)
BACKTEST_COLUMNS = [
    "date",
    "strategy",
    "sector_level",
    "sector_code",
    "rank",
    "sector_return",
    "relative_strength",
    "sector_wave",
    "previous_sector_code",
    "transition",
    "lag_sessions",
    "forward_return_t5",
    "forward_return_t10",
    "forward_return_t15",
    "forward_return_t20",
]


@dataclass(frozen=True)
class SymbolMetadata:
    """Normalized symbol membership used to locate per-symbol feature files."""

    exchange: str
    code: str
    symbol_key: str
    sector_code: str


def calculate_symbol_features(
    eod_frame: pd.DataFrame,
    *,
    symbol_key: str,
    exchange: str,
    code: str,
) -> pd.DataFrame:
    """Calculate per-symbol features from one EOD price series.

    The input is normalized from Omni's adjusted EOD columns, then all return
    windows are computed with trading-session offsets instead of calendar days.
    Forward returns are labels for later backtesting and intentionally look
    ahead by the configured number of available sessions.
    """
    frame = _normalize_eod_frame(eod_frame).copy()
    frame["symbol_key"] = symbol_key
    frame["exchange"] = exchange.upper()
    frame["symbol"] = code.upper()
    frame["ma20"] = frame["close"].rolling(window=20, min_periods=20).mean()
    volume_ma20 = frame["volume"].rolling(window=20, min_periods=20).mean()
    frame["volume_ratio"] = frame["volume"] / volume_ma20.replace({0: pd.NA})
    frame["above_ma20"] = frame["close"] > frame["ma20"]

    for window in RETURN_WINDOWS:
        frame[f"return_{window}d"] = frame["close"].pct_change(periods=window)
    for window in FORWARD_WINDOWS:
        frame[f"forward_return_t{window}"] = (
            frame["close"].shift(-window) / frame["close"] - 1
        )

    return frame[SYMBOL_FEATURE_COLUMNS]


def aggregate_sector_features(
    symbol_frames: list[pd.DataFrame],
    *,
    sector_code: str,
    sector_level: int,
    market_frame: pd.DataFrame | None = None,
) -> pd.DataFrame:
    """Aggregate multiple symbol feature frames into one sector feature frame.

    Each trading date is calculated independently with equal member weights.
    The nested ``contributors`` value preserves analytical structure, while
    ``contributors_json`` mirrors it as a string for Parquet tools such as
    DBeaver that do not display nested LIST/STRUCT fields well.
    """
    if not symbol_frames:
        return pd.DataFrame(columns=SECTOR_FEATURE_COLUMNS)

    frame = pd.concat(symbol_frames, ignore_index=True)
    frame = frame.copy()
    frame["date"] = pd.to_datetime(frame["date"])
    frame = frame.sort_values(["date", "symbol"])
    member_count = frame["symbol"].nunique()
    rows: list[dict[str, Any]] = []

    for date, group in frame.groupby("date", sort=True):
        valid_returns = group.dropna(subset=["return_1d"])
        coverage_ratio = 0.0 if member_count == 0 else len(group) / member_count
        weight = 0.0 if group.empty else 1.0 / len(group)
        sector_return = (
            float(valid_returns["return_1d"].mean())
            if not valid_returns.empty
            else pd.NA
        )
        contributors = _build_contributors(group, weight)
        total_contribution = sum(abs(item["contribution"]) for item in contributors)
        top_3_contribution = sum(abs(item["contribution"]) for item in contributors[:3])
        rows.append(
            {
                "date": date,
                "sector_code": sector_code.upper(),
                "sector_level": sector_level,
                "sector_return": sector_return,
                "volume_ratio": _safe_mean(group.get("volume_ratio")),
                "breadth_above_ma20": _safe_mean(group.get("above_ma20")),
                "coverage_ratio": coverage_ratio,
                "top_3_contribution_share": (
                    top_3_contribution / total_contribution
                    if total_contribution
                    else 0.0
                ),
                "contributors": contributors,
                "contributors_json": json.dumps(contributors, separators=(",", ":")),
            }
        )

    result = pd.DataFrame(rows)
    result["sector_index"] = (1 + result["sector_return"].fillna(0.0)).cumprod() * 100.0
    result["ma20"] = result["sector_index"].rolling(window=20, min_periods=20).mean()
    market = _normalize_market_frame(market_frame)
    if market is not None:
        result = result.merge(market, on="date", how="left")
        result["relative_strength"] = result["sector_return"] - result["market_return"]
        result = result.drop(columns=["market_return"])
    else:
        result["relative_strength"] = result["sector_return"]
    return result[SECTOR_FEATURE_COLUMNS]


def calculate_sector_rotation_backtest(
    sector_frames: list[pd.DataFrame],
    *,
    strategy: str,
    sector_level: int,
) -> pd.DataFrame:
    """Build the rule-based Sector Wave V1 rotation backtest table.

    Sectors are ranked by daily relative strength. The daily winner becomes the
    simulated holding, with transition metadata, lag sessions since the previous
    same-sector win, and forward realized returns for each configured window.
    """
    if not sector_frames:
        return pd.DataFrame(columns=BACKTEST_COLUMNS)

    frame = pd.concat(sector_frames, ignore_index=True).copy()
    frame["date"] = pd.to_datetime(frame["date"])
    frame = frame.sort_values(["date", "sector_code"])
    frame["rank"] = frame.groupby("date")["relative_strength"].rank(
        method="first", ascending=False
    )
    frame["sector_wave"] = frame.apply(_classify_sector_wave, axis=1)
    winners = frame[frame["rank"] == 1].sort_values("date").copy()
    winners["previous_sector_code"] = winners["sector_code"].shift(1)
    winners["transition"] = (
        winners["previous_sector_code"].fillna("") + "->" + winners["sector_code"]
    )
    winners.loc[winners["previous_sector_code"].isna(), "transition"] = pd.NA
    winners["lag_sessions"] = _calculate_lag_sessions(winners["sector_code"])

    sector_returns = frame.pivot(
        index="date", columns="sector_code", values="sector_return"
    )
    for window in FORWARD_WINDOWS:
        winners[f"forward_return_t{window}"] = [
            _forward_return(sector_returns, row.date, row.sector_code, window)
            for row in winners.itertuples(index=False)
        ]

    winners["strategy"] = strategy.upper()
    winners["sector_level"] = sector_level
    return winners[BACKTEST_COLUMNS]


def filter_symbols_for_sector(
    symbols_frame: pd.DataFrame,
    *,
    sector_code: str,
    sector_level: int,
) -> list[SymbolMetadata]:
    """Resolve sector members from symbols Parquet metadata.

    The platform scheduler selects sector jobs, but the analyzer derives the
    concrete symbol list from existing symbols Parquet snapshots. This keeps the
    analyzer storage-driven and avoids needing platform DB access.
    """
    sector_column = f"sectorLv{sector_level}Code"
    if sector_column not in symbols_frame.columns:
        raise ValueError(f"Symbols metadata missing {sector_column}")
    rows = symbols_frame[
        symbols_frame[sector_column].astype(str).str.upper() == sector_code.upper()
    ]
    metadata: list[SymbolMetadata] = []
    for row in rows.itertuples(index=False):
        exchange = _first_present(row, ("exchange", "floor", "market"))
        code = _first_present(row, ("code", "symbol", "ticker"))
        if exchange and code:
            metadata.append(
                SymbolMetadata(
                    exchange=str(exchange).upper(),
                    code=str(code).upper(),
                    symbol_key=f"{str(exchange).upper()}-{str(code).upper()}",
                    sector_code=sector_code.upper(),
                )
            )
    return sorted(metadata, key=lambda item: item.symbol_key)


def _normalize_eod_frame(frame: pd.DataFrame) -> pd.DataFrame:
    """Normalize adjusted EOD data into sorted ``date/close/volume`` rows."""
    close_column = _find_column(frame, ("ad_close",))
    volume_column = _find_column(frame, ("nm_volume",), required=False)
    if "date" not in frame.columns:
        raise ValueError("EOD frame must contain date")
    result = pd.DataFrame(
        {
            "date": pd.to_datetime(frame["date"]),
            "close": pd.to_numeric(frame[close_column], errors="coerce"),
            "volume": (
                pd.to_numeric(frame[volume_column], errors="coerce")
                if volume_column
                else 0.0
            ),
        }
    )
    result = result.dropna(subset=["date", "close"])
    result = result.sort_values("date").drop_duplicates("date", keep="last")
    return result.reset_index(drop=True)


def _normalize_market_frame(frame: pd.DataFrame | None) -> pd.DataFrame | None:
    """Normalize optional market benchmark data and derive daily returns."""
    if frame is None or frame.empty:
        return None
    market = _normalize_eod_frame(frame)
    market["market_return"] = market["close"].pct_change()
    return market[["date", "market_return"]]


def _build_contributors(group: pd.DataFrame, weight: float) -> list[dict[str, Any]]:
    """Build sorted per-symbol contribution details for one sector/date group."""
    contributors: list[dict[str, Any]] = []
    for row in group.itertuples(index=False):
        symbol_return = _to_float(getattr(row, "return_1d", pd.NA))
        contribution = weight * (symbol_return or 0.0)
        contributors.append(
            {
                "symbol": str(row.symbol),
                "weight": weight,
                "return": symbol_return,
                "contribution": contribution,
                "contribution_share": 0.0,
                "above_ma20": bool(getattr(row, "above_ma20", False)),
            }
        )
    total = sum(abs(item["contribution"]) for item in contributors)
    for item in contributors:
        item["contribution_share"] = abs(item["contribution"]) / total if total else 0.0
    return sorted(
        contributors, key=lambda item: (-item["contribution"], item["symbol"])
    )


def _classify_sector_wave(row: pd.Series) -> str:
    """Classify one sector-date row into the Sector Wave quadrant label."""
    relative_strength = _to_float(row.get("relative_strength")) or 0.0
    sector_return = _to_float(row.get("sector_return")) or 0.0
    above_ma20 = bool(row.get("sector_index", 0) > row.get("ma20", float("inf")))
    if relative_strength > 0 and sector_return > 0 and above_ma20:
        return "LEADING"
    if relative_strength > 0:
        return "IMPROVING"
    if sector_return < 0:
        return "WEAKENING"
    return "LAGGING"


def _calculate_lag_sessions(values: pd.Series) -> list[int]:
    """Count sessions since the same sector last appeared in the winner stream."""
    lags: list[int] = []
    last_seen: dict[str, int] = {}
    for position, sector_code in enumerate(values):
        previous = last_seen.get(sector_code)
        lags.append(0 if previous is None else position - previous)
        last_seen[sector_code] = position
    return lags


def _forward_return(
    sector_returns: pd.DataFrame,
    date: pd.Timestamp,
    sector_code: str,
    window: int,
) -> float | None:
    """Calculate compounded future return for one sector after ``date``."""
    dates = list(sector_returns.index)
    try:
        start = dates.index(date)
    except ValueError:
        return None
    end = start + window
    if end >= len(dates) or sector_code not in sector_returns.columns:
        return None
    values = sector_returns.iloc[start + 1 : end + 1][sector_code].dropna()
    if len(values) < window:
        return None
    return float((1 + values).prod() - 1)


def _safe_mean(series: pd.Series | None) -> float | None:
    """Return a numeric mean or ``None`` when the series has no valid values."""
    if series is None:
        return None
    numeric = pd.to_numeric(series, errors="coerce")
    if numeric.dropna().empty:
        return None
    return float(numeric.mean())


def _to_float(value: Any) -> float | None:
    """Convert scalar-like values to ``float`` while preserving missing values."""
    if pd.isna(value):
        return None
    return float(value)


def _find_column(
    frame: pd.DataFrame,
    candidates: tuple[str, ...],
    *,
    required: bool = True,
) -> str | None:
    """Find the first matching column name from a prioritized candidate list."""
    for candidate in candidates:
        if candidate in frame.columns:
            return candidate
    if required:
        raise ValueError(
            f"Missing required column; expected one of {', '.join(candidates)}"
        )
    return None


def _first_present(row: Any, names: tuple[str, ...]) -> Any:
    """Return the first non-empty attribute from a namedtuple-like row object."""
    for name in names:
        if hasattr(row, name):
            value = getattr(row, name)
            if not pd.isna(value) and str(value).strip():
                return value
    return None
