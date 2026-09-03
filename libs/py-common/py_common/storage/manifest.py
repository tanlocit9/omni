"""Deterministic dataset identity and schema utilities.

Metadata persistence lives exclusively in global_metadata.py. This module does
not read or write object-store metadata.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass, field
from typing import Literal

import pandas as pd

from py_common.storage.date_contracts import manifest_type_for_column
from py_common.storage.exceptions import ManifestInvalidError

logger = logging.getLogger(__name__)
_SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
_SAFE_SEGMENT = re.compile(r"^[a-z0-9][a-z0-9._-]*$")


def _require_segment(value: str, field_name: str) -> str:
    if not isinstance(value, str) or not _SAFE_SEGMENT.fullmatch(value):
        raise ManifestInvalidError(
            f"{field_name} must be a lowercase path-safe identifier: {value!r}"
        )
    return value


def normalize_partition(partition: dict[str, str]) -> dict[str, str]:
    if not isinstance(partition, dict):
        raise ManifestInvalidError("partition must be an object")
    return {
        _require_segment(key, "partition key"): _require_segment(
            value, f"partition value for {key!r}"
        )
        for key, value in sorted(partition.items())
    }


@dataclass(frozen=True)
class ColumnMetadata:
    name: str
    type: str
    nullable: bool = True

    def __post_init__(self) -> None:
        if not isinstance(self.name, str) or not self.name:
            raise ManifestInvalidError("column name must be a non-empty string")
        if not isinstance(self.type, str) or not self.type:
            raise ManifestInvalidError("column type must be a non-empty string")
        if not isinstance(self.nullable, bool):
            raise ManifestInvalidError("column nullable must be a boolean")


@dataclass(frozen=True)
class DatasetInput:
    dataset: str
    partition: dict[str, str]
    dataVersion: str

    def __post_init__(self) -> None:
        _require_segment(self.dataset, "input dataset")
        object.__setattr__(self, "partition", normalize_partition(self.partition))
        if not _SHA256.fullmatch(self.dataVersion or ""):
            raise ManifestInvalidError("input dataVersion must be a valid SHA-256")


@dataclass(frozen=True)
class DatasetManifest:
    """In-process resolved partition view; never persisted independently."""

    version: int
    dataset: str
    partition: dict[str, str]
    status: Literal["READY"]
    path: str
    dataVersion: str
    objectCount: int
    totalBytes: int
    rowCount: int
    columnCount: int
    columns: list[ColumnMetadata]
    schemaVersion: int
    schemaHash: str
    generatedAt: str
    minTimestamp: str | None = None
    maxTimestamp: str | None = None
    inputs: list[DatasetInput] = field(default_factory=list)
    sourceExecutionId: str | None = None


def calculate_schema_hash(columns: list[ColumnMetadata]) -> str:
    canonical = {
        "columns": [
            {"name": item.name, "type": item.type, "nullable": item.nullable}
            for item in sorted(columns, key=lambda column: column.name)
        ]
    }
    encoded = json.dumps(
        canonical, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    )
    return f"sha256:{hashlib.sha256(encoded.encode()).hexdigest()}"


def calculate_data_version(
    dataset: str,
    partition: dict[str, str],
    schema_hash: str,
    object_checksums: list[tuple[str, str]],
    inputs: list[DatasetInput] | None = None,
) -> str:
    lineage = [
        {
            "dataset": item.dataset,
            "partition": normalize_partition(item.partition),
            "dataVersion": item.dataVersion,
        }
        for item in (inputs or [])
    ]
    lineage.sort(
        key=lambda item: json.dumps(item, sort_keys=True, separators=(",", ":"))
    )
    canonical = {
        "dataset": _require_segment(dataset, "dataset"),
        "partition": normalize_partition(partition),
        "schemaHash": schema_hash,
        "objects": sorted(object_checksums),
        "inputs": lineage,
    }
    encoded = json.dumps(
        canonical, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    )
    return f"sha256:{hashlib.sha256(encoded.encode()).hexdigest()}"


def extract_schema_from_dataframe(frame: pd.DataFrame) -> list[ColumnMetadata]:
    columns = []
    for name in frame.columns:
        dtype = frame[name].dtype
        contract_type = manifest_type_for_column(str(name))
        if contract_type is not None:
            data_type = contract_type
        elif pd.api.types.is_integer_dtype(dtype):
            data_type = "BIGINT"
        elif pd.api.types.is_float_dtype(dtype):
            data_type = "DOUBLE"
        elif pd.api.types.is_bool_dtype(dtype):
            data_type = "BOOLEAN"
        elif pd.api.types.is_datetime64_any_dtype(dtype):
            data_type = "TIMESTAMP"
        else:
            data_type = "VARCHAR"
        columns.append(
            ColumnMetadata(str(name), data_type, bool(frame[name].isnull().any()))
        )
    return columns


def extract_timestamp_range(frame: pd.DataFrame) -> tuple[str | None, str | None]:
    for name in ("date", "bar_time", "timestamp"):
        if name not in frame.columns:
            continue
        try:
            return (
                pd.to_datetime(frame[name].min()).isoformat(),
                pd.to_datetime(frame[name].max()).isoformat(),
            )
        except Exception as exc:
            logger.warning("Failed to extract timestamp range from %s: %s", name, exc)
    return None, None
