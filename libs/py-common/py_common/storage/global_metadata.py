"""Canonical global dataset metadata document and storage operations.

This module is additive during the metadata migration. Existing per-partition
manifest readers and writers remain available until all consumers switch.
"""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date, datetime
from enum import StrEnum
from typing import Any

from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
)
from py_common.storage.ports import ReadableStorage, WritableStorage

GLOBAL_METADATA_PATH = "_metadata/metadata.json"
GLOBAL_METADATA_VERSION = 1
_SCHEMA_VERSION = 1
_SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
_SAFE_NAME = re.compile(r"^[a-z0-9][a-z0-9._-]*$")


class PartitionValueType(StrEnum):
    STRING = "STRING"
    DATE = "DATE"
    INTEGER = "INTEGER"
    BOOLEAN = "BOOLEAN"


def _invalid(message: str) -> ManifestInvalidError:
    return ManifestInvalidError(message)


def _require_name(value: str, field_name: str) -> str:
    if not isinstance(value, str) or not _SAFE_NAME.fullmatch(value):
        raise _invalid(f"{field_name} must be a lowercase path-safe identifier")
    return value


def _require_timestamp(value: str, field_name: str) -> str:
    if not isinstance(value, str) or not value:
        raise _invalid(f"{field_name} must be a non-empty ISO-8601 timestamp")
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise _invalid(f"{field_name} must be an ISO-8601 timestamp") from exc
    return value


def _require_path(value: str, field_name: str) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value.startswith("/")
        or ".." in value.split("/")
    ):
        raise _invalid(f"{field_name} must be a safe relative object path")
    return value


@dataclass(frozen=True)
class PartitionKeyDefinition:
    name: str
    type: PartitionValueType
    required: bool
    order: int
    queryable: bool = True
    label: str | None = None

    def __post_init__(self) -> None:
        _require_name(self.name, "partition key name")
        try:
            object.__setattr__(self, "type", PartitionValueType(self.type))
        except ValueError as exc:
            raise _invalid(f"Unsupported partition key type: {self.type!r}") from exc
        if not isinstance(self.required, bool) or not isinstance(self.queryable, bool):
            raise _invalid("partition key required and queryable must be booleans")
        if (
            not isinstance(self.order, int)
            or isinstance(self.order, bool)
            or self.order < 0
        ):
            raise _invalid("partition key order must be a non-negative integer")
        if self.label is not None and (
            not isinstance(self.label, str) or not self.label
        ):
            raise _invalid("partition key label must be a non-empty string or null")

    def normalize(self, value: Any) -> str | int | bool:
        if self.type is PartitionValueType.STRING:
            if not isinstance(value, str) or not value or len(value) > 256:
                raise _invalid(
                    f"partition value {self.name!r} must be a bounded string"
                )
            return value
        if self.type is PartitionValueType.DATE:
            if not isinstance(value, str):
                raise _invalid(f"partition value {self.name!r} must be an ISO date")
            try:
                return date.fromisoformat(value).isoformat()
            except ValueError as exc:
                raise _invalid(
                    f"partition value {self.name!r} must be an ISO date"
                ) from exc
        if self.type is PartitionValueType.INTEGER:
            if not isinstance(value, int) or isinstance(value, bool):
                raise _invalid(f"partition value {self.name!r} must be an integer")
            return value
        if not isinstance(value, bool):
            raise _invalid(f"partition value {self.name!r} must be a boolean")
        return value


@dataclass(frozen=True)
class GlobalColumnMetadata:
    name: str
    type: str
    nullable: bool

    def __post_init__(self) -> None:
        if not isinstance(self.name, str) or not self.name:
            raise _invalid("column name must be a non-empty string")
        if not isinstance(self.type, str) or not self.type:
            raise _invalid("column type must be a non-empty string")
        if not isinstance(self.nullable, bool):
            raise _invalid("column nullable must be a boolean")


@dataclass(frozen=True)
class GlobalDatasetInput:
    dataset: str
    partition: dict[str, Any]
    dataVersion: str

    def __post_init__(self) -> None:
        _require_name(self.dataset, "input dataset")
        if not isinstance(self.partition, dict):
            raise _invalid("input partition must be an object")
        if not isinstance(self.dataVersion, str) or not _SHA256.fullmatch(
            self.dataVersion
        ):
            raise _invalid("input dataVersion must be a SHA-256 fingerprint")
        object.__setattr__(self, "partition", dict(sorted(self.partition.items())))


