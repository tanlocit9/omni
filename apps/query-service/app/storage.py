from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.global_metadata import (
    GlobalDatasetMetadata,
    GlobalMetadataReader,
    GlobalPartitionMetadata,
)
from py_common.storage.ports import ReadableStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.models import DatasetRef
from app.settings import QueryServiceSettings


@dataclass(frozen=True)
class ResolvedDataset:
    view_name: str
    manifest: GlobalPartitionMetadata
    paths: list[str]
    include_filename: bool = False


def create_storage_registry(settings: QueryServiceSettings) -> StorageProviderRegistry:
    client = create_minio_client(settings.minio)
    return StorageProviderRegistry(adapters=[MinioStorageAdapter(client)])


class DatasetResolver:
    """Resolve logical identities from one validated global metadata document."""

    def __init__(
        self,
        reader: GlobalMetadataReader,
        settings: QueryServiceSettings,
    ) -> None:
        self._reader = reader
        self._settings = settings

    async def resolve_many(self, refs: list[DatasetRef]) -> list[ResolvedDataset]:
        document = await self._reader.read()
        resolved = []
        for ref in refs:
            manifest = document.resolve(ref.dataset, ref.partition)
            if manifest is None or manifest.status != "READY":
                raise ValueError(f"Dataset {ref.dataset!r} partition is not READY")
            if ref.data_version and manifest.dataVersion != ref.data_version:
                raise ValueError(
                    f"Dataset {ref.dataset!r} current version no longer matches request"
                )
            resolved.append(
                ResolvedDataset(
                    view_name=ref.view_name,
                    manifest=manifest,
                    paths=[self._physical_path(manifest.path)],
                )
            )
        return resolved

    def _physical_path(self, logical_path: str) -> str:
        if logical_path.startswith("/") or ".." in logical_path.split("/"):
            raise ValueError("Metadata contains an invalid logical data path")
        if self._settings.query_storage_scheme == "file":
            root = self._settings.query_local_data_root
            if not root:
                raise ValueError("query_local_data_root is required for file storage")
            candidate = (Path(root) / logical_path).resolve()
            root_path = Path(root).resolve()
            if not candidate.is_relative_to(root_path):
                raise ValueError("Resolved path escapes the configured data root")
            return str(candidate)
        return f"s3://{self._settings.minio.bucket}/{logical_path}"


class DatasetCatalogService:
    """Browser-safe metadata discovery backed by the global document."""

    def __init__(self, reader: GlobalMetadataReader) -> None:
        self._reader = reader

    async def list_datasets(self) -> list[dict[str, Any]]:
        document = await self._reader.read()
        return [self._safe_dataset(item) for item in document.datasets]

    async def list_partitions(
        self, dataset: str, *, offset: int = 0, limit: int = 200
    ) -> dict[str, Any]:
        document = await self._reader.read()
        section = document.dataset(dataset)
        if section is None:
            raise ValueError(f"Unsupported dataset: {dataset!r}")
        selected = section.partitions[offset : offset + limit]
        return {
            "items": [self._safe_partition(section.name, item) for item in selected],
            "offset": offset,
            "limit": limit,
            "total": len(section.partitions),
        }

    async def list_partition_options(
        self,
        dataset: str,
        key: str,
        filters: dict[str, str],
        *,
        limit: int = 200,
    ) -> list[Any]:
        document = await self._reader.read()
        section = document.dataset(dataset)
        if section is None:
            raise ValueError(f"Unsupported dataset: {dataset!r}")
        definitions = {item.name: item for item in section.partitionKeys}
        if key not in definitions or not definitions[key].queryable:
            raise ValueError(f"Unsupported partition key: {key!r}")
        if not set(filters).issubset(definitions):
            raise ValueError("Partition filters contain unsupported keys")
        values = {
            item.values[key]
            for item in section.partitions
            if key in item.values
            and all(
                str(item.values.get(name)) == value for name, value in filters.items()
            )
        }
        return sorted(values, key=str)[:limit]

    @staticmethod
    def _safe_dataset(dataset: GlobalDatasetMetadata) -> dict[str, Any]:
        return {
            "name": dataset.name,
            "label": dataset.label,
            "partitionKeys": [
                {
                    "name": item.name,
                    "type": item.type.value,
                    "required": item.required,
                    "order": item.order,
                    "queryable": item.queryable,
                    "label": item.label,
                }
                for item in dataset.partitionKeys
            ],
            "partitionCount": len(dataset.partitions),
        }

    @staticmethod
    def _safe_partition(
        dataset: str, partition: GlobalPartitionMetadata
    ) -> dict[str, Any]:
        return {
            "dataset": dataset,
            "partition": partition.values,
            "status": partition.status,
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


def build_metadata_services(
    registry: StorageProviderRegistry,
    settings: QueryServiceSettings,
) -> tuple[DatasetResolver, DatasetCatalogService]:
    reader = GlobalMetadataReader(
        registry.get_port(StorageProvider.MINIO, ReadableStorage),
        settings.minio.bucket,
    )
    return DatasetResolver(reader, settings), DatasetCatalogService(reader)
