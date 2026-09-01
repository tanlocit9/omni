from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.manifest import DatasetManifest, ManifestReader
from py_common.storage.ports import ListableStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.models import DatasetRef
from app.settings import QueryServiceSettings


@dataclass(frozen=True)
class ResolvedDataset:
    view_name: str
    manifest: DatasetManifest
    paths: list[str]
    include_filename: bool = False


def create_storage_registry(settings: QueryServiceSettings) -> StorageProviderRegistry:
    client = create_minio_client(settings.minio)
    return StorageProviderRegistry(adapters=[MinioStorageAdapter(client)])


class DatasetResolver:
    """Resolve logical dataset identities through canonical READY manifests."""

    def __init__(
        self,
        reader: ManifestReader,
        settings: QueryServiceSettings,
    ) -> None:
        self._reader = reader
        self._settings = settings

    async def resolve_many(self, refs: list[DatasetRef]) -> list[ResolvedDataset]:
        resolved = []
        for ref in refs:
            manifest = await self._reader.read_manifest(ref.dataset, ref.partition)
            if manifest.status != "READY":
                raise ValueError(f"Dataset {ref.dataset!r} is not READY")
            if ref.data_version and manifest.dataVersion != ref.data_version:
                raise ValueError(
                    f"Dataset {ref.dataset!r} READY version no longer matches request"
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
            raise ValueError("Manifest contains an invalid logical data path")
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
    """Metadata-only catalog and READY partition listing for the Explorer."""

    def __init__(
        self,
        reader: ManifestReader,
        listable: ListableStorage,
        bucket: str,
    ) -> None:
        self._reader = reader
        self._listable = listable
        self._bucket = bucket

    async def list_datasets(self) -> list[dict[str, str | None]]:
        catalog = await self._reader.read_catalog()
        return [
            {
                "name": item.name,
                "description": item.description,
                "dataPrefix": item.dataPrefix,
            }
            for item in catalog.datasets
        ]

    async def list_partitions(self, dataset: str) -> list[DatasetManifest]:
        prefix = f"_metadata/datasets/{dataset}/"
        objects = await self._listable.list_objects(self._bucket, prefix)
        partitions: list[dict[str, str]] = []
        for object_name in objects:
            if not object_name.endswith("/READY.json"):
                continue
            relative = object_name[len(prefix) : -len("/READY.json")]
            if relative == "_default":
                partitions.append({})
                continue
            partition: dict[str, str] = {}
            valid = True
            for segment in relative.split("/"):
                if "=" not in segment:
                    valid = False
                    break
                key, value = segment.split("=", 1)
                partition[key] = value
            if valid:
                partitions.append(partition)
        return [await self._reader.read_manifest(dataset, item) for item in partitions]


def build_metadata_services(
    registry: StorageProviderRegistry,
    settings: QueryServiceSettings,
) -> tuple[DatasetResolver, DatasetCatalogService]:
    reader = ManifestReader(
        registry=registry,
        provider=StorageProvider.MINIO,
        bucket=settings.minio.bucket,
    )
    listable = registry.get_port(StorageProvider.MINIO, ListableStorage)
    return (
        DatasetResolver(reader, settings),
        DatasetCatalogService(reader, listable, settings.minio.bucket),
    )
