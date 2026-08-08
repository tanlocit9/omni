from __future__ import annotations

from typing import Any

from py_common.config import validate_indicator_timeframe
from py_common.messaging import JobMessage
from pydantic import Field, field_validator, model_validator

SUPPORTED_SECTOR_WAVE_STRATEGIES = {"SECTOR_WAVE_V1"}


class SectorWaveSymbolFeatureJobMessage(JobMessage):
    symbol_key: str = Field(alias="symbolKey")
    timeframe: str
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("timeframe")
    @classmethod
    def validate_enabled_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("symbol_key")
    @classmethod
    def validate_symbol_key(cls, value: str) -> str:
        parts = value.split("-", maxsplit=1)
        if len(parts) != 2 or not parts[0].strip() or not parts[1].strip():
            raise ValueError("symbolKey must use '<exchange>-<code>' format")
        return value

    def parse_symbol_key(self) -> tuple[str, str]:
        exchange, code = self.symbol_key.split("-", maxsplit=1)
        return exchange, code


class SectorWaveSectorFeatureJobMessage(JobMessage):
    sector_code: str = Field(alias="sectorCode")
    sector_level: int = Field(alias="sectorLevel")
    timeframe: str
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("timeframe")
    @classmethod
    def validate_enabled_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("sector_code")
    @classmethod
    def validate_sector_code(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("sectorCode is required")
        return value.strip().upper()

    @field_validator("sector_level")
    @classmethod
    def validate_sector_level(cls, value: int) -> int:
        if value < 1:
            raise ValueError("sectorLevel must be greater than or equal to 1")
        return value


class SectorRotationBacktestJobMessage(JobMessage):
    sector_codes: list[str] = Field(alias="sectorCodes")
    sector_level: int = Field(alias="sectorLevel")
    timeframe: str
    strategy: str
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("timeframe")
    @classmethod
    def validate_enabled_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("strategy")
    @classmethod
    def validate_strategy(cls, value: str) -> str:
        strategy = value.strip().upper()
        if strategy not in SUPPORTED_SECTOR_WAVE_STRATEGIES:
            raise ValueError("Unsupported sector rotation strategy")
        return strategy

    @model_validator(mode="after")
    def validate_contract(self) -> SectorRotationBacktestJobMessage:
        if self.sector_level < 1:
            raise ValueError("sectorLevel must be greater than or equal to 1")
        self.sector_codes = [
            code.strip().upper() for code in self.sector_codes if code.strip()
        ]
        if not self.sector_codes:
            raise ValueError("sectorCodes must contain at least one sector")
        return self