@dataclass(frozen=True)
class GlobalPartitionMetadata:
    values: dict[str, Any]
    status: str
    path: str
    dataVersion: str
    schemaVersion: int
    schemaHash: str
    objectCount: int
    totalBytes: int
    rowCount: int
    columnCount: int
    columns: list[GlobalColumnMetadata]
    generatedAt: str
    minTimestamp: str | None = None
    maxTimestamp: str | None = None
    inputs: list[GlobalDatasetInput] = field(default_factory=list)
    sourceExecutionId: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.values, dict):
            raise _invalid("partition values must be an object")
        if self.status != "READY":
            raise _invalid("global metadata partitions must have READY status")
        _require_path(self.path, "partition path")
        if not _SHA256.fullmatch(self.dataVersion or ""):
            raise _invalid("partition dataVersion must be a SHA-256 fingerprint")
        if self.schemaVersion != _SCHEMA_VERSION:
            raise _invalid(f"Unsupported schemaVersion: {self.schemaVersion!r}")
        if not _SHA256.fullmatch(self.schemaHash or ""):
            raise _invalid("partition schemaHash must be a SHA-256 fingerprint")
        for name in ("objectCount", "totalBytes", "rowCount", "columnCount"):
            value = getattr(self, name)
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                raise _invalid(f"{name} must be a non-negative integer")
        if self.objectCount < 1:
            raise _invalid("READY partition requires objectCount >= 1")
        if self.columnCount != len(self.columns):
            raise _invalid("columnCount must equal the number of columns")
        if not all(isinstance(item, GlobalColumnMetadata) for item in self.columns):
            raise _invalid("columns contains an invalid value")
        if not all(isinstance(item, GlobalDatasetInput) for item in self.inputs):
            raise _invalid("inputs contains an invalid value")
        _require_timestamp(self.generatedAt, "partition generatedAt")
        object.__setattr__(self, "values", dict(sorted(self.values.items())))

    @property
    def partition(self) -> dict[str, Any]:
        """Logical alias used by backend query and dependency code."""
        return self.values


@dataclass(frozen=True)
class GlobalDatasetMetadata:
    name: str
    label: str
    dataPrefix: str
    partitionKeys: list[PartitionKeyDefinition]
    partitions: list[GlobalPartitionMetadata]

    def __post_init__(self) -> None:
        _require_name(self.name, "dataset name")
        if not isinstance(self.label, str) or not self.label:
            raise _invalid("dataset label must be a non-empty string")
        _require_path(self.dataPrefix, "dataset dataPrefix")
        definitions = sorted(self.partitionKeys, key=lambda item: item.order)
        if [item.order for item in definitions] != list(range(len(definitions))):
            raise _invalid(
                "partition key orders must be unique and contiguous from zero"
            )
        if len({item.name for item in definitions}) != len(definitions):
            raise _invalid("partition key names must be unique")
        object.__setattr__(self, "partitionKeys", definitions)

        identities: set[tuple[Any, ...]] = set()
        normalized_partitions: list[GlobalPartitionMetadata] = []
        expected = {item.name for item in definitions if item.required}
        allowed = {item.name for item in definitions}
        for partition in self.partitions:
            keys = set(partition.values)
            if not expected.issubset(keys) or not keys.issubset(allowed):
                raise _invalid(f"partition keys do not match dataset {self.name!r}")
            normalized = {
                definition.name: definition.normalize(partition.values[definition.name])
                for definition in definitions
                if definition.name in partition.values
            }
            object.__setattr__(partition, "values", normalized)
            identity = tuple(normalized.get(item.name) for item in definitions)
            if identity in identities:
                raise _invalid(f"duplicate partition identity in dataset {self.name!r}")
            identities.add(identity)
            normalized_partitions.append(partition)
        normalized_partitions.sort(key=lambda item: _identity_json(item.values))
        object.__setattr__(self, "partitions", normalized_partitions)

    def find_partition(
        self, values: Mapping[str, Any]
    ) -> GlobalPartitionMetadata | None:
        normalized = self.normalize_partition(values)
        return next(
            (item for item in self.partitions if item.values == normalized), None
        )

    def normalize_partition(self, values: Mapping[str, Any]) -> dict[str, Any]:
        if not isinstance(values, Mapping):
            raise _invalid("partition values must be an object")
        supplied = set(values)
        required = {item.name for item in self.partitionKeys if item.required}
        allowed = {item.name for item in self.partitionKeys}
        if not required.issubset(supplied) or not supplied.issubset(allowed):
            raise _invalid(f"partition keys do not match dataset {self.name!r}")
        return {
            item.name: item.normalize(values[item.name])
            for item in self.partitionKeys
            if item.name in values
        }


