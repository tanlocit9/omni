"""Safe metadata reconciliation for canonical Parquet datasets."""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
from dataclasses import dataclass

from py_common.storage.exceptions import ManifestError
from py_common.storage.manifest import (
    ManifestReader,
    ManifestWriter,
    bootstrap_catalog,
    calculate_data_version,
    calculate_schema_hash,
    extract_schema_from_dataframe,
    publish_dataset_manifest,
)
from py_common.storage.parquet import ParquetCodec
from py_common.storage.ports import ListableStorage, ReadableStorage

logger = logging.getLogger(__name__)

_EOD_OBJECT = re.compile(
    r"^eod/(?P<exchange>[a-z0-9][a-z0-9._-]*)/"
    r"(?P<code>[a-z0-9][a-z0-9._-]*)\.parquet$"
)


class MetadataSyncEmptyError(RuntimeError):
    """Raised when no canonical, non-empty EOD object can be reconciled."""


@dataclass(frozen=True)
class MetadataSyncResult:
    objects_seen: int
    manifests_published: int
    manifests_unchanged: int
    objects_skipped: int
    objects_failed: int

    @property
    def is_partial(self) -> bool:
        return self.objects_failed > 0 or self.objects_skipped > 0


class EodMetadataSynchronizer:
    """Rebuild EOD manifests from exact persisted Parquet bytes.

    EOD is the only safe automatic reconstruction target because it is a root
    dataset and therefore has no upstream lineage to infer. Derived dataset
    manifests must continue to be published by their writers with exact inputs.
    """

    def __init__(
        self,
        *,
        readable: ReadableStorage,
        listable: ListableStorage,
        reader: ManifestReader,
        writer: ManifestWriter,
        bucket: str,
    ) -> None:
        self._readable = readable
        self._listable = listable
        self._reader = reader
        self._writer = writer
        self._bucket = bucket

    async def sync(self, *, execution_id: str | None = None) -> MetadataSyncResult:
        object_names = await self._listable.list_objects(self._bucket, prefix="eod/")
        canonical = [
            (object_name, match)
            for object_name in object_names
            if (match := _EOD_OBJECT.fullmatch(object_name)) is not None
        ]
        skipped = len(object_names) - len(canonical)
        published = 0
        unchanged = 0
        failed = 0

        for object_name, match in canonical:
            try:
                parquet_bytes = await self._readable.read_bytes(
                    self._bucket, object_name
                )
                dataframe = await asyncio.to_thread(ParquetCodec.decode, parquet_bytes)
                if dataframe.empty:
                    skipped += 1
                    continue
                checksum = f"sha256:{hashlib.sha256(parquet_bytes).hexdigest()}"
                partition = {
                    "exchange": match.group("exchange"),
                    "code": match.group("code"),
                }
                schema_hash = calculate_schema_hash(
                    extract_schema_from_dataframe(dataframe)
                )
                data_version = calculate_data_version(
                    dataset="eod",
                    partition=partition,
                    schema_hash=schema_hash,
                    object_checksums=[(object_name, checksum)],
                    inputs=[],
                )
                try:
                    current = await self._reader.read_manifest("eod", partition)
                except ManifestError:
                    current = None
                if (
                    current is not None
                    and current.dataVersion == data_version
                    and current.path == object_name
                ):
                    unchanged += 1
                    continue
                await publish_dataset_manifest(
                    writer=self._writer,
                    dataset="eod",
                    partition=partition,
                    data_path=object_name,
                    dataframe=dataframe,
                    object_checksums=[(object_name, checksum)],
                    inputs=[],
                    execution_id=execution_id,
                    object_count=1,
                    total_bytes=len(parquet_bytes),
                )
                published += 1
            except Exception:
                failed += 1
                logger.exception("Failed to reconcile one EOD metadata partition")

        if published + unchanged == 0:
            raise MetadataSyncEmptyError(
                "No canonical, non-empty EOD Parquet object could be reconciled"
            )

        # Catalog is the discovery pointer and is updated only after manifests.
        await bootstrap_catalog(self._writer)
        return MetadataSyncResult(
            objects_seen=len(object_names),
            manifests_published=published,
            manifests_unchanged=unchanged,
            objects_skipped=skipped,
            objects_failed=failed,
        )
