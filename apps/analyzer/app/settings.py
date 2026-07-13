from pathlib import Path

from py_common.config.loader import load_yaml
from py_common.config.models import (
    BaseAppSettings,
    KafkaSettings,
    MinioSettings,
    StorageSettings,
)
from py_common.config.paths import StockDataPaths

_SHARED_TOPICS_YAML = Path(__file__).resolve().parents[3] / "configs/shared/topics.yaml"
_SHARED_S3_PATHS_YAML = Path(__file__).resolve().parents[3] / "configs/shared/s3-paths.yaml"


class AppSettings(BaseAppSettings):
    """Application settings for the Analyzer service."""

    kafka: KafkaSettings = KafkaSettings()
    storage: StorageSettings = StorageSettings()
    minio: MinioSettings = MinioSettings()
    stock_data_paths: StockDataPaths = StockDataPaths.from_config({})

    def __init__(self, **values):
        super().__init__(**values)
        self._load_shared_configs()

    def _load_shared_configs(self) -> None:
        shared = load_yaml(_SHARED_TOPICS_YAML)
        spring = shared.get("spring", {})
        kafka_cfg = spring.get("kafka", {})

        self.kafka.bootstrap_servers = kafka_cfg.get(
            "bootstrap-servers", self.kafka.bootstrap_servers
        )

        minio_cfg = shared.get("min-io", {})
        if minio_cfg:
            self.minio.endpoint = minio_cfg.get("endpoint", self.minio.endpoint)
            self.minio.access_key = minio_cfg.get("access-key", self.minio.access_key)
            self.minio.secret_key = minio_cfg.get("secret-key", self.minio.secret_key)
            self.minio.bucket = minio_cfg.get("bucket", self.minio.bucket)

        s3_cfg = load_yaml(_SHARED_S3_PATHS_YAML)
        stock_data_cfg = s3_cfg.get("stock-data", s3_cfg)
        self.stock_data_paths = StockDataPaths.from_config(stock_data_cfg)


settings = AppSettings()