@dataclass(frozen=True)
class GlobalMetadataDocument:
    version: int
    generatedAt: str
    datasets: list[GlobalDatasetMetadata]
    sourceExecutionId: str | None = None

    def __post_init__(self) -> None:
        if self.version != GLOBAL_METADATA_VERSION:
            raise _invalid(f"Unsupported global metadata version: {self.version!r}")
        _require_timestamp(self.generatedAt, "global generatedAt")
        names = [dataset.name for dataset in self.datasets]
        if len(set(names)) != len(names):
            raise _invalid("global metadata dataset names must be unique")
        object.__setattr__(
            self, "datasets", sorted(self.datasets, key=lambda item: item.name)
        )

    def dataset(self, name: str) -> GlobalDatasetMetadata | None:
        return next((item for item in self.datasets if item.name == name), None)

    def resolve(
        self, dataset: str, values: Mapping[str, Any]
    ) -> GlobalPartitionMetadata | None:
        section = self.dataset(dataset)
        return section.find_partition(values) if section is not None else None

    def to_json(self) -> str:
        return json.dumps(
            _document_dict(self),
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=True,
        )

    @classmethod
    def from_json(cls, payload: str | bytes) -> GlobalMetadataDocument:
        try:
            raw = json.loads(payload)
            if not isinstance(raw, dict):
                raise TypeError("root must be an object")
            datasets = []
            for dataset in raw["datasets"]:
                partitions = []
                for partition in dataset["partitions"]:
                    partition_fields = {
                        key: value
                        for key, value in partition.items()
                        if key not in {"columns", "inputs"}
                    }
                    partitions.append(
                        GlobalPartitionMetadata(
                            **partition_fields,
                            columns=[
                                GlobalColumnMetadata(**item)
                                for item in partition["columns"]
                            ],
                            inputs=[
                                GlobalDatasetInput(**item)
                                for item in partition["inputs"]
                            ],
                        )
                    )
                dataset_fields = {
                    key: value
                    for key, value in dataset.items()
                    if key not in {"partitionKeys", "partitions"}
                }
                datasets.append(
                    GlobalDatasetMetadata(
                        **dataset_fields,
                        partitionKeys=[
                            PartitionKeyDefinition(**item)
                            for item in dataset["partitionKeys"]
                        ],
                        partitions=partitions,
                    )
                )
            return cls(
                version=raw["version"],
                generatedAt=raw["generatedAt"],
                sourceExecutionId=raw.get("sourceExecutionId"),
                datasets=datasets,
            )
        except ManifestInvalidError:
            raise
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise _invalid("Invalid global metadata JSON") from exc


def _identity_json(values: Mapping[str, Any]) -> str:
    return json.dumps(values, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def _document_dict(document: GlobalMetadataDocument) -> dict[str, Any]:
    return {
        "version": document.version,
        "generatedAt": document.generatedAt,
        "sourceExecutionId": document.sourceExecutionId,
        "datasets": [
            {
                "name": dataset.name,
                "label": dataset.label,
                "dataPrefix": dataset.dataPrefix,
                "partitionKeys": [
                    {
                        "name": item.name,
                        "type": item.type.value,
                        "required": item.required,
                        "order": item.order,
                        "queryable": item.queryable,
                        **({"label": item.label} if item.label is not None else {}),
                    }
                    for item in dataset.partitionKeys
                ],
                "partitions": [
                    {
                        "values": partition.values,
                        "status": partition.status,
                        "path": partition.path,
                        "dataVersion": partition.dataVersion,
                        "schemaVersion": partition.schemaVersion,
                        "schemaHash": partition.schemaHash,
                        "objectCount": partition.objectCount,
                        "totalBytes": partition.totalBytes,
                        "rowCount": partition.rowCount,
                        "columnCount": partition.columnCount,
                        "columns": [vars(item) for item in partition.columns],
                        "minTimestamp": partition.minTimestamp,
                        "maxTimestamp": partition.maxTimestamp,
                        "inputs": [vars(item) for item in partition.inputs],
                        "generatedAt": partition.generatedAt,
                        "sourceExecutionId": partition.sourceExecutionId,
                    }
                    for partition in dataset.partitions
                ],
            }
            for dataset in document.datasets
        ],
    }


class GlobalMetadataReader:
    def __init__(self, readable: ReadableStorage, bucket: str) -> None:
        self._readable = readable
        self._bucket = bucket

    async def read(self) -> GlobalMetadataDocument:
        try:
            payload = await self._readable.read_bytes(
                self._bucket, GLOBAL_METADATA_PATH
            )
        except StorageObjectNotFoundError as exc:
            raise ManifestInvalidError(
                "Global metadata document not found", exc
            ) from exc
        return GlobalMetadataDocument.from_json(payload)


class GlobalMetadataWriter:
    """Sole-writer replacement operation with mandatory read-back validation."""

    def __init__(
        self, writable: WritableStorage, readable: ReadableStorage, bucket: str
    ) -> None:
        self._writable = writable
        self._reader = GlobalMetadataReader(readable, bucket)
        self._bucket = bucket

    async def replace(self, document: GlobalMetadataDocument) -> None:
        payload = document.to_json().encode("utf-8")
        await self._writable.write_bytes(
            bucket=self._bucket,
            object_name=GLOBAL_METADATA_PATH,
            data=payload,
            content_type="application/json",
        )
        persisted = await self._reader.read()
        if persisted.to_json() != document.to_json():
            raise ManifestInvalidError(
                "Persisted global metadata failed read-back validation"
            )
