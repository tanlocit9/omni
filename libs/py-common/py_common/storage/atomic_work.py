"""Day cutoff and history-preserving writes for atomic work, not a writer service."""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd


def cutoff_frame(
    frame: pd.DataFrame,
    business_date: date | None,
    *,
    column: str = "date",
    require_day: bool = False,
) -> pd.DataFrame:
    if business_date is None:
        return frame
    if column not in frame.columns:
        raise ValueError(f"Atomic input requires {column}")
    dates = pd.to_datetime(frame[column], errors="raise").dt.date
    if dates.isna().any():
        raise ValueError("Atomic input contains missing dates")
    if require_day and not dates.eq(business_date).any():
        raise ValueError(f"Input does not cover business date {business_date}")
    return frame.loc[dates <= business_date].copy()


def day_rows(
    frame: pd.DataFrame,
    business_date: date | None,
    *,
    column: str = "date",
) -> pd.DataFrame:
    if business_date is None:
        return frame
    if column not in frame.columns:
        raise ValueError(f"Atomic output requires {column}")
    dates = pd.to_datetime(frame[column], errors="raise").dt.date
    selected = frame.loc[dates == business_date].copy()
    if selected.empty:
        raise ValueError(f"Calculation produced no output for {business_date}")
    return selected


async def write_day(
    storage: Any,
    path: str,
    frame: pd.DataFrame,
    business_date: date | None,
    *,
    column: str = "date",
    **kwargs: Any,
) -> None:
    if business_date is not None:
        frame = day_rows(frame, business_date, column=column)
        existing = await storage.read_optional_dataframe(path)
        if existing is not None and not existing.empty:
            if column not in existing:
                raise ValueError("Existing output has no date identity")
            dates = pd.to_datetime(existing[column], errors="raise").dt.date
            if dates.isna().any():
                raise ValueError("Existing output contains missing dates")
            frame = pd.concat(
                [existing.loc[dates != business_date], frame], ignore_index=True
            ).sort_values(column)
    await storage.write_dataframe(path, frame, **kwargs)
