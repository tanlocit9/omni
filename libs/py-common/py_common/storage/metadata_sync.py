"""Registry-driven synchronization of the canonical global metadata document."""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import pandas as pd

from py_common.storage.dataset_registry import (
    DatasetAdapterDefinition,
    DatasetAdapterRegistry,
)
from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.global_metadata import (
    GLOBAL_METADATA_VERSION,
    GlobalColumnMetadata,
    GlobalDatasetInput,
    GlobalDatasetMetadata,
    GlobalMetadataDocument,
    GlobalMetadataReader,
    GlobalMetadataWriter,
    GlobalPartitionMetadata,
)
from py_common.storage.manifest import (
    DatasetInput,
    calculate_data_version,
    calculate_schema_hash,
    extract_schema_from_dataframe,
    extract_timestamp_range,
)
from py_common.storage.parquet import ParquetCodec
from py_common.storage.ports import ListableStorage, ReadableStorage

logger = logging.getLogger(__name__)


class MetadataSyncEmptyError(RuntimeError):
    """Raised when a requested synchronization produces no valid metadata."""


@dataclass(frozen=True)
class MetadataSyncTarget:
    dataset: str | None = None
    partition: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.dataset is None and self.partition is not None:
            raise ManifestInvalidError("partition requires a logical dataset target")
        if self.partition is not None and not isinstance(self.partition, Mapping):
            raise ManifestInvalidError("partition target must be an object")

    @property
    def mode(self) -> str:
        if self.dataset is None:
            return "FULL"
        if self.partition is None:
            return "DATASET"
        return "EXACT"


@dataclass(frozen=True)
class MetadataSyncResult:
    mode: str
    objects_seen: int
    partitions_added: int
    partitions_replaced: int
    partitions_removed: int
    partitions_unchanged: int
    objects_skipped: int
    objects_failed: int

    @property
    def is_partial(self) -> bool:
        return self.objects_failed > 0 or self.objects_skipped > 0

    @property
    def partitions_published(self) -> int:
        return self.partitions_added + self.partitions_replaced


