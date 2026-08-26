from py_common.config import BaseAppSettings
from pydantic import Field


class AppSettings(BaseAppSettings):
    """Application settings for the Analyzer service."""

    indicator_kafka_enabled: bool = Field(default=True)
    metadata_kafka_enabled: bool = Field(default=True)
    signal_kafka_enabled: bool = Field(default=True)
    signal_evaluation_kafka_enabled: bool = Field(default=True)
    sector_wave_symbol_features_kafka_enabled: bool = Field(default=True)
    sector_wave_sector_features_kafka_enabled: bool = Field(default=True)
    sector_rotation_backtest_kafka_enabled: bool = Field(default=True)
    sector_transition_analyze_kafka_enabled: bool = Field(default=True)
    sector_transition_outcome_kafka_enabled: bool = Field(default=True)
    sector_wave_symbol_exchanges: list[str] = Field(
        default_factory=lambda: ["HOSE", "HNX", "UPCOM"]
    )


settings = AppSettings()
