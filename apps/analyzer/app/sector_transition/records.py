"""Stable prediction/outcome identity and append-only domain merges.

These checks protect sequential replay; cross-process output exclusion belongs to
future writer work rather than this read/merge/write helper.
"""
from __future__ import annotations

import pandas as pd
from py_common.storage.date_contracts import BUSINESS_DATE_COLUMNS

TRANSITION_KEY = [
    "evaluation_date", "strategy", "timeframe", "sector_level",
    "from_sector", "to_sector", "horizon_sessions",
]


def normalized_rows(frame: pd.DataFrame, keys: list[str]) -> pd.DataFrame:
    result = frame.copy(deep=True)
    if result.empty:
        return result
    missing = set(keys) - set(result.columns)
    if missing:
        raise ValueError(f"Transition rows missing identity columns: {sorted(missing)}")
    for column in BUSINESS_DATE_COLUMNS.intersection(result.columns):
        result[column] = pd.to_datetime(result[column], errors="raise").dt.date
    if result[keys].isna().any().any():
        raise ValueError("Transition identity contains null values")
    for column in ("sector_level", "horizon_sessions"):
        if column in result:
            values = pd.to_numeric(result[column], errors="raise")
            if values.isna().any() or ((values <= 0) | (values % 1 != 0)).any():
                raise ValueError(f"Invalid transition identity: {column}")
            result[column] = values.astype("int64")
    if result.duplicated(keys).any():
        raise ValueError("Duplicate transition identity")
    return result


def missing_rows(
    candidates: pd.DataFrame, existing: pd.DataFrame | None, keys: list[str],
) -> pd.DataFrame:
    """Keep only identities not already durably evaluated."""
    candidates = normalized_rows(candidates, keys)
    if candidates.empty or existing is None or existing.empty:
        return candidates
    saved = normalized_rows(existing, keys)
    index = pd.MultiIndex.from_frame(saved[keys])
    return candidates.loc[~pd.MultiIndex.from_frame(candidates[keys]).isin(index)].copy()


def append_immutable_rows(
    existing: pd.DataFrame | None, incoming: pd.DataFrame, keys: list[str],
) -> tuple[pd.DataFrame, int]:
    """Identical replay is a no-op; conflicting values under an existing key fail."""
    incoming = normalized_rows(incoming, keys)
    if existing is None or existing.empty:
        return incoming, len(incoming)
    saved = normalized_rows(existing, keys)
    if incoming.empty:
        return saved, 0
    old_indexed = saved.set_index(keys)
    new_indexed = incoming.set_index(keys)
    common = [column for column in old_indexed.columns if column in new_indexed.columns]
    shared_keys = old_indexed.index.intersection(new_indexed.index)
    old_values = old_indexed.loc[shared_keys, common]
    new_values = new_indexed.loc[shared_keys, common]
    equal = old_values.eq(new_values) | (old_values.isna() & new_values.isna())
    if not equal.fillna(False).to_numpy().all():
        raise ValueError("Immutable transition rows conflict with stored values")
    additions = missing_rows(incoming, saved, keys)
    if additions.empty:
        return saved, 0
    return pd.concat([saved, additions], ignore_index=True), len(additions)
