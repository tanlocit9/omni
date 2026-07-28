"""Shared application settings loaded from repository-level YAML configs."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Self

from pydantic import Field, PrivateAttr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from py_common.config.loader import load_yaml
from py_common.config.models import (
    KafkaSettings,
    MinioSettings,
    SchedulerSettings,
    StorageSettings,
)
from py_common.config.paths import StockDataPaths

_SHARED_TOPICS_RELATIVE_PATH = Path("configs/shared/topics.yaml")
_SHARED_S3_PATHS_RELATIVE_PATH = Path("configs/shared/s3-paths.yaml")


def find_repo_root(start: Path | None = None) -> Path:
    """Find the monorepo root by walking upward from ``start``.

    The root is identified by the presence of ``configs/shared``. If no marker
    is found, the current working directory is returned so callers still get a
    deterministic fallback.
    """
    current = (start or Path.cwd()).resolve()
    if current.is_file():
        current = current.parent

    for candidate in (current, *current.parents):
        if (candidate / _SHARED_TOPICS_RELATIVE_PATH).exists() or (
            candidate / _SHARED_S3_PATHS_RELATIVE_PATH
        ).exists():
            return candidate

    return Path.cwd().resolve()


class TopicSettings(BaseSettings):
    """Kafka topic names shared by Python services."""

    topic_sync_stock_prices: str = Field(default="topic-sync-stock-prices")
    topic_sync_symbols: str = Field(default="topic-sync-symbols")
    topic_upsert_symbols: str = Field(default="topic-upsert-symbols")
    topic_upsert_sectors: str = Field(default="topic-upsert-sectors")
    sync_job_status_topic: str = Field(default="topic-sync-job-status")
    topic_sync_indicators: str = Field(default="topic-sync-indicators")


class BaseAppSettings(BaseSettings):
    """Base settings for Python services using shared Omni configuration.

    Subclasses may declare service-specific fields only. Shared Kafka, MinIO,
    storage, scheduler, topic, and stock-data-path settings are populated from
    ``configs/shared/topics.yaml`` and ``configs/shared/s3-paths.yaml``.
    """

    model_config = SettingsConfigDict(
        env_file=(".env", ".env.local"),
        extra="ignore",
    )

    kafka: KafkaSettings = Field(default_factory=KafkaSettings)
    storage: StorageSettings = Field(default_factory=StorageSettings)
    minio: MinioSettings = Field(default_factory=MinioSettings)
    topics: TopicSettings = Field(default_factory=TopicSettings)
    scheduler: SchedulerSettings = Field(default_factory=SchedulerSettings)
    stock_data_paths: StockDataPaths = Field(
        default_factory=lambda: StockDataPaths.from_config({})
    )

    shared_config_root: Path | None = Field(default=None, exclude=True)

    _shared_topics: dict[str, Any] = PrivateAttr(default_factory=dict)
    _shared_s3_paths: dict[str, Any] = PrivateAttr(default_factory=dict)

    @model_validator(mode="after")
    def load_shared_config(self) -> Self:
        """Load shared YAML config after environment/default values are built."""
        root = self.shared_config_root or find_repo_root()
        self._shared_topics = load_yaml(root / _SHARED_TOPICS_RELATIVE_PATH)
        self._shared_s3_paths = load_yaml(root / _SHARED_S3_PATHS_RELATIVE_PATH)

        self._apply_topics_config()
        self._apply_s3_paths_config()
        self._apply_app_config()
        self._apply_flat_environment_overrides()
        return self

    @property
    def topic_sync_stock_prices(self) -> str:
        """Backward-compatible access to the sync stock prices topic."""
        return self.topics.topic_sync_stock_prices

    @property
    def topic_sync_symbols(self) -> str:
        """Backward-compatible access to the sync symbols topic."""
        return self.topics.topic_sync_symbols

    @property
    def topic_upsert_symbols(self) -> str:
        """Backward-compatible access to the upsert symbols topic."""
        return self.topics.topic_upsert_symbols

    @property
    def topic_upsert_sectors(self) -> str:
        """Backward-compatible access to the upsert sectors topic."""
        return self.topics.topic_upsert_sectors

    @property
    def topic_sync_indicators(self) -> str:
        """Backward-compatible access to the sync indicators topic."""
        return self.topics.topic_sync_indicators

    @property
    def sync_job_status_topic(self) -> str:
        """Backward-compatible access to the sync job status topic."""
        return self.topics.sync_job_status_topic

    def get_symbols_path(self, exchange: str) -> str:
        """Build a shared object path for symbol metadata."""
        return self.stock_data_paths.symbols(exchange)

    def get_eod_path(self, exchange: str, code: str) -> str:
        """Build a shared object path for EOD price data."""
        return self.stock_data_paths.eod(exchange, code)

    def get_indicators_path(
        self,
        source: str,
        timeframe: str,
        exchange: str,
        code: str,
    ) -> str:
        """Build a shared object path for indicator data."""
        return self.stock_data_paths.indicators(source, timeframe, exchange, code)

    def _apply_topics_config(self) -> None:
        kafka_cfg = self._shared_topics.get("kafka", {})
        topics_cfg = kafka_cfg.get("topics", {})

        self.kafka.bootstrap_servers = kafka_cfg.get(
            "bootstrap-servers",
            self.kafka.bootstrap_servers,
        )

        self.topics.topic_sync_stock_prices = topics_cfg.get(
            "topic-sync-stock-prices",
            self.topics.topic_sync_stock_prices,
        )
        self.topics.topic_sync_symbols = topics_cfg.get(
            "topic-sync-symbols",
            self.topics.topic_sync_symbols,
        )
        self.topics.topic_upsert_symbols = topics_cfg.get(
            "topic-upsert-symbols",
            self.topics.topic_upsert_symbols,
        )
        self.topics.topic_upsert_sectors = topics_cfg.get(
            "topic-upsert-sectors",
            self.topics.topic_upsert_sectors,
        )
        self.topics.sync_job_status_topic = topics_cfg.get(
            "topic-sync-job-status",
            self.topics.sync_job_status_topic,
        )
        self.topics.topic_sync_indicators = topics_cfg.get(
            "topic-sync-indicators",
            self.topics.topic_sync_indicators,
        )

        minio_cfg = self._shared_topics.get("min-io", {})
        if minio_cfg:
            self.minio.endpoint = minio_cfg.get("endpoint", self.minio.endpoint)
            self.minio.access_key = minio_cfg.get("access-key", self.minio.access_key)
            self.minio.secret_key = minio_cfg.get("secret-key", self.minio.secret_key)
            self.minio.bucket = minio_cfg.get("bucket", self.minio.bucket)

    def _apply_app_config(self) -> None:
        app_cfg = self._shared_topics.get("app", {})
        scheduler_cfg = app_cfg.get("scheduler", {})
        self.scheduler.zone = scheduler_cfg.get("zone", self.scheduler.zone)

    def _apply_s3_paths_config(self) -> None:
        stock_data_cfg = self._shared_s3_paths.get("stock-data", self._shared_s3_paths)
        self.minio.bucket = stock_data_cfg.get("bucket", self.minio.bucket)
        self.stock_data_paths = StockDataPaths.from_config(stock_data_cfg)

    def _apply_flat_environment_overrides(self) -> None:
        """Apply shared flat env vars used by Java, Python, and Compose."""
        self.kafka.bootstrap_servers = os.getenv(
            "KAFKA_BOOTSTRAP_SERVERS",
            self.kafka.bootstrap_servers,
        )
        self.minio.endpoint = os.getenv("MINIO_ENDPOINT", self.minio.endpoint)
        self.minio.access_key = os.getenv("MINIO_ACCESS_KEY", self.minio.access_key)
        self.minio.secret_key = os.getenv("MINIO_SECRET_KEY", self.minio.secret_key)
        self.minio.bucket = os.getenv("MINIO_BUCKET", self.minio.bucket)
