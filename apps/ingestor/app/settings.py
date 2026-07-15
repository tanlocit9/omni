import logging

from py_common.config import BaseAppSettings
from py_common.config.constants import ConsumerGroup

logger = logging.getLogger(__name__)


class Settings(BaseAppSettings):
    """Application settings for the Ingestor service."""

    kafka_retry_interval_seconds: int = 3
    default_stock_source: str = "VND"


settings = Settings()
settings.kafka.consumer_group = ConsumerGroup.INGESTOR

logger.info("Ingestor settings loaded: %s", settings.model_dump())
