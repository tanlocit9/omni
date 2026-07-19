import logging
from pathlib import Path

import yaml
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger(__name__)

_SHARED_TOPICS_YAML = (
    Path(__file__).resolve().parents[3] / "configs/shared/topics.yaml"
)
_SHARED_S3_PATHS_YAML = (
    Path(__file__).resolve().parents[3] / "configs/shared/s3-paths.yaml"
)


def _load_shared_yaml(path: Path) -> dict:
    if not path.exists():
        return {}
    return yaml.safe_load(path.read_text()) or {}


_shared = _load_shared_yaml(_SHARED_TOPICS_YAML)
_s3_config = _load_shared_yaml(_SHARED_S3_PATHS_YAML)
_spring = _shared.get("spring", {})
_kafka = _spring.get("kafka", {})
_topics = _kafka.get("topics", {})
_minio = _shared.get("min-io", {})

_stock_data = _s3_config.get("stock-data", {})
_s3_paths = _stock_data.get("paths", {})


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    kafka_bootstrap: str = Field(
        default=_kafka.get("bootstrap-servers", "localhost:9092"),
    )
    kafka_consumer_group_id: str = "analyzer-group"
    kafka_retry_interval_seconds: int = 3

    topic_sync_stock_prices: str = Field(
        default=_topics.get("topic-sync-stock-prices", "topic-sync-stock-prices"),
    )
    topic_sync_symbols: str = Field(
        default=_topics.get("topic-sync-symbols", "topic-sync-symbols"),
    )
    topic_upsert_symbols: str = Field(
        default=_topics.get("topic-upsert-symbols", "topic-upsert-symbols"),
    )
    sync_job_status_topic: str = Field(
        default=_topics.get("topic-sync-job-status", "topic-sync-job-status"),
    )

    minio_endpoint: str = Field(default=_minio.get("endpoint", "localhost:9000"))
    minio_access_key: str = Field(default=_minio.get("access-key", "minioadmin"))
    minio_secret_key: str = Field(default=_minio.get("secret-key", "minioadmin"))
    minio_bucket: str = Field(
        default=_minio.get("bucket") or _stock_data.get("bucket", "stock-data")
    )

    default_stock_source: str = "VND"

    def get_symbols_path(self, exchange: str) -> str:
        """Build S3 path for symbol metadata file.
        
        Args:
            exchange: Exchange name (will be normalized to lowercase)
            
        Returns:
            Path like: symbols/hose.parquet
        """
        symbols_cfg = _s3_paths.get("symbols", {})
        base = symbols_cfg.get("base", "symbols/")
        pattern = symbols_cfg.get("pattern", "{exchange}.parquet")
        return base + pattern.format(exchange=exchange.lower())

    def get_eod_path(self, exchange: str, code: str) -> str:
        """Build S3 path for EOD price data file.
        
        Args:
            exchange: Exchange name (will be normalized to lowercase)
            code: Stock ticker code (will be normalized to lowercase)
            
        Returns:
            Path like: eod/hose/hpg.parquet
        """
        eod_cfg = _s3_paths.get("eod", {})
        base = eod_cfg.get("base", "eod/")
        pattern = eod_cfg.get("pattern", "{exchange}/{code}.parquet")
        return base + pattern.format(exchange=exchange.lower(), code=code.lower())


settings = Settings()

logger.info("Ingestor settings loaded: %s", settings.model_dump())
