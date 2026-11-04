"""Addressable output bytes only. No READY pointer, journal or durable recovery."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from py_common.config.paths import StockDataPaths
from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
)
from py_common.storage.manifest import (
    DatasetInput,
    calculate_data_version,
    normalize_partition,
)
from py_common.storage.ports import ReadableStorage, WritableStorage


@dataclass(frozen=True)
class OutputSnapshotIdentity:
    dataset: str
    partition: dict[str, str]

    def __post_init__(self) -> None:
        if (
            not isinstance(self.dataset, str)
            or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", self.dataset) is None
        ):
            raise ManifestInvalidError("Invalid snapshot dataset")
        object.__setattr__(self, "partition", normalize_partition(self.partition))


@dataclass(frozen=True)
class OutputSnapshot:
    object_name: str
    canonical_object_name: str
    data_version: str
    checksum: str
    total_bytes: int
    schema_hash: str
    inputs: tuple[DatasetInput, ...]


def snapshot_object_name(
    identity: OutputSnapshotIdentity, data_version: str, paths: StockDataPaths
) -> str:
    if re.fullmatch(r"sha256:[0-9a-f]{64}", data_version) is None:
        raise ManifestInvalidError("Invalid snapshot dataVersion")
    partition = json.dumps(
        identity.partition, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    )
    partition_hash = hashlib.sha256(partition.encode("utf-8")).hexdigest()
    name = paths.output_snapshot(identity.dataset, partition_hash, data_version[7:])
    if (
        not name.endswith(".parquet")
        or name.startswith("/")
        or ":" in name
        or "\\" in name
        or any(part in {"", ".", ".."} for part in name.split("/"))
    ):
        raise ManifestInvalidError("Snapshot layout must produce a safe relative path")
    return name


class OutputSnapshotStore:
    def __init__(
        self,
        readable: ReadableStorage,
        writable: WritableStorage,
        bucket: str,
        paths: StockDataPaths,
    ) -> None:
        self._readable = readable
        self._writable = writable
        self._bucket = bucket
        self._paths = paths

    async def publish(
        self,
        *,
        identity: OutputSnapshotIdentity,
        canonical_object_name: str,
        data: bytes,
        schema_hash: str,
        inputs: tuple[DatasetInput, ...] = (),
    ) -> OutputSnapshot:
        if re.fullmatch(r"sha256:[0-9a-f]{64}", schema_hash) is None:
            raise ManifestInvalidError("Invalid snapshot schema fingerprint")
        if (
            not isinstance(data, bytes)
            or not data
            or not canonical_object_name.endswith(".parquet")
            or canonical_object_name.startswith("/")
            or ":" in canonical_object_name
            or "\\" in canonical_object_name
            or any(part in {"", ".", ".."} for part in canonical_object_name.split("/"))
        ):
            raise ManifestInvalidError("Invalid canonical output identity")
        checksum = "sha256:" + hashlib.sha256(data).hexdigest()
        version = calculate_data_version(
            identity.dataset,
            identity.partition,
            schema_hash,
            [(canonical_object_name, checksum)],
            inputs=list(inputs),
        )
        target = snapshot_object_name(identity, version, self._paths)
        if target == canonical_object_name:
            raise ManifestInvalidError(
                "Snapshot layout must not alias the mutable object"
            )
        try:
            existing = await self._readable.read_bytes(self._bucket, target)
        except StorageObjectNotFoundError:
            # Every conforming writer derives this key from the same byte fingerprint,
            # schema and lineage. Concurrent duplicate writers can only write
            # equal bytes.
            await self._writable.write_bytes(
                self._bucket, target, data, "application/vnd.apache.parquet"
            )
        else:
            if existing != data:
                raise ManifestInvalidError(
                    "Existing snapshot conflicts with its version identity"
                )
        persisted = await self._readable.read_bytes(self._bucket, target)
        if persisted != data:
            raise ManifestInvalidError("Snapshot bytes failed read-back validation")
        return OutputSnapshot(
            target,
            canonical_object_name,
            version,
            checksum,
            len(data),
            schema_hash,
            inputs,
        )

    async def read(
        self, identity: OutputSnapshotIdentity, snapshot: OutputSnapshot
    ) -> bytes:
        expected_path = snapshot_object_name(
            identity, snapshot.data_version, self._paths
        )
        if snapshot.object_name != expected_path:
            raise ManifestInvalidError(
                "Snapshot path differs from its logical identity"
            )
        data = await self._readable.read_bytes(self._bucket, expected_path)
        checksum = "sha256:" + hashlib.sha256(data).hexdigest()
        version = calculate_data_version(
            identity.dataset,
            identity.partition,
            snapshot.schema_hash,
            [(snapshot.canonical_object_name, checksum)],
            inputs=list(snapshot.inputs),
        )
        if (
            checksum != snapshot.checksum
            or len(data) != snapshot.total_bytes
            or version != snapshot.data_version
        ):
            raise ManifestInvalidError(
                "Snapshot bytes differ from the registered version"
            )
        return data
