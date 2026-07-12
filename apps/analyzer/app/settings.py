from py_common.config.models import (
    BaseAppSettings,
    KafkaSettings,
    MinioSettings,
    StorageSettings,
    StockDataPathsSettings,
)


class AppSettings(BaseAppSettings):
    """Application settings for the Analyzer service."""

    kafka: KafkaSettings = KafkaSettings()
    storage: StorageSettings = StorageSettings()
    minio: MinioSettings = MinioSettings()
    stock_data_paths: StockDataPathsSettings = StockDataPathsSettings()


settings = AppSettings()