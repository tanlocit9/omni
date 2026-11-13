"""Parquet data-format service layered on top of storage ports.

``ParquetStorage`` sits above the storage ports and handles
serialization/deserialization of pandas DataFrames to/from Parquet bytes.
It is completely decoupled from MinIO — swap the provider in the registry
and the Parquet logic stays unchanged.

``ParquetCodec`` is separated out so it can be unit-tested independently
of any network or I/O concerns.

Semantics:
    read_dataframe(path)
        Object missing  → StorageObjectNotFoundError  (caller decides)
        Corrupt bytes   → ParquetDecodeError
    read_optional_dataframe(path)
        Object missing  → None
        Corrupt bytes   → ParquetDecodeError  (still propagated)
    write_dataframe(path, df)
        Upload failure  → StorageWriteError

Empty DataFrame and None have different meanings:
    None              → dataset does not exist yet
    empty DataFrame   → dataset exists but has no rows
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import json
import logging
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import PurePosixPath
from uuid import uuid4

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

from py_common.config.paths import StockDataPaths
from py_common.storage.date_contracts import (
    canonicalize_arrow_schema,
    normalize_date_contracts,
)
from py_common.storage.exceptions import (
    ManifestInvalidError,
    ParquetDecodeError,
    StorageObjectNotFoundError,
)
from py_common.storage.global_metadata import GlobalPartitionMetadata
from py_common.storage.manifest import (
    DatasetInput,
    calculate_data_version,
    calculate_schema_hash,
    extract_schema_from_dataframe,
)
from py_common.storage.output_lineage import extract_output_lineage
from py_common.storage.output_snapshots import (
    OutputSnapshot,
    OutputSnapshotIdentity,
    OutputSnapshotStore,
)
from py_common.storage.ports import (
    CopyableStorage,
    DeletableStorage,
    ReadableStorage,
    VersionedStorage,
    WritableStorage,
)
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

logger = logging.getLogger(__name__)

_PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet"


@dataclass(frozen=True)
class ParquetWriteResult:
    """Physical identity of the exact Parquet bytes persisted to storage."""

    object_name: str
    checksum: str
    total_bytes: int
    temporary_object_name: str | None = None
    snapshot: OutputSnapshot | None = None


class ParquetCodec:
    """Stateless codec for converting DataFrames to/from Parquet bytes.

    Separated from ``ParquetStorage`` so serialization logic can be
    tested without any I/O.
    """

    @staticmethod
    def encode(
        dataframe: pd.DataFrame,
        *,
        index: bool = False,
        schema: pa.Schema | None = None,
    ) -> bytes:
        """Serialize a DataFrame to Parquet bytes.

        Args:
            dataframe: The DataFrame to encode.
            index: Whether to include the DataFrame index. Default ``False``.
            schema: Optional explicit PyArrow schema. Use this for nested columns
                that require stable field names across Parquet readers.

        Returns:
            Parquet-encoded bytes.
        """
        buffer = io.BytesIO()
        normalized = normalize_date_contracts(dataframe)
        inferred = pa.Table.from_pandas(normalized, preserve_index=index)
        target_schema = canonicalize_arrow_schema(schema or inferred.schema)
        table = pa.Table.from_pandas(
            normalized,
            schema=target_schema,
            preserve_index=index,
        )
        pq.write_table(table, buffer)
        return buffer.getvalue()

    @staticmethod
    def decode(data: bytes) -> pd.DataFrame:
        """Deserialize Parquet bytes into a DataFrame.

        Args:
            data: Raw Parquet bytes.

        Returns:
            Decoded DataFrame.

        Raises:
            ParquetDecodeError: If the bytes are not valid Parquet.
        """
        try:
            return normalize_date_contracts(pd.read_parquet(io.BytesIO(data)))
        except Exception as exc:
            raise ParquetDecodeError(cause=exc) from exc


class ParquetStorage:
    """Read and write pandas DataFrames as Parquet objects in object storage.

    Constructed with a registry and provider so it remains independent of
    any specific storage SDK.  Business logic injects this class and never
    touches ``Minio`` directly.

    Args:
        registry: The ``StorageProviderRegistry`` holding registered adapters.
        provider: Which storage provider to use (e.g. ``StorageProvider.MINIO``).
        bucket: Default bucket name for all operations.

    Example::

        parquet = ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket="stock-data",
        )

        df = await parquet.read_dataframe("eod/hose/hpg.parquet")
        await parquet.write_dataframe("eod/hose/hpg.parquet", merged_df)
    """

    CONTENT_TYPE = _PARQUET_CONTENT_TYPE

    def __init__(
        self,
        registry: StorageProviderRegistry,
        provider: StorageProvider,
        bucket: str,
        snapshot_paths: StockDataPaths | None = None,
    ) -> None:
        self._readable: ReadableStorage = registry.get_port(provider, ReadableStorage)
        self._writable: WritableStorage = registry.get_port(provider, WritableStorage)
        self._copyable: CopyableStorage = registry.get_port(provider, CopyableStorage)
        self._deletable: DeletableStorage = registry.get_port(
            provider,
            DeletableStorage,
        )
        self._bucket = bucket
        self._registry, self._provider = registry, provider
        self._paths = snapshot_paths or StockDataPaths.from_config({})
        self._snapshot_store = OutputSnapshotStore(
            self._readable,
            self._writable,
            bucket,
            snapshot_paths or StockDataPaths.from_config({}),
        )

    # ------------------------------------------------------------------
    # Read operations
    # ------------------------------------------------------------------

    async def read_dataframe(self, object_name: str) -> pd.DataFrame:
        """Download and deserialize a Parquet object.

        Args:
            object_name: Object key within the configured bucket.

        Returns:
            Decoded DataFrame.

        Raises:
            StorageObjectNotFoundError: Object does not exist.
            ParquetDecodeError: Bytes exist but are not valid Parquet.
            StorageReadError: Transport / server failure.
        """
        publication, identity = self._work_publication(object_name)
        if self._versioned(publication):
            frame = await self.read_work_base_dataframe(publication.raw, identity)
            if frame is None:
                raise StorageObjectNotFoundError(
                    bucket=self._bucket, object_name=object_name
                )
            return frame
        data = await self._readable.read_bytes(self._bucket, object_name)
        try:
            return await asyncio.to_thread(ParquetCodec.decode, data)
        except ParquetDecodeError as exc:
            # Re-raise with object_name context so callers see the full path.
            raise ParquetDecodeError(
                cause=exc.cause,
                object_name=f"{self._bucket}/{object_name}",
            ) from exc.cause

    async def read_bound_dataframe(
        self, dataset: str, manifest: GlobalPartitionMetadata, expected_version: str
    ) -> pd.DataFrame:
        """Validate the exact bytes decoded against an execution's approved version."""
        if manifest.status != "READY" or manifest.dataVersion != expected_version:
            raise ManifestInvalidError(
                "Current metadata differs from approved input version"
            )
        proofs = getattr(manifest, "workProofs", [])
        if (
            proofs
            and (
                getattr(manifest, "storageVersionId", None) is not None
                or any(p.get("storageVersionId") is not None for p in proofs)
            )
            and (
                getattr(manifest, "storageVersionId", None) is None
                or any(p.get("storageVersionId") is None for p in proofs)
            )
        ):
            raise ManifestInvalidError(
                "Incomplete immutable storage coordinates; no mutable fallback"
            )
        data = await self._bound_bytes(
            manifest.path, getattr(manifest, "storageVersionId", None)
        )
        if getattr(manifest, "workScope", None) or getattr(
            manifest, "workProofs", None
        ):
            from py_common.storage.work_rows import select_rows, work_version

            frame = await asyncio.to_thread(ParquetCodec.decode, data)
            proofs = manifest.workProofs or [
                {
                    "workScope": manifest.workScope,
                    "schemaHash": manifest.schemaHash,
                    "columns": [vars(c) for c in manifest.columns],
                    "inputs": [vars(i) for i in manifest.inputs],
                    "dataVersion": manifest.dataVersion,
                    "storageVersionId": getattr(manifest, "storageVersionId", None),
                }
            ]
            for proof in proofs:
                proof_frame = frame
                if proof.get("storageVersionId") != getattr(
                    manifest, "storageVersionId", None
                ):
                    proof_frame = await asyncio.to_thread(
                        ParquetCodec.decode,
                        await self._bound_bytes(
                            manifest.path, proof.get("storageVersionId")
                        ),
                    )
                rows = select_rows(
                    dataset,
                    proof_frame,
                    proof["workScope"],
                    [c["name"] for c in proof["columns"]],
                )
                if (
                    calculate_schema_hash(extract_schema_from_dataframe(rows))
                    != proof["schemaHash"]
                ):
                    raise ManifestInvalidError(
                        "Parquet work schema differs from approved schema"
                    )
                if (
                    work_version(
                        dataset,
                        manifest.values,
                        proof["workScope"],
                        rows,
                        proof["schemaHash"],
                        proof["inputs"],
                    )
                    != proof["dataVersion"]
                ):
                    raise ManifestInvalidError(
                        "Parquet work data differs from approved version"
                    )
                if proof_frame is not frame:
                    frame = self._overlay_rows(dataset, frame, rows)
            return frame
        checksum = "sha256:" + hashlib.sha256(data).hexdigest()
        version = calculate_data_version(
            dataset,
            {key: str(value).lower() for key, value in manifest.values.items()},
            manifest.schemaHash,
            [(getattr(manifest, "canonicalPath", None) or manifest.path, checksum)],
            inputs=[
                DatasetInput(item.dataset, item.partition, item.dataVersion)
                for item in manifest.inputs
            ],
        )
        if version != expected_version:
            raise ManifestInvalidError(
                "Parquet bytes differ from approved input version"
            )
        frame = await asyncio.to_thread(ParquetCodec.decode, data)
        if (
            calculate_schema_hash(extract_schema_from_dataframe(frame))
            != manifest.schemaHash
        ):
            raise ManifestInvalidError(
                "Parquet schema differs from approved input schema"
            )
        actual_inputs = extract_output_lineage(
            dataset,
            frame,
            manifest.values,
            require_complete_components=bool(getattr(manifest, "canonicalPath", None)),
        )

        def identity(item):
            return (
                item.dataset,
                tuple(sorted(item.partition.items())),
                item.dataVersion,
            )

        if {identity(item) for item in actual_inputs} != {
            identity(item) for item in manifest.inputs
        }:
            raise ManifestInvalidError(
                "Parquet lineage differs from approved input lineage"
            )
        return frame

    async def read_optional_dataframe(self, object_name: str) -> pd.DataFrame | None:
        """Download and deserialize a Parquet object, returning ``None`` if absent.

        Use this when the absence of an object is a normal condition (e.g.
        first-time ingest — no existing file to merge with).  Corrupt data
        still raises ``ParquetDecodeError`` so data integrity errors are
        never silently swallowed.

        Args:
            object_name: Object key within the configured bucket.

        Returns:
            Decoded DataFrame, or ``None`` if the object does not exist.

        Raises:
            ParquetDecodeError: Bytes exist but are not valid Parquet.
            StorageReadError: Transport / server failure (not a 404).
        """
        try:
            return await self.read_dataframe(object_name)
        except StorageObjectNotFoundError:
            logger.debug(
                "Object '%s/%s' not found — returning None",
                self._bucket,
                object_name,
            )
            return None

    async def snapshot_bound_output(
        self, dataset: str, manifest: GlobalPartitionMetadata
    ):
        """Backfill exact validated bytes.

        A concurrent mutable replacement fails closed.
        """
        await self.read_bound_dataframe(dataset, manifest, manifest.dataVersion)
        data = await self._bound_bytes(
            manifest.path, getattr(manifest, "storageVersionId", None)
        )
        identity = OutputSnapshotIdentity(dataset, manifest.values)
        snapshot = await self._snapshot_bytes(manifest.path, data, identity)
        if snapshot is None or snapshot.data_version != manifest.dataVersion:
            raise ManifestInvalidError(
                "Output changed while creating backfill snapshot"
            )
        return snapshot, await self.read_output_snapshot(identity, snapshot)

    async def pin_historical_output(
        self, identity, manifest, *, allow_unproven_history=False
    ):
        """Pin a physical baseline without creating work READY or provenance."""
        if not allow_unproven_history:
            raise ManifestInvalidError(
                "Physical baseline requires explicit historical-container approval"
            )
        object_name = getattr(self._paths, identity.dataset)(**identity.partition)
        data = await self._readable.read_bytes(self._bucket, object_name)
        frame = await asyncio.to_thread(ParquetCodec.decode, data)
        from py_common.storage.work_rows import row_keys

        if frame.empty:
            raise ManifestInvalidError("Cannot pin an empty historical container")
        row_keys(identity.dataset, frame)
        if manifest is not None:
            checked = await self.read_bound_dataframe(
                identity.dataset, manifest, manifest.dataVersion
            )
            try:
                pd.testing.assert_frame_equal(
                    frame, checked, check_exact=True, check_dtype=False
                )
            except AssertionError as exc:
                raise ManifestInvalidError(
                    "Historical canonical bytes differ from accepted proofs"
                ) from exc
        if await self._readable.read_bytes(self._bucket, object_name) != data:
            raise ManifestInvalidError(
                "Historical container changed during baseline validation"
            )
        version = await self._version_port().write_versioned_bytes(
            self._bucket, object_name, data, _PARQUET_CONTENT_TYPE
        )
        if await self._bound_bytes(object_name, version) != data:
            raise ManifestInvalidError(
                "Historical version read-back differs from validated bytes"
            )
        return {
            "storageVersionId": version,
            "checksum": "sha256:" + hashlib.sha256(data).hexdigest(),
            "totalBytes": len(data),
        }

    # ------------------------------------------------------------------
    # Write operations
    # ------------------------------------------------------------------

    def _work_publication(self, object_name):
        from py_common.messaging.output_publication import active_publication

        publication = active_publication()
        if publication is None:
            return None, None
        for item in publication.contract["requiredOutputs"]:
            identity = OutputSnapshotIdentity(item["dataset"], item["partition"])
            builder = getattr(self._paths, identity.dataset, None)
            if builder is not None and builder(**identity.partition) == object_name:
                return publication, identity
        return None, None

    def _prepare_work(self, publication, identity, expected, before):
        from py_common.storage.work_rows import scope_for_work, validate_replacement

        scope, rows = scope_for_work(
            publication.raw, identity.dataset, expected, before
        )
        validate_replacement(identity.dataset, before, expected, scope)
        output = publication.prepare(
            identity,
            None,
            expected,
            before=before,
            total_bytes=len(ParquetCodec.encode(rows)),
        )
        publication.preflight(
            output.model_copy(update={"storage_version_id": "x" * 1024})
            if self._versioned(publication)
            else output
        )
        return output

    async def _record_work(
        self, publication, object_name, output, storage_version=None
    ):
        if publication is None:
            return
        from py_common.storage.work_rows import select_rows, work_version

        # Read after canonical publication, before emitting registration/worker SUCCESS.
        # Read back the PUT result, not a mutable HEAD or the captured write base.
        actual = await asyncio.to_thread(
            ParquetCodec.decode, await self._bound_bytes(object_name, storage_version)
        )
        scope = output.work_scope.model_dump(mode="json", by_alias=True)
        selected = select_rows(
            output.dataset, actual, scope, [c.name for c in output.columns]
        )
        schema_hash = calculate_schema_hash(extract_schema_from_dataframe(selected))
        version = work_version(
            output.dataset,
            output.partition,
            scope,
            selected,
            schema_hash,
            [i.model_dump(mode="json", by_alias=True) for i in output.inputs],
        )
        if schema_hash != output.schema_hash or version != output.data_version:
            raise ManifestInvalidError(
                "Canonical Parquet does not contain the published work data"
            )
        if storage_version is not None:
            output = output.model_copy(update={"storage_version_id": storage_version})
        publication.confirm(output)
        # TODO(P12-I4): persist/reuse the prepared evidence across copy/read-back/
        # Kafka ACK failures. Preflight is not durable recovery or write fencing.

    async def write_dataframe(
        self,
        object_name: str,
        dataframe: pd.DataFrame,
        *,
        index: bool = False,
        schema: pa.Schema | None = None,
        snapshot_identity: OutputSnapshotIdentity | None = None,
    ) -> ParquetWriteResult:
        """Serialize a DataFrame and upload it as a Parquet object.

        Overwrites any existing object at ``object_name``.

        Args:
            object_name: Object key within the configured bucket.
            dataframe: DataFrame to serialize and upload.
            index: Whether to include the DataFrame index. Default ``False``.
            schema: Optional explicit PyArrow schema. Use this for nested columns
                that require stable field names across Parquet readers.

        Returns:
            Physical identity derived from the exact bytes uploaded.

        Raises:
            StorageWriteError: If the upload fails.
        """
        data = await asyncio.to_thread(
            ParquetCodec.encode,
            dataframe,
            index=index,
            schema=schema,
        )
        publication, work_identity = self._work_publication(object_name)
        before = (
            await self.read_optional_dataframe(object_name) if publication else None
        )
        work_frame = (
            await asyncio.to_thread(ParquetCodec.decode, data) if publication else None
        )
        if self._versioned(publication):
            work_frame = self._merge_work(
                publication, work_identity, work_frame, before
            )
            data = await asyncio.to_thread(
                ParquetCodec.encode, work_frame, index=index, schema=schema
            )
        output = (
            self._prepare_work(publication, work_identity, work_frame, before)
            if publication
            else None
        )
        snapshot = (
            None
            if publication
            else await self._snapshot_bytes(object_name, data, snapshot_identity)
        )
        storage_version = None
        if self._versioned(publication):
            storage_version = await self._version_port().write_versioned_bytes(
                self._bucket, object_name, data, _PARQUET_CONTENT_TYPE
            )
        else:
            await self._writable.write_bytes(
                bucket=self._bucket,
                object_name=object_name,
                data=data,
                content_type=_PARQUET_CONTENT_TYPE,
            )

        if publication:
            await self._record_work(publication, object_name, output, storage_version)
        result = ParquetWriteResult(
            object_name=object_name,
            checksum=f"sha256:{hashlib.sha256(data).hexdigest()}",
            total_bytes=len(data),
            snapshot=snapshot,
        )
        if snapshot is not None:
            from py_common.messaging.output_publication import record_output

            record_output(
                snapshot_identity,
                snapshot,
                await asyncio.to_thread(ParquetCodec.decode, data),
            )
        logger.debug(
            "Wrote DataFrame (%d rows, %d cols, %d bytes) to '%s/%s'",
            len(dataframe),
            len(dataframe.columns),
            result.total_bytes,
            self._bucket,
            object_name,
        )
        return result

    async def _snapshot_bytes(
        self,
        canonical_object_name: str,
        data: bytes,
        identity: OutputSnapshotIdentity | None,
    ) -> OutputSnapshot | None:
        if identity is None:
            return None
        # Describe the encoded byte buffer, not the pre-serialization DataFrame.
        frame = await asyncio.to_thread(ParquetCodec.decode, data)
        if frame.empty:
            raise ManifestInvalidError("Cannot publish an empty output snapshot")
        columns = extract_schema_from_dataframe(frame)
        lineage = extract_output_lineage(
            identity.dataset,
            frame,
            identity.partition,
            require_complete_components=True,
        )
        return await self._snapshot_store.publish(
            identity=identity,
            canonical_object_name=canonical_object_name,
            data=data,
            schema_hash=calculate_schema_hash(columns),
            inputs=tuple(
                DatasetInput(item.dataset, item.partition, item.dataVersion)
                for item in lineage
            ),
        )

    async def read_output_snapshot(
        self,
        identity: OutputSnapshotIdentity,
        snapshot: OutputSnapshot,
    ) -> pd.DataFrame:
        data = await self._snapshot_store.read(identity, snapshot)
        frame = await asyncio.to_thread(ParquetCodec.decode, data)
        if (
            calculate_schema_hash(extract_schema_from_dataframe(frame))
            != snapshot.schema_hash
        ):
            raise ManifestInvalidError(
                "Snapshot schema differs from its registered schema"
            )
        lineage = tuple(
            DatasetInput(item.dataset, item.partition, item.dataVersion)
            for item in extract_output_lineage(
                identity.dataset,
                frame,
                identity.partition,
                require_complete_components=True,
            )
        )
        if set(
            (item.dataset, tuple(sorted(item.partition.items())), item.dataVersion)
            for item in lineage
        ) != set(
            (item.dataset, tuple(sorted(item.partition.items())), item.dataVersion)
            for item in snapshot.inputs
        ):
            raise ManifestInvalidError(
                "Snapshot lineage differs from its registered lineage"
            )
        return frame

    async def replace_dataframe(
        self,
        object_name: str,
        dataframe: pd.DataFrame,
        *,
        index: bool = False,
        temp_object_name: str | None = None,
        validate: Callable[[pd.DataFrame], None] | None = None,
        snapshot_identity: OutputSnapshotIdentity | None = None,
    ) -> ParquetWriteResult:
        """Best-effort atomic replacement for a Parquet object.

        The candidate DataFrame is written to a temporary object, read back,
        optionally validated, copied over the final object, and then cleaned up.
        If any step before the final copy fails, the existing final object is
        preserved. Object stores do not provide database-style transactions, so
        this method is explicitly best-effort.

        Args:
            object_name: Final object key within the configured bucket.
            dataframe: DataFrame to serialize and replace the final object with.
            index: Whether to include the DataFrame index. Default ``False``.
            temp_object_name: Optional explicit temporary object key. Tests and
                orchestrators can pass this for deterministic cleanup.
            validate: Optional callback that receives the read-back DataFrame
                and raises on schema/content validation failure.

        Returns:
            Physical identity of the final object and the staging object key.

        Raises:
            ParquetDecodeError: Temporary bytes are not valid Parquet.
            StorageWriteError: Temporary write or final copy fails.
            Any exception raised by ``validate``.
        """
        publication, work_identity = self._work_publication(object_name)
        before = (
            await self.read_optional_dataframe(object_name) if publication else None
        )
        dataframe = self._merge_work(publication, work_identity, dataframe, before)
        temp_name = temp_object_name or self._build_temp_object_name(object_name)

        staged_result = await self.write_dataframe(temp_name, dataframe, index=index)
        try:
            staged = await self.read_dataframe(temp_name)
            if validate is not None:
                validate(staged)

            snapshot = None
            output = (
                self._prepare_work(publication, work_identity, staged, before)
                if publication
                else None
            )
            if snapshot_identity is not None and publication is None:
                staged_bytes = await self._readable.read_bytes(self._bucket, temp_name)
                if (
                    "sha256:" + hashlib.sha256(staged_bytes).hexdigest()
                    != staged_result.checksum
                ):
                    raise ManifestInvalidError(
                        "Staged bytes changed before snapshot publication"
                    )
                snapshot = await self._snapshot_bytes(
                    object_name, staged_bytes, snapshot_identity
                )

            storage_version = None
            if self._versioned(publication):
                staged_bytes = await self._readable.read_bytes(self._bucket, temp_name)
                if (
                    "sha256:" + hashlib.sha256(staged_bytes).hexdigest()
                    != staged_result.checksum
                ):
                    raise ManifestInvalidError(
                        "Staged bytes changed before immutable publication"
                    )
                storage_version = await self._version_port().write_versioned_bytes(
                    self._bucket, object_name, staged_bytes, _PARQUET_CONTENT_TYPE
                )
            else:
                await self._copyable.copy_object(
                    bucket=self._bucket,
                    source_object_name=temp_name,
                    target_object_name=object_name,
                    content_type=_PARQUET_CONTENT_TYPE,
                )
        except Exception:
            await self._delete_temp_safely(temp_name)
            raise

        await self._delete_temp_safely(temp_name)

        await self._record_work(publication, object_name, output, storage_version)
        if snapshot is not None:
            from py_common.messaging.output_publication import record_output

            record_output(
                snapshot_identity,
                snapshot,
                await self.read_output_snapshot(snapshot_identity, snapshot),
            )

        logger.debug(
            "Replaced DataFrame (%d rows, %d cols) at '%s/%s' via temp '%s'",
            len(dataframe),
            len(dataframe.columns),
            self._bucket,
            object_name,
            temp_name,
        )
        return ParquetWriteResult(
            object_name=object_name,
            checksum=staged_result.checksum,
            total_bytes=staged_result.total_bytes,
            temporary_object_name=temp_name,
            snapshot=snapshot,
        )

    def _build_temp_object_name(self, object_name: str) -> str:
        path = PurePosixPath(object_name)
        parent = "" if str(path.parent) == "." else f"{path.parent}/"
        return f"{parent}.tmp/{path.name}.{uuid4().hex}.tmp"

    async def _delete_temp_safely(self, object_name: str) -> None:
        try:
            await self._deletable.delete(self._bucket, object_name)
        except StorageObjectNotFoundError:
            logger.debug(
                "Temporary object '%s/%s' already absent during cleanup",
                self._bucket,
                object_name,
            )
        except Exception:
            logger.warning(
                "Failed to clean up temporary object '%s/%s'",
                self._bucket,
                object_name,
                exc_info=True,
            )

    def _version_port(self):
        return self._registry.get_port(self._provider, VersionedStorage)

    async def _bound_bytes(self, object_name, version):
        if version is None:
            return await self._readable.read_bytes(self._bucket, object_name)
        return await self._version_port().read_versioned_bytes(
            self._bucket, object_name, version
        )

    @staticmethod
    def _versioned(publication):
        return (
            publication is not None
            and publication.contract.get("storagePolicy") == "VERSIONED"
        )

    async def read_work_base_dataframe(self, raw, identity):
        """Read the frozen accepted version, never an unaccepted late PUT."""
        contract = (raw.get("metadata") or {}).get("outputCompletion") or {}
        object_name = getattr(self._paths, identity.dataset)(**identity.partition)
        if contract.get("storagePolicy") != "VERSIONED":
            return await self.read_optional_dataframe(object_name)
        bases = contract.get("storageBases")
        if not isinstance(bases, list) or not 1 <= len(bases) <= 32:
            raise ManifestInvalidError("Missing frozen storage bases")
        matches = [
            b
            for b in bases
            if b.get("dataset") == identity.dataset
            and b.get("partition") == identity.partition
        ]
        if len(matches) != 1:
            raise ManifestInvalidError("Output has no unique frozen storage base")
        base = matches[0]
        version = base.get("storageVersionId")
        if version is not None:
            if type(base.get("generation")) is not int or base["generation"] < 0:
                raise ManifestInvalidError("Invalid storage base generation")
            try:
                data = await self._bound_bytes(object_name, version)
            except StorageObjectNotFoundError as exc:
                raise ManifestInvalidError(
                    "Pinned storage base is missing; no mutable fallback"
                ) from exc
            return await asyncio.to_thread(ParquetCodec.decode, data)
        if base.get("generation") is not None:
            raise ManifestInvalidError("Incomplete frozen storage base")
        try:
            await self._readable.read_bytes(self._bucket, object_name)
        except StorageObjectNotFoundError:
            return None
        raise ManifestInvalidError(
            "STORAGE_BASELINE_REQUIRED: existing canonical object is not pinned"
        )

    @staticmethod
    def _overlay_rows(dataset, before, selected):
        from py_common.storage.work_rows import row_keys, select_rows

        if before is None or before.empty:
            return selected.copy()
        owned = {json.dumps(k, sort_keys=True) for k in row_keys(dataset, selected)}
        other_keys = [
            k
            for k in row_keys(dataset, before)
            if json.dumps(k, sort_keys=True) not in owned
        ]
        # row_keys is canonically sorted, so it must never be used as a positional
        # mask over a container whose physical row order may differ.
        kept = (
            select_rows(dataset, before, {"rowKeys": other_keys}, before.columns)
            if other_keys
            else before.iloc[:0]
        )
        return pd.concat([kept, selected], ignore_index=True)

    def _merge_work(self, publication, identity, frame, before):
        if not self._versioned(publication) or before is None:
            return frame
        from py_common.storage.work_rows import scope_for_work

        _, selected = scope_for_work(publication.raw, identity.dataset, frame, before)
        return self._overlay_rows(identity.dataset, before, selected)
