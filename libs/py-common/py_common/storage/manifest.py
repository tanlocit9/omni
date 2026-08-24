"""Dataset metadata manifest management for Omni analytical storage.

This module provides manifest read/write operations for dataset statistics,
schema information, readiness state, and version lineage tracking.

Manifests are stored as JSON objects in MinIO/S3 alongside Parquet data,
eliminating the need for PostgreSQL/Redis metadata caches.

Key concepts:
    - READY-last semantics: Manifests are published only after data validation
    - Deterministic dataVersion: Content-based fingerprinting for lineage
    - Manifest-first checks: Read manifests instead of scanning Parquet
    - Version lineage: Track upstream dataset versions in inputs[]

Example:
    >>> # Write manifest after successful Parquet write
    >>> manifest_writer = ManifestWriter(registry, provider, bucket)
    >>> await publish_dataset_manifest(
    ...     writer=manifest_writer,
    ...     dataset='eod',
    ...     partition={'exchange': 'hose'},
    ...     data_path='eod/hose/*.parquet',
    ...     dataframe=eod_df,
    ... )

    >>> # Read manifests for statistics
    >>> manifest_reader = ManifestReader(registry, provider, bucket)
    >>> catalog = await manifest_reader.read_catalog()
    >>> manifest = await manifest_reader.read_manifest('eod', {'exchange': 'hose'})
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Literal

import pandas as pd

from py_common.storage.date_contracts import manifest_type_for_column
from py_common.storage.exceptions import (
    ManifestInvalidError,
    ManifestNotFoundError,
    ManifestUnsupportedSchemaVersionError,
    ManifestUnsupportedVersionError,
    StorageObjectNotFoundError,
)
from py_common.storage.ports import ReadableStorage, WritableStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

logger = logging.getLogger(__name__)

# ------------------------------------------------------------------
# Domain Models and canonical identity
# ------------------------------------------------------------------

MANIFEST_VERSION = 1
SCHEMA_VERSION = 1
_READY_STATUS = "READY"
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


def partition_path(partition: dict[str, str]) -> str:
    normalized = normalize_partition(partition)
    if not normalized:
        return "_default"
    return "/".join(f"{key}={value}" for key, value in normalized.items())


def ready_manifest_path(dataset: str, partition: dict[str, str]) -> str:
    dataset = _require_segment(dataset, "dataset")
    return f"_metadata/datasets/{dataset}/{partition_path(partition)}/READY.json"


def versioned_manifest_path(
    dataset: str,
    partition: dict[str, str],
    data_version: str,
) -> str:
    dataset = _require_segment(dataset, "dataset")
    if not isinstance(data_version, str) or not re.fullmatch(
        r"sha256:[0-9a-f]{64}", data_version
    ):
        raise ManifestInvalidError(f"Invalid dataVersion: {data_version!r}")
    digest = data_version.removeprefix("sha256:")
    return (
        f"_metadata/datasets/{dataset}/{partition_path(partition)}/"
        f"versions/{digest}.json"
    )


@dataclass(frozen=True)
class ColumnMetadata:
    """Schema column information."""

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
    """Upstream dataset reference with version for lineage tracking."""

    dataset: str
    partition: dict[str, str]
    dataVersion: str

    def __post_init__(self) -> None:
        _require_segment(self.dataset, "input dataset")
        object.__setattr__(self, "partition", normalize_partition(self.partition))
        if not isinstance(self.dataVersion, str) or not re.fullmatch(
            r"sha256:[0-9a-f]{64}", self.dataVersion
        ):
            raise ManifestInvalidError("input dataVersion must be a valid SHA-256")


@dataclass(frozen=True)
class DatasetManifest:
    """Complete dataset partition manifest.

    This is the primary contract for dataset metadata. All fields except
    optional ones must be present in serialized JSON.
    """

    version: int
    dataset: str
    partition: dict[str, str]
    status: Literal["READY", "PROCESSING", "FAILED"]
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

    def __post_init__(self) -> None:
        if self.version != MANIFEST_VERSION:
            raise ManifestUnsupportedVersionError(self.version)
        if self.schemaVersion != SCHEMA_VERSION:
            raise ManifestUnsupportedSchemaVersionError(self.schemaVersion)
        _require_segment(self.dataset, "dataset")
        object.__setattr__(self, "partition", normalize_partition(self.partition))
        if self.status not in {"READY", "PROCESSING", "FAILED"}:
            raise ManifestInvalidError(f"Unsupported manifest status: {self.status!r}")
        invalid_path = (
            not isinstance(self.path, str)
            or not self.path
            or self.path.startswith("/")
            or ".." in self.path.split("/")
        )
        if invalid_path:
            raise ManifestInvalidError(f"Invalid logical data path: {self.path!r}")
        for name in ("objectCount", "totalBytes", "rowCount", "columnCount"):
            value = getattr(self, name)
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                raise ManifestInvalidError(f"{name} must be a non-negative integer")
        if self.columnCount != len(self.columns):
            raise ManifestInvalidError("columnCount must equal the number of columns")
        if self.status == _READY_STATUS:
            valid_data_version = self.dataVersion and re.fullmatch(
                r"sha256:[0-9a-f]{64}", self.dataVersion
            )
            if not valid_data_version:
                raise ManifestInvalidError(
                    "READY manifest requires a valid dataVersion"
                )
            valid_schema_hash = self.schemaHash and re.fullmatch(
                r"sha256:[0-9a-f]{64}", self.schemaHash
            )
            if not valid_schema_hash:
                raise ManifestInvalidError("READY manifest requires a valid schemaHash")
            if self.objectCount < 1:
                raise ManifestInvalidError("READY manifest requires objectCount >= 1")
        for item in self.inputs:
            if not isinstance(item, DatasetInput):
                raise ManifestInvalidError("inputs must contain DatasetInput values")


@dataclass(frozen=True)
class DatasetDefinition:
    """Catalog entry for a dataset."""

    name: str
    metadataPrefix: str
    dataPrefix: str
    description: str | None = None

    def __post_init__(self) -> None:
        _require_segment(self.name, "dataset definition name")
        expected_prefix = f"_metadata/datasets/{self.name}/"
        if self.metadataPrefix != expected_prefix:
            raise ManifestInvalidError(f"metadataPrefix must equal {expected_prefix!r}")
        if not isinstance(self.dataPrefix, str) or not self.dataPrefix:
            raise ManifestInvalidError("dataPrefix must be a non-empty string")
        if self.dataPrefix.startswith("/") or ".." in self.dataPrefix.split("/"):
            raise ManifestInvalidError(f"Invalid dataPrefix: {self.dataPrefix!r}")
        if self.description is not None and not isinstance(self.description, str):
            raise ManifestInvalidError("description must be a string or null")


@dataclass(frozen=True)
class DatasetCatalog:
    """Root catalog of all datasets."""

    version: int
    datasets: list[DatasetDefinition]
    lastUpdated: str

    def __post_init__(self) -> None:
        if self.version != MANIFEST_VERSION:
            raise ManifestUnsupportedVersionError(self.version)
        if not isinstance(self.datasets, list) or not all(
            isinstance(dataset, DatasetDefinition) for dataset in self.datasets
        ):
            raise ManifestInvalidError(
                "catalog datasets must contain DatasetDefinition values"
            )
        if not isinstance(self.lastUpdated, str) or not self.lastUpdated:
            raise ManifestInvalidError("catalog lastUpdated must be a non-empty string")


# ------------------------------------------------------------------
# DataVersion Calculation
# ------------------------------------------------------------------


def calculate_schema_hash(columns: list[ColumnMetadata]) -> str:
    """Calculate deterministic hash of schema columns.

    The schema hash is based on column names, types, and nullability,
    sorted by column name for consistency.

    Args:
        columns: List of column metadata

    Returns:
        sha256:... schema hash string

    Example:
        >>> cols = [
        ...     ColumnMetadata('date', 'TIMESTAMP', False),
        ...     ColumnMetadata('close', 'DOUBLE', False),
        ... ]
        >>> hash_value = calculate_schema_hash(cols)
        >>> hash_value.startswith('sha256:')
        True
    """
    schema_dict = {
        "columns": [
            {"name": col.name, "type": col.type, "nullable": col.nullable}
            for col in sorted(columns, key=lambda c: c.name)
        ]
    }

    schema_json = json.dumps(
        schema_dict,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    )

    digest = hashlib.sha256(schema_json.encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


def calculate_data_version(
    dataset: str,
    partition: dict[str, str],
    schema_hash: str,
    object_checksums: list[tuple[str, str]],
    inputs: list[DatasetInput] | None = None,
) -> str:
    """Calculate deterministic data version fingerprint.

    The data version is based on:
    - Dataset name
    - Normalized partition keys (sorted)
    - Schema hash
    - Sorted list of (object_path, checksum) tuples

    This ensures that identical content produces identical versions,
    enabling proper lineage tracking and avoiding spurious downstream
    invalidations on idempotent retries.

    Args:
        dataset: Dataset name
        partition: Partition keys dictionary
        schema_hash: Hash of Parquet schema
        object_checksums: Sorted list of (object_path, checksum) tuples

    Returns:
        sha256:... data version string

    Example:
        >>> calculate_data_version(
        ...     'eod',
        ...     {'exchange': 'hose'},
        ...     'sha256:abc123',
        ...     [('eod/hose/hpg.parquet', '"etag-1"')]
        ... )
        'sha256:...'
    """
    canonical_inputs = [
        {
            "dataset": item.dataset,
            "partition": normalize_partition(item.partition),
            "dataVersion": item.dataVersion,
        }
        for item in (inputs or [])
    ]
    canonical_inputs.sort(
        key=lambda item: json.dumps(item, sort_keys=True, separators=(",", ":"))
    )
    canonical = {
        "dataset": _require_segment(dataset, "dataset"),
        "partition": normalize_partition(partition),
        "schemaHash": schema_hash,
        "objects": sorted(object_checksums),
        "inputs": canonical_inputs,
    }

    # Deterministic JSON encoding
    canonical_json = json.dumps(
        canonical,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    )

    # SHA256 fingerprint
    digest = hashlib.sha256(canonical_json.encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


# ------------------------------------------------------------------
# Schema Extraction
# ------------------------------------------------------------------


def extract_schema_from_dataframe(df: pd.DataFrame) -> list[ColumnMetadata]:
    """Extract schema metadata from pandas DataFrame.

    Maps pandas dtypes to SQL-like type names for manifest storage.

    Args:
        df: DataFrame to inspect

    Returns:
        List of column metadata

    Example:
        >>> df = pd.DataFrame({
        ...     'date': pd.to_datetime(['2026-08-18']),
        ...     'close': [100.5],
        ...     'volume': [1000],
        ... })
        >>> schema = extract_schema_from_dataframe(df)
        >>> len(schema)
        3
        >>> schema[0].name
        'date'
    """
    columns = []
    for col_name in df.columns:
        dtype = df[col_name].dtype
        nullable = bool(df[col_name].isnull().any())

        # Map pandas dtype to SQL-like type
        contract_type = manifest_type_for_column(str(col_name))
        if contract_type is not None:
            sql_type = contract_type
        elif pd.api.types.is_integer_dtype(dtype):
            sql_type = "BIGINT"
        elif pd.api.types.is_float_dtype(dtype):
            sql_type = "DOUBLE"
        elif pd.api.types.is_bool_dtype(dtype):
            sql_type = "BOOLEAN"
        elif pd.api.types.is_datetime64_any_dtype(dtype):
            sql_type = "TIMESTAMP"
        elif pd.api.types.is_string_dtype(dtype) or pd.api.types.is_object_dtype(dtype):
            sql_type = "VARCHAR"
        else:
            sql_type = "VARCHAR"  # Fallback

        columns.append(
            ColumnMetadata(
                name=col_name,
                type=sql_type,
                nullable=nullable,
            )
        )

    return columns


def extract_timestamp_range(df: pd.DataFrame) -> tuple[str | None, str | None]:
    """Extract min/max timestamp from DataFrame.

    Searches for common timestamp columns: date, bar_time, timestamp.

    Args:
        df: DataFrame to inspect

    Returns:
        (min_timestamp, max_timestamp) tuple, or (None, None) if no timestamp column
    """
    for ts_col in ["date", "bar_time", "timestamp"]:
        if ts_col in df.columns:
            try:
                min_ts = pd.to_datetime(df[ts_col].min()).isoformat()
                max_ts = pd.to_datetime(df[ts_col].max()).isoformat()
                return min_ts, max_ts
            except Exception as e:
                logger.warning(
                    "Failed to extract timestamp range from %s: %s", ts_col, e
                )
                continue

    return None, None


# ------------------------------------------------------------------
# Manifest Writer
# ------------------------------------------------------------------


class ManifestWriter:
    """Write dataset manifests to object storage.

    Manifests are written as JSON objects under _metadata/datasets/.

    Args:
        registry: Storage provider registry
        provider: Storage provider to use
        bucket: Bucket name for manifest storage

    Example:
        >>> writer = ManifestWriter(registry, StorageProvider.MINIO, 'stock-data')
        >>> await writer.write_manifest(manifest)
    """

    def __init__(
        self,
        registry: StorageProviderRegistry,
        provider: StorageProvider,
        bucket: str,
    ) -> None:
        self._writable: WritableStorage = registry.get_port(provider, WritableStorage)
        self._bucket = bucket

    async def write_manifest(self, manifest: DatasetManifest) -> None:
        """Publish an immutable manifest, then replace READY last."""
        if manifest.status != _READY_STATUS:
            raise ManifestInvalidError("Only READY manifests may be published")
        manifest_json = self._serialize_manifest(manifest).encode("utf-8")
        immutable_path = versioned_manifest_path(
            manifest.dataset, manifest.partition, manifest.dataVersion
        )
        await self._writable.write_bytes(
            bucket=self._bucket,
            object_name=immutable_path,
            data=manifest_json,
            content_type="application/json",
        )
        pointer_path = ready_manifest_path(manifest.dataset, manifest.partition)
        await self._writable.write_bytes(
            bucket=self._bucket,
            object_name=pointer_path,
            data=manifest_json,
            content_type="application/json",
        )
        logger.info(
            "Published READY manifest for %s partition=%s version=%s",
            manifest.dataset,
            manifest.partition,
            manifest.dataVersion,
        )

    async def write_catalog(self, catalog: DatasetCatalog) -> None:
        """Write dataset catalog to object storage.

        Args:
            catalog: Catalog to write

        Raises:
            StorageWriteError: If write fails
        """
        catalog_json = self._serialize_catalog(catalog)

        await self._writable.write_bytes(
            bucket=self._bucket,
            object_name="_metadata/catalog.json",
            data=catalog_json.encode("utf-8"),
            content_type="application/json",
        )

        logger.info("Wrote catalog with %d datasets", len(catalog.datasets))

    def _build_manifest_path(
        self,
        dataset: str,
        partition: dict[str, str],
    ) -> str:
        """Build the mutable READY pointer path."""
        return ready_manifest_path(dataset, partition)

    def _serialize_manifest(self, manifest: DatasetManifest) -> str:
        """Serialize a stable JSON shape including nullable fields and arrays."""
        manifest_dict: dict[str, Any] = {
            "version": manifest.version,
            "dataset": manifest.dataset,
            "partition": manifest.partition,
            "status": manifest.status,
            "path": manifest.path,
            "dataVersion": manifest.dataVersion,
            "objectCount": manifest.objectCount,
            "totalBytes": manifest.totalBytes,
            "rowCount": manifest.rowCount,
            "columnCount": manifest.columnCount,
            "columns": [
                {"name": col.name, "type": col.type, "nullable": col.nullable}
                for col in manifest.columns
            ],
            "schemaVersion": manifest.schemaVersion,
            "schemaHash": manifest.schemaHash,
            "minTimestamp": manifest.minTimestamp,
            "maxTimestamp": manifest.maxTimestamp,
            "inputs": [
                {
                    "dataset": item.dataset,
                    "partition": normalize_partition(item.partition),
                    "dataVersion": item.dataVersion,
                }
                for item in manifest.inputs
            ],
            "sourceExecutionId": manifest.sourceExecutionId,
            "generatedAt": manifest.generatedAt,
        }
        return json.dumps(manifest_dict, indent=2, ensure_ascii=False)

    def _serialize_catalog(self, catalog: DatasetCatalog) -> str:
        """Serialize catalog to JSON."""
        catalog_dict = {
            "version": catalog.version,
            "datasets": [
                {
                    "name": ds.name,
                    "metadataPrefix": ds.metadataPrefix,
                    "dataPrefix": ds.dataPrefix,
                    **({"description": ds.description} if ds.description else {}),
                }
                for ds in catalog.datasets
            ],
            "lastUpdated": catalog.lastUpdated,
        }

        return json.dumps(catalog_dict, indent=2, ensure_ascii=False)


# ------------------------------------------------------------------
# Manifest Reader
# ------------------------------------------------------------------


class ManifestReader:
    """Read dataset manifests from object storage.

    Args:
        registry: Storage provider registry
        provider: Storage provider to use
        bucket: Bucket name for manifest storage

    Example:
        >>> reader = ManifestReader(registry, StorageProvider.MINIO, 'stock-data')
        >>> catalog = await reader.read_catalog()
        >>> manifest = await reader.read_manifest('eod', {'exchange': 'hose'})
    """

    def __init__(
        self,
        registry: StorageProviderRegistry,
        provider: StorageProvider,
        bucket: str,
    ) -> None:
        self._readable: ReadableStorage = registry.get_port(provider, ReadableStorage)
        self._bucket = bucket

    async def read_catalog(self) -> DatasetCatalog:
        """Read dataset catalog.

        Returns:
            Dataset catalog, or empty catalog if not found

        Raises:
            StorageReadError: If read fails for reasons other than not found
        """
        path = "_metadata/catalog.json"

        try:
            data = await self._readable.read_bytes(self._bucket, path)
            return self._deserialize_catalog(data.decode("utf-8"))
        except StorageObjectNotFoundError:
            # Return empty catalog if not found
            logger.warning("Catalog not found, returning empty catalog")
            return DatasetCatalog(
                version=1,
                datasets=[],
                lastUpdated=datetime.now(UTC).isoformat(),
            )

    async def read_manifest(
        self,
        dataset: str,
        partition: dict[str, str],
    ) -> DatasetManifest:
        """Read and validate a specific dataset partition READY pointer.

        Args:
            dataset: Dataset name
            partition: Partition keys

        Returns:
            Validated manifest stored at the canonical READY pointer

        Raises:
            ManifestNotFoundError: If the READY pointer does not exist
            ManifestInvalidError: If the manifest JSON violates the contract
            StorageReadError: If the storage read fails for another reason
        """
        path = self._build_manifest_path(dataset, partition)

        try:
            data = await self._readable.read_bytes(self._bucket, path)
        except StorageObjectNotFoundError as exc:
            raise ManifestNotFoundError(dataset, partition) from exc
        return self._deserialize_manifest(data.decode("utf-8"))

    def _build_manifest_path(
        self,
        dataset: str,
        partition: dict[str, str],
    ) -> str:
        """Build the mutable READY pointer path."""
        return ready_manifest_path(dataset, partition)

    def _deserialize_manifest(self, json_str: str) -> DatasetManifest:
        """Deserialize and validate JSON, ignoring unknown additive fields."""
        try:
            data = json.loads(json_str)
            if not isinstance(data, dict):
                raise TypeError("manifest root must be an object")
            columns = [ColumnMetadata(**column) for column in data["columns"]]
            inputs = [DatasetInput(**item) for item in data["inputs"]]
            return DatasetManifest(
                version=data["version"],
                dataset=data["dataset"],
                partition=data["partition"],
                status=data["status"],
                path=data["path"],
                dataVersion=data["dataVersion"],
                objectCount=data["objectCount"],
                totalBytes=data["totalBytes"],
                rowCount=data["rowCount"],
                columnCount=data["columnCount"],
                columns=columns,
                schemaVersion=data["schemaVersion"],
                schemaHash=data["schemaHash"],
                minTimestamp=data["minTimestamp"],
                maxTimestamp=data["maxTimestamp"],
                inputs=inputs,
                sourceExecutionId=data["sourceExecutionId"],
                generatedAt=data["generatedAt"],
            )
        except (
            ManifestInvalidError,
            ManifestUnsupportedVersionError,
            ManifestUnsupportedSchemaVersionError,
        ):
            raise
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise ManifestInvalidError("Invalid dataset manifest JSON", exc) from exc

    def _deserialize_catalog(self, json_str: str) -> DatasetCatalog:
        """Deserialize and validate catalog JSON."""
        try:
            data = json.loads(json_str)
            if not isinstance(data, dict):
                raise TypeError("catalog root must be an object")
            datasets = [DatasetDefinition(**ds) for ds in data["datasets"]]
            return DatasetCatalog(
                version=data["version"],
                datasets=datasets,
                lastUpdated=data["lastUpdated"],
            )
        except ManifestInvalidError, ManifestUnsupportedVersionError:
            raise
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise ManifestInvalidError("Invalid dataset catalog JSON", exc) from exc


# ------------------------------------------------------------------
# High-Level Publishing Helper
# ------------------------------------------------------------------


async def publish_dataset_manifest(
    writer: ManifestWriter,
    dataset: str,
    partition: dict[str, str],
    data_path: str,
    dataframe: pd.DataFrame,
    object_checksums: list[tuple[str, str]],
    inputs: list[DatasetInput] | None = None,
    execution_id: str | None = None,
    object_count: int = 1,
    total_bytes: int = 0,
) -> DatasetManifest:
    """Publish READY manifest after successful data write.

    This is the recommended high-level API for manifest publishing.
    Call this LAST after Parquet data is written and validated.

    Args:
        writer: Manifest writer instance
        dataset: Dataset name
        partition: Partition keys dictionary
        data_path: Glob path to data files (for reference)
        dataframe: DataFrame that was written (for schema extraction)
        object_checksums: Physical object paths and checksums/ETags
        inputs: Upstream dataset inputs for lineage tracking
        execution_id: Source job execution ID
        object_count: Number of objects written
        total_bytes: Total bytes written

    Returns:
        Published manifest

    Example:
        >>> manifest = await publish_dataset_manifest(
        ...     writer=manifest_writer,
        ...     dataset='eod',
        ...     partition={'exchange': 'hose'},
        ...     data_path='eod/hose/*.parquet',
        ...     dataframe=eod_df,
        ...     inputs=[DatasetInput(...)],
        ...     execution_id='exec-123',
        ... )
    """
    # Extract schema
    columns = extract_schema_from_dataframe(dataframe)
    schema_hash = calculate_schema_hash(columns)

    # Calculate statistics
    row_count = len(dataframe)
    column_count = len(dataframe.columns)

    # Get timestamp range
    min_timestamp, max_timestamp = extract_timestamp_range(dataframe)

    if not object_checksums:
        raise ManifestInvalidError(
            "READY publication requires at least one physical object checksum"
        )
    lineage = inputs or []
    data_version = calculate_data_version(
        dataset=dataset,
        partition=partition,
        schema_hash=schema_hash,
        object_checksums=object_checksums,
        inputs=lineage,
    )

    # Build manifest
    manifest = DatasetManifest(
        version=1,
        dataset=dataset,
        partition=partition,
        status="READY",
        path=data_path,
        dataVersion=data_version,
        objectCount=object_count,
        totalBytes=total_bytes,
        rowCount=row_count,
        columnCount=column_count,
        columns=columns,
        schemaVersion=1,
        schemaHash=schema_hash,
        minTimestamp=min_timestamp,
        maxTimestamp=max_timestamp,
        inputs=lineage,
        sourceExecutionId=execution_id,
        generatedAt=datetime.now(UTC).isoformat(),
    )

    # Write manifest (READY-last semantics)
    await writer.write_manifest(manifest)

    return manifest


# ------------------------------------------------------------------
# Catalog Bootstrap
# ------------------------------------------------------------------

# Known Omni datasets for catalog bootstrapping
OMNI_DATASETS = [
    DatasetDefinition(
        name="eod",
        metadataPrefix="_metadata/datasets/eod/",
        dataPrefix="eod/",
        description="End-of-day price data by exchange",
    ),
    DatasetDefinition(
        name="indicators",
        metadataPrefix="_metadata/datasets/indicators/",
        dataPrefix="indicators/",
        description="Technical indicators by source and timeframe",
    ),
    DatasetDefinition(
        name="signals",
        metadataPrefix="_metadata/datasets/signals/",
        dataPrefix="signals/",
        description="Trading signals by strategy",
    ),
    DatasetDefinition(
        name="symbol-features",
        metadataPrefix="_metadata/datasets/symbol-features/",
        dataPrefix="features/symbol/",
        description="Symbol-level sector wave features",
    ),
    DatasetDefinition(
        name="sector-features",
        metadataPrefix="_metadata/datasets/sector-features/",
        dataPrefix="features/sector/",
        description="Sector-level aggregated features",
    ),
]


async def bootstrap_catalog(writer: ManifestWriter) -> DatasetCatalog:
    """Bootstrap catalog with known Omni datasets.

    Args:
        writer: Manifest writer instance

    Returns:
        Bootstrapped catalog

    Example:
        >>> catalog = await bootstrap_catalog(manifest_writer)
    """
    catalog = DatasetCatalog(
        version=1,
        datasets=OMNI_DATASETS,
        lastUpdated=datetime.now(UTC).isoformat(),
    )

    await writer.write_catalog(catalog)

    logger.info("Bootstrapped catalog with %d datasets", len(catalog.datasets))
    return catalog
