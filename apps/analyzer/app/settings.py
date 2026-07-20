from pydantic import Field
from py_common.config import BaseAppSettings


class AppSettings(BaseAppSettings):
    """Application settings for the Analyzer service."""

    indicator_kafka_enabled: bool = Field(default=True)


settings = AppSettings()
