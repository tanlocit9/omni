import logging
from pathlib import Path
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from py_common.config.loader import load_yaml
from py_common.config.models import KafkaSettings, MinioSettings, StorageSettings
from py_common.config.paths import StockDataPaths

logger = logging.getLogger(__name__)

_SHARED_TOPICS_YAML = (
    Path(__file__).resolve().parents[3] / "configs/shared/topics.yaml"
)
_SHARED_S3_PATHS_YAML = (
    Path(__file__).resolve().parents[3] / "configs/shared/s3-paths.yaml"
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Kafka
    kafka: KafkaSettings = Field(default_factory=KafkaSettings)
    kafka_retry_interval_seconds: int = 3

    topic_sync_stock_prices: str = "topic-sync-stock-prices"
    topic_sync_symbols: str = "topic-sync-symbols"
    topic_upsert_symbols: str = "topic-upsert-symbols"
    sync_job_status_topic: str = "topic-sync-job-status"

    # Storage
    storage: StorageSettings = Field(default_factory=StorageSettings)
    minio: MinioSettings = Field(default_factory=MinioSettings)

    stock_data_paths: StockDataPaths | None = None
    default_stock_source: str = "VND"

    def __init__(self, **values):
        super().__init__(**values)
        self._load_shared_configs()

    def _load_shared_configs(self) -> None:
        # Load topics and basic kafka/minio from topics.yaml
        shared = load_yaml(_SHARED_TOPICS_YAML)
        spring = shared.get("spring", {})
        kafka_cfg = spring.get("kafka", {})
        topics = kafka_cfg.get("topics", {})

        self.kafka.bootstrap_servers = kafka_cfg.get(
            "bootstrap-servers", self.kafka.bootstrap_servers
        )

        self.topic_sync_stock_prices = topics.get(
            "topic-sync-stock-prices", self.topic_sync_stock_prices
        )
        self.topic_sync_symbols = topics.get(
            "topic-sync-symbols", self.topic_sync_symbols
        )
        self.topic_upsert_symbols = topics.get(
            "topic-upsert-symbols", self.topic_upsert_symbols
        )
        self.sync_job_status_topic = topics.get(
            "topic-sync-job-status", self.sync_job_status_topic
        )

        minio_cfg = shared.get("min-io", {})
        if minio_cfg:
            self.minio.endpoint = minio_cfg.get(
                "endpoint", self.minio.endpoint
            )
            self.minio.access_key = minio_cfg.get(
                "access-key", self.minio.access_key
            )
            self.minio.secret_key = minio_cfg.get(
                "secret-key", self.minio.secret_key
            )
            self.minio.bucket = minio_cfg.get(
                "bucket", self.minio.bucket
            )

        # Load S3 paths
        s3_cfg = load_yaml(_SHARED_S3_PATHS_YAML)
        self.stock_data_paths = StockDataPaths.from_config(s3_cfg)


settings = Settings()

logger.info("Ingestor settings loaded: %s", settings.model_dump())