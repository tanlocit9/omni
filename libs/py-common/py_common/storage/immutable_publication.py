"""Immutable dataset publication with a validated READY-last pointer."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from py_common.storage.ports import ReadableStorage, WritableStorage

_JSON_CONTENT_TYPE = "application/json"
_PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet"


@dataclass(frozen=True)
class ImmutablePublicationResult:
    """Physical identities published for one immutable dataset version."""

    data_version: str
    data_object: str
    version_manifest_object: str
    ready_object: str
    total_bytes: int


class ImmutableDatasetPublisher:
    """Publish immutable bytes and manifest before replacing ``READY.json``.

    Candidate data and its version manifest are read back and validated before the
    mutable pointer is written. Therefore any failure before the final write leaves
    an existing READY pointer untouched.
    """

    def __init__(
        self,
        readable: ReadableStorage,
        writable: WritableStorage,
        bucket: str,
    ) -> None:
        self._readable = readable
        self._writable = writable
        self._bucket = bucket

    async def publish(
        self,
        *,
        partition_prefix: str,
        object_name: str,
        data: bytes,
        manifest: dict[str, Any],
        validate_data: Callable[[bytes], Awaitable[None] | None] | None = None,
    ) -> ImmutablePublicationResult:
        prefix = _safe_prefix(partition_prefix)
        leaf = _safe_leaf(object_name)
        digest = hashlib.sha256(data).hexdigest()
        data_version = f"sha256:{digest}"
        version_prefix = f"{prefix}/_versions/{digest}"
        data_object = f"{version_prefix}/{leaf}"
        version_manifest_object = f"{version_prefix}/manifest.json"
        ready_object = f"{prefix}/READY.json"

        await self._writable.write_bytes(
            self._bucket,
            data_object,
            data,
            _PARQUET_CONTENT_TYPE,
        )
        persisted_data = await self._readable.read_bytes(self._bucket, data_object)
        if persisted_data != data:
            raise ValueError("Immutable data failed read-back validation")
        if validate_data is not None:
            validation = validate_data(persisted_data)
            if validation is not None:
                await validation

        version_manifest = {
            **manifest,
            "status": "READY",
            "dataVersion": data_version,
            "path": data_object,
            "totalBytes": len(data),
        }
        manifest_bytes = _canonical_json(version_manifest)
        await self._writable.write_bytes(
            self._bucket,
            version_manifest_object,
            manifest_bytes,
            _JSON_CONTENT_TYPE,
        )
        persisted_manifest = await self._readable.read_bytes(
            self._bucket, version_manifest_object
        )
        if persisted_manifest != manifest_bytes:
            raise ValueError("Immutable version manifest failed read-back validation")

        ready_bytes = _canonical_json(
            {
                "status": "READY",
                "dataVersion": data_version,
                "manifestPath": version_manifest_object,
            }
        )
        await self._writable.write_bytes(
            self._bucket,
            ready_object,
            ready_bytes,
            _JSON_CONTENT_TYPE,
        )
        persisted_ready = await self._readable.read_bytes(self._bucket, ready_object)
        if persisted_ready != ready_bytes:
            raise ValueError("READY pointer failed read-back validation")

        return ImmutablePublicationResult(
            data_version=data_version,
            data_object=data_object,
            version_manifest_object=version_manifest_object,
            ready_object=ready_object,
            total_bytes=len(data),
        )


def _canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("utf-8")


def _safe_prefix(value: str) -> str:
    normalized = value.strip().strip("/")
    if not normalized or any(part in {"", ".", ".."} for part in normalized.split("/")):
        raise ValueError("partition_prefix must be a safe relative object prefix")
    return normalized


def _safe_leaf(value: str) -> str:
    normalized = value.strip()
    if not normalized or "/" in normalized or normalized in {".", ".."}:
        raise ValueError("object_name must be a safe object leaf name")
    return normalized
