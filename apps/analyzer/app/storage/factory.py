from py_common.storage.adapters.factory import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.registry import StorageProviderRegistry

from app.settings import AppSettings


def create_storage_registry(settings: AppSettings) -> StorageProviderRegistry:
    """Create and configure the storage provider registry."""
    minio_client = create_minio_client(settings.minio)
    minio_adapter = MinioStorageAdapter(minio_client)

    return StorageProviderRegistry(adapters=[minio_adapter])

