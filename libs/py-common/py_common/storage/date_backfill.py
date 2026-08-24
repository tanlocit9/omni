"""Failure-safe versioned rewrite for legacy Parquet date contracts."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import PurePosixPath

from py_common.storage.date_contracts import (
    manifest_type_for_column,
    normalize_date_contracts,
)
from py_common.storage.manifest import (
    DatasetManifest,
    ManifestReader,
    ManifestWriter,
    publish_dataset_manifest,
)
from py_common.storage.parquet import ParquetStorage

_CONTRACT_VERSION = "date-contract-v1"


@dataclass(frozen=True)
class DateBackfillResult:
    """Result of an idempotent READY-last rewrite."""

    manifest: DatasetManifest
    object_name: str
    rewritten: bool


class ParquetDateBackfill:
    """Rewrite one READY object without mutating the object it references.

    A candidate is written beneath a versioned sibling prefix and validated by
    a read-back before the immutable manifest and mutable READY pointer are
    published.  Any failure before the final pointer write leaves the previous
    READY object and pointer untouched.  Retrying an already published rewrite
    is a no-op.
    """

    def __init__(
        self,
        parquet: ParquetStorage,
        reader: ManifestReader,
        writer: ManifestWriter,
    ) -> None:
        self._parquet = parquet
        self._reader = reader
        self._writer = writer

    async def rewrite(
        self,
        dataset: str,
        partition: dict[str, str],
        *,
        execution_id: str | None = None,
    ) -> DateBackfillResult:
        current = await self._reader.read_manifest(dataset, partition)
        if self._is_current_contract(current):
            return DateBackfillResult(current, current.path, rewritten=False)
        if self._is_glob(current.path) or current.objectCount != 1:
            raise ValueError(
                "Date backfill requires a READY partition that references one "
                "exact Parquet object"
            )

        source = await self._parquet.read_dataframe(current.path)
        normalized = normalize_date_contracts(source)
        candidate = self._candidate_path(current)
        write_result = await self._parquet.write_dataframe(candidate, normalized)

        # Validate the persisted candidate before any manifest publication.
        persisted = await self._parquet.read_dataframe(candidate)
        if len(persisted) != len(normalized) or list(persisted.columns) != list(
            normalized.columns
        ):
            raise ValueError("Date backfill candidate failed read-back validation")

        manifest = await publish_dataset_manifest(
            writer=self._writer,
            dataset=dataset,
            partition=partition,
            data_path=candidate,
            dataframe=persisted,
            object_checksums=[(candidate, write_result.checksum)],
            inputs=current.inputs,
            execution_id=execution_id or current.sourceExecutionId,
            object_count=1,
            total_bytes=write_result.total_bytes,
        )
        return DateBackfillResult(manifest, candidate, rewritten=True)

    @staticmethod
    def _is_current_contract(manifest: DatasetManifest) -> bool:
        return f"/{_CONTRACT_VERSION}/" in f"/{manifest.path}" and all(
            manifest_type_for_column(column.name) in {None, column.type}
            for column in manifest.columns
        )

    @staticmethod
    def _is_glob(path: str) -> bool:
        return any(token in path for token in ("*", "?", "["))

    @staticmethod
    def _candidate_path(manifest: DatasetManifest) -> str:
        source = PurePosixPath(manifest.path)
        digest = manifest.dataVersion.removeprefix("sha256:")[:16]
        parent = "" if str(source.parent) == "." else f"{source.parent}/"
        return (
            f"{parent}_versions/{_CONTRACT_VERSION}/"
            f"{source.stem}-{digest}{source.suffix}"
        )
