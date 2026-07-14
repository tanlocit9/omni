import logging

from py_common.config import BaseAppSettings

logger = logging.getLogger(__name__)


class Settings(BaseAppSettings):
    """Application settings for the Ingestor service."""

    kafka_retry_interval_seconds: int = 3
    default_stock_source: str = "VND"


settings = Settings()

logger.info("Ingestor settings loaded: %s", settings.model_dump())
