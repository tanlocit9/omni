from minio import Minio
from py_common.config.models import MinioSettings, StockDataPathsSettings
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.registry import StorageProviderRegistry

from app.settings import AppSettings


def create_minio_client(settings: MinioSettings) -> Minio:
    """Create a MinIO client."""
    return Minio(
        endpoint=settings.endpoint,
        access_key=settings.access_key,
        secret_key=settings.secret_key,
        secure=settings.secure,
    )


def create_storage_registry(settings: AppSettings) -> StorageProviderRegistry:
    """Create and configure the storage provider registry."""
    minio_client = create_minio_client(settings.minio)
    minio_adapter = MinioStorageAdapter(minio_client)

    return StorageProviderRegistry(adapters=[minio_adapter])


def create_stock_data_paths(
    settings: StockDataPathsSettings,
) -> StockDataPathsSettings:
    """Create stock data paths."""
    return StockDataPathsSettings(
        symbols_base=settings.symbols_base,
        symbols_pattern=settings.symbols_pattern,
        eod_base=settings.eod_base,
        eod_pattern=settings.eod_pattern,
        indicators_base=settings.indicators_base,
        indicators_pattern=settings.indicators_pattern,
    )