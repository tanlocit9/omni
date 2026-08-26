"""Pydantic settings models for Omni services."""

from pydantic import BaseModel, Field

from py_common.config.constants import ConsumerGroup
from py_common.config.paths import StockDataPaths


class KafkaSettings(BaseModel):
    """Kafka connection and configuration settings.

    Attributes:
        bootstrap_servers: Kafka broker addresses (comma-separated)
        consumer_group: Consumer group prefix for this service

    Examples:
        >>> settings = KafkaSettings(
        ...     bootstrap_servers="localhost:9092",
        ...     consumer_group=ConsumerGroup.INGESTOR
        ... )
        >>> settings.bootstrap_servers
        'localhost:9092'
    """

    bootstrap_servers: str = Field(
        default="localhost:9092",
        description="Kafka bootstrap servers (comma-separated)",
    )
    consumer_group: ConsumerGroup = Field(
        default=ConsumerGroup.INGESTOR,
        description="Consumer group prefix for this service",
    )


class MinioSettings(BaseModel):
    """MinIO object storage connection settings.

    Attributes:
        enabled: Whether MinIO adapter is enabled
        endpoint: MinIO server endpoint (host:port)
        access_key: Access key for authentication
        secret_key: Secret key for authentication
        bucket: Default bucket name for storage operations
        secure: Whether to use HTTPS for connections

    Examples:
        >>> settings = MinioSettings(
        ...     endpoint="localhost:9000",
        ...     access_key="minioadmin",
        ...     secret_key="minioadmin",
        ...     bucket="stock-data"
        ... )
        >>> settings.endpoint
        'localhost:9000'
    """

    enabled: bool = Field(
        default=True,
        description="Whether MinIO adapter is enabled",
    )
    endpoint: str = Field(
        default="",
        description="MinIO endpoint (host:port)",
    )
    access_key: str = Field(
        default="",
        description="MinIO access key",
    )
    secret_key: str = Field(
        default="",
        description="MinIO secret key",
    )
    bucket: str = Field(
        default="",
        description="Default bucket name",
    )
    secure: bool = Field(
        default=False,
        description="Use HTTPS for connections",
    )


class SchedulerSettings(BaseModel):
    """Scheduler-related application settings.

    Attributes:
        zone: IANA timezone identifier used for scheduler defaults

    Examples:
        >>> settings = SchedulerSettings(zone="Asia/Ho_Chi_Minh")
        >>> settings.zone
        'Asia/Ho_Chi_Minh'
    """

    zone: str = Field(
        default="Asia/Ho_Chi_Minh",
        description="Default scheduler timezone (IANA timezone identifier)",
    )


class StorageSettings(BaseModel):
    """Storage provider configuration.

    Attributes:
        provider: Storage provider to use (minio, aws_s3)

    Examples:
        >>> from py_common.storage import StorageProvider
        >>> settings = StorageSettings(provider=StorageProvider.MINIO)
        >>> settings.provider
        <StorageProvider.MINIO: 'minio'>
    """

    provider: str = Field(
        default="minio",
        description="Storage provider: minio or aws_s3",
    )


class TopicSettings(BaseModel):
    """Kafka topic names shared by Omni services."""

    topic_sync_stock_prices: str = Field(default="topic-sync-stock-prices")
    topic_sync_symbols: str = Field(default="topic-sync-symbols")
    topic_upsert_symbols: str = Field(default="topic-upsert-symbols")
    topic_upsert_sectors: str = Field(default="topic-upsert-sectors")
    sync_job_status_topic: str = Field(default="topic-sync-job-status")
    topic_sync_indicators: str = Field(default="topic-sync-indicators")
    topic_sync_metadata: str = Field(default="topic-sync-metadata")
    topic_sync_signals: str = Field(default="topic-sync-signals")
    topic_evaluate_signals: str = Field(default="topic-evaluate-signals")
    topic_signal_notifications: str = Field(default="topic-signal-notifications")
    topic_sector_transition_analyze: str = Field(
        default="topic-sector-transition-analyze"
    )
    topic_sector_transition_evaluate_outcomes: str = Field(
        default="topic-sector-transition-evaluate-outcomes"
    )


StockDataPathsSettings = StockDataPaths