class MetadataSynchronizer:
    """Build, validate, publish, and read back one canonical metadata object."""

    def __init__(
        self,
        *,
        readable: ReadableStorage,
        listable: ListableStorage,
        reader: GlobalMetadataReader,
        writer: GlobalMetadataWriter,
        registry: DatasetAdapterRegistry,
        bucket: str,
    ) -> None:
        self._readable = readable
        self._listable = listable
        self._reader = reader
        self._writer = writer
        self._registry = registry
        self._bucket = bucket
        self._lock = asyncio.Lock()

    async def sync(
        self,
        *,
        target: MetadataSyncTarget | None = None,
        execution_id: str | None = None,
    ) -> MetadataSyncResult:
        requested = target or MetadataSyncTarget()
        if self._lock.locked():
            raise RuntimeError("A metadata synchronization is already active")
        async with self._lock:
            return await self._sync_locked(requested, execution_id)

    async def _sync_locked(
        self, target: MetadataSyncTarget, execution_id: str | None
    ) -> MetadataSyncResult:
        now = datetime.now(UTC).isoformat().replace("+00:00", "Z")
        current = await self._reader.read() if target.mode != "FULL" else None
        definitions = (
            self._registry.all()
            if target.dataset is None
            else (self._registry.get(target.dataset),)
        )

        seen = skipped = failed = 0
        rebuilt: dict[str, GlobalDatasetMetadata] = {}
        exact_values: dict[str, Any] | None = None
        for definition in definitions:
            if target.mode == "EXACT":
                if not definition.supports_exact_sync:
                    raise ManifestInvalidError(
                        f"Dataset {definition.name!r} does not support "
                        "exact synchronization"
                    )
                exact_values = definition.normalize_partition(target.partition or {})
                section, counts = await self._build_exact_dataset(
                    definition, current, exact_values, now, execution_id
                )
            else:
                supported = (
                    definition.supports_full_sync
                    if target.mode == "FULL"
                    else definition.supports_dataset_sync
                )
                if not supported:
                    raise ManifestInvalidError(
                        f"Dataset {definition.name!r} does not support "
                        f"{target.mode.lower()} synchronization"
                    )
                section, counts = await self._scan_dataset(
                    definition, now, execution_id
                )
            seen += counts[0]
            skipped += counts[1]
            failed += counts[2]
            rebuilt[definition.name] = section

        if failed:
            raise RuntimeError(
                "Metadata synchronization failed validation for "
                f"{failed} physical object(s)"
            )
        if target.mode == "FULL" and not any(
            item.partitions for item in rebuilt.values()
        ):
            raise MetadataSyncEmptyError(
                "Full synchronization produced no valid partitions"
            )

        candidate_sections = (
            rebuilt
            if current is None
            else {item.name: item for item in current.datasets} | rebuilt
        )
        candidate = GlobalMetadataDocument(
            version=GLOBAL_METADATA_VERSION,
            generatedAt=now,
            sourceExecutionId=execution_id,
            datasets=list(candidate_sections.values()),
        )
        added, replaced, removed, unchanged = _diff(current, candidate)
        await self._writer.replace(candidate)
        return MetadataSyncResult(
            mode=target.mode,
            objects_seen=seen,
            partitions_added=added,
            partitions_replaced=replaced,
            partitions_removed=removed,
            partitions_unchanged=unchanged,
            objects_skipped=skipped,
            objects_failed=failed,
        )

    async def _scan_dataset(
        self,
        definition: DatasetAdapterDefinition,
        generated_at: str,
        execution_id: str | None,
    ) -> tuple[GlobalDatasetMetadata, tuple[int, int, int]]:
        names = await self._listable.list_objects(
            self._bucket, prefix=definition.data_prefix
        )
        partitions: list[GlobalPartitionMetadata] = []
        skipped = failed = 0
        for object_name in sorted(names):
            values = _parse_object(definition, object_name)
            if values is None:
                skipped += 1
                continue
            try:
                partitions.append(
                    await self._build_partition(
                        definition, values, object_name, generated_at, execution_id
                    )
                )
            except Exception:
                failed += 1
                logger.exception(
                    "Failed to validate one physical object for dataset=%s",
                    definition.name,
                )
        return _dataset_section(definition, partitions), (len(names), skipped, failed)

    async def _build_exact_dataset(
        self,
        definition: DatasetAdapterDefinition,
        current: GlobalMetadataDocument | None,
        values: dict[str, Any],
        generated_at: str,
        execution_id: str | None,
    ) -> tuple[GlobalDatasetMetadata, tuple[int, int, int]]:
        if current is None:
            raise ManifestInvalidError(
                "Exact synchronization requires current metadata"
            )
        existing = current.dataset(definition.name)
        partitions = list(existing.partitions) if existing is not None else []
        partitions = [item for item in partitions if item.values != values]
        object_name = _object_for_partition(definition, values)
        try:
            metadata = await self._build_partition(
                definition, values, object_name, generated_at, execution_id
            )
        except Exception as exc:
            from py_common.storage.exceptions import StorageObjectNotFoundError

            if not isinstance(exc, StorageObjectNotFoundError):
                raise
        else:
            partitions.append(metadata)
        return _dataset_section(definition, partitions), (1, 0, 0)

    async def _build_partition(
        self,
        definition: DatasetAdapterDefinition,
        values: dict[str, Any],
        object_name: str,
        generated_at: str,
        execution_id: str | None,
    ) -> GlobalPartitionMetadata:
        payload = await self._readable.read_bytes(self._bucket, object_name)
        frame = await asyncio.to_thread(ParquetCodec.decode, payload)
        if frame.empty:
            raise ManifestInvalidError("Canonical Parquet object must not be empty")
        lineage = _extract_lineage(definition.name, frame, values)
        columns = extract_schema_from_dataframe(frame)
        schema_hash = calculate_schema_hash(columns)
        checksum = f"sha256:{hashlib.sha256(payload).hexdigest()}"
        data_version = calculate_data_version(
            definition.name,
            {key: str(value).lower() for key, value in values.items()},
            schema_hash,
            [(object_name, checksum)],
            inputs=[
                DatasetInput(item.dataset, item.partition, item.dataVersion)
                for item in lineage
            ],
        )
        minimum, maximum = extract_timestamp_range(frame)
        return GlobalPartitionMetadata(
            values=values,
            status="READY",
            path=object_name,
            dataVersion=data_version,
            schemaVersion=1,
            schemaHash=schema_hash,
            objectCount=1,
            totalBytes=len(payload),
            rowCount=len(frame),
            columnCount=len(columns),
            columns=[
                GlobalColumnMetadata(item.name, item.type, item.nullable)
                for item in columns
            ],
            minTimestamp=minimum,
            maxTimestamp=maximum,
            inputs=lineage,
            generatedAt=generated_at,
            sourceExecutionId=execution_id,
        )


