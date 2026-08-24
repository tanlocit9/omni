"""Canonical logical date and timestamp contracts for analytical Parquet data.

Business dates are calendar values without a time zone.  Event timestamps are
absolute instants.  Keeping this distinction in one shared boundary prevents
individual producers from silently choosing incompatible Parquet types.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import pandas as pd
import pyarrow as pa

BUSINESS_DATE_COLUMNS = frozenset(
    {
        "date",
        "signal_date",
        "evaluation_date",
        "target_date",
        "resolved_date",
        "generated_from_date",
    }
)
EVENT_TIMESTAMP_COLUMNS = frozenset(
    {
        "generated_at",
        "calculated_at",
        "updated_at",
        "last_recalculated_at",
        "actual_updated_at",
    }
)

PARQUET_DATE_TYPE = pa.date32()
PARQUET_EVENT_TIMESTAMP_TYPE = pa.timestamp("us", tz="UTC")


class DateContractError(ValueError):
    """Raised when a non-null value cannot satisfy its semantic date contract."""


def is_business_date_column(name: str) -> bool:
    return name.lower() in BUSINESS_DATE_COLUMNS


def is_event_timestamp_column(name: str) -> bool:
    lowered = name.lower()
    return lowered in EVENT_TIMESTAMP_COLUMNS or lowered.endswith("_calculated_at")


def canonical_arrow_type(name: str) -> pa.DataType | None:
    """Return the required Arrow type for a semantic column, if constrained."""
    if is_business_date_column(name):
        return PARQUET_DATE_TYPE
    if is_event_timestamp_column(name):
        return PARQUET_EVENT_TIMESTAMP_TYPE
    return None


def manifest_type_for_column(name: str) -> str | None:
    """Return the canonical DuckDB/manifest type for a semantic column."""
    if is_business_date_column(name):
        return "DATE"
    if is_event_timestamp_column(name):
        return "TIMESTAMP_US_UTC"
    return None


def normalize_date_contracts(dataframe: pd.DataFrame) -> pd.DataFrame:
    """Normalize known semantic columns, including legacy strings/timestamps.

    The returned frame is a copy.  Business dates become ``datetime.date``
    values (Arrow ``date32``), while event timestamps become timezone-aware UTC
    values at microsecond precision.
    """
    normalized = dataframe.copy()
    for name in normalized.columns:
        if is_business_date_column(str(name)):
            normalized[name] = _normalize_business_dates(normalized[name], str(name))
        elif is_event_timestamp_column(str(name)):
            normalized[name] = _normalize_event_timestamps(
                normalized[name], str(name)
            )
    return normalized


def canonicalize_arrow_schema(schema: pa.Schema) -> pa.Schema:
    """Replace constrained fields while preserving order and schema metadata."""
    fields = []
    for field in schema:
        required_type = canonical_arrow_type(field.name)
        fields.append(
            pa.field(
                field.name,
                required_type or field.type,
                nullable=field.nullable,
                metadata=field.metadata,
            )
        )
    return pa.schema(fields, metadata=schema.metadata)


def _normalize_business_dates(series: pd.Series, column: str) -> pd.Series:
    def convert(value: Any) -> date | None:
        if _is_missing(value):
            return None
        try:
            return pd.Timestamp(value).date()
        except (TypeError, ValueError, OverflowError) as exc:
            raise DateContractError(
                f"Column {column!r} contains an invalid business date: {value!r}"
            ) from exc

    return series.map(convert)


def _normalize_event_timestamps(series: pd.Series, column: str) -> pd.Series:
    try:
        values = pd.to_datetime(series, errors="raise", utc=True)
        return values.astype("datetime64[us, UTC]")
    except (TypeError, ValueError, OverflowError) as exc:
        raise DateContractError(
            f"Column {column!r} contains an invalid event timestamp"
        ) from exc


def _is_missing(value: Any) -> bool:
    missing = pd.isna(value)
    try:
        return bool(missing)
    except ValueError:
        return False
