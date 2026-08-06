from py_common.config import BaseAppSettings
from pydantic import Field


class AppSettings(BaseAppSettings):
    """Application settings for the Analyzer service."""

    indicator_kafka_enabled: bool = Field(default=True)
    signal_kafka_enabled: bool = Field(default=True)
    signal_evaluation_kafka_enabled: bool = Field(default=True)


settings = AppSettings()