def _dataset_section(
    definition: DatasetAdapterDefinition,
    partitions: list[GlobalPartitionMetadata],
) -> GlobalDatasetMetadata:
    return GlobalDatasetMetadata(
        name=definition.name,
        label=definition.label,
        dataPrefix=definition.data_prefix,
        partitionKeys=list(definition.partition_keys),
        partitions=partitions,
    )


def _parse_object(
    definition: DatasetAdapterDefinition, object_name: str
) -> dict[str, str] | None:
    patterns = {
        "eod": r"^eod/(?P<exchange>[^/]+)/(?P<code>[^/]+)\.parquet$",
        "indicators": (
            r"^indicators/(?P<source>[^/]+)/(?P<timeframe>[^/]+)/"
            r"(?P<exchange>[^/]+)/(?P<code>[^/]+)\.parquet$"
        ),
        "signals": (
            r"^signals/(?P<strategy>[^/]+)/(?P<timeframe>[^/]+)/"
            r"(?P<exchange>[^/]+)\.parquet$"
        ),
    }
    pattern = patterns.get(definition.name)
    match = re.fullmatch(pattern, object_name) if pattern is not None else None
    if match is None:
        return None
    return definition.normalize_partition(match.groupdict())


def _object_for_partition(
    definition: DatasetAdapterDefinition, values: Mapping[str, Any]
) -> str:
    if definition.name == "eod":
        return f"eod/{values['exchange']}/{values['code']}.parquet"
    if definition.name == "indicators":
        return (
            f"indicators/{values['source']}/{values['timeframe']}/"
            f"{values['exchange']}/{values['code']}.parquet"
        )
    if definition.name == "signals":
        return (
            f"signals/{values['strategy']}/{values['timeframe']}/"
            f"{values['exchange']}.parquet"
        )
    raise ManifestInvalidError(
        f"Dataset {definition.name!r} has no trusted path builder"
    )


def _extract_lineage(
    dataset: str, frame: pd.DataFrame, values: Mapping[str, Any]
) -> list[GlobalDatasetInput]:
    if dataset == "eod":
        return []
    required = {"symbol_key", "eod_data_version"}
    if dataset == "indicators":
        required = {"eod_data_version"}
    if not required.issubset(frame.columns):
        raise ManifestInvalidError(
            f"Dataset {dataset!r} lacks authoritative persisted lineage evidence"
        )
    inputs: dict[tuple[str, tuple[tuple[str, Any], ...], str], GlobalDatasetInput] = {}
    for row in frame.to_dict("records"):
        if dataset == "indicators":
            partition = {"exchange": values["exchange"], "code": values["code"]}
            candidates = (("eod", partition, row.get("eod_data_version")),)
        else:
            parts = str(row.get("symbol_key", "")).lower().split("-", 1)
            if len(parts) != 2:
                raise ManifestInvalidError(
                    "Signal lineage contains an invalid symbol_key"
                )
            partition = {"exchange": parts[0], "code": parts[1]}
            indicator_partition = {
                "source": "ad_close",
                "timeframe": values["timeframe"],
                **partition,
            }
            candidates = (
                ("eod", partition, row.get("eod_data_version")),
                ("indicators", indicator_partition, row.get("indicators_data_version")),
            )
        for upstream, partition, version in candidates:
            if not isinstance(version, str) or not re.fullmatch(
                r"sha256:[0-9a-f]{64}", version
            ):
                raise ManifestInvalidError(
                    "Derived lineage contains an invalid dataVersion"
                )
            item = GlobalDatasetInput(upstream, partition, version)
            inputs[(upstream, tuple(sorted(partition.items())), version)] = item
    return list(inputs.values())


def _diff(
    current: GlobalMetadataDocument | None,
    candidate: GlobalMetadataDocument,
) -> tuple[int, int, int, int]:
    old = _partition_index(current)
    new = _partition_index(candidate)
    added = len(new.keys() - old.keys())
    removed = len(old.keys() - new.keys())
    replaced = unchanged = 0
    for key in old.keys() & new.keys():
        if old[key].dataVersion == new[key].dataVersion:
            unchanged += 1
        else:
            replaced += 1
    return added, replaced, removed, unchanged


def _partition_index(
    document: GlobalMetadataDocument | None,
) -> dict[tuple[str, str], GlobalPartitionMetadata]:
    if document is None:
        return {}
    return {
        (dataset.name, repr(partition.values)): partition
        for dataset in document.datasets
        for partition in dataset.partitions
    }
