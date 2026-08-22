from __future__ import annotations

from datetime import date
from typing import Any

from py_common.config import validate_indicator_timeframe
from py_common.messaging import JobMessage
from pydantic import ConfigDict, Field, field_validator, model_validator

SUPPORTED_SECTOR_TRANSITION_STRATEGIES = ["SECTOR_TRANSITION_V1"]


class SectorTransitionAnalyzeJobMessage(JobMessage):
    evaluation_date: date = Field(alias="evaluationDate")
    sector_codes: list[str] = Field(alias="sectorCodes")
    focus_sector_codes: list[str] = Field(
        default_factory=list, alias="focusSectorCodes"
    )
    sector_level: int = Field(alias="sectorLevel")
    timeframe: str
    strategy: str = "SECTOR_TRANSITION_V1"
    prediction_horizons: list[int] = Field(alias="predictionHorizons")
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)

    @field_validator("timeframe")
    @classmethod
    def validate_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("strategy")
    @classmethod
    def validate_strategy(cls, value: str) -> str:
        normalized = value.strip().upper()
        if normalized not in SUPPORTED_SECTOR_TRANSITION_STRATEGIES:
            raise ValueError(f"Unsupported sector transition strategy: {value}")
        return normalized

    @field_validator("sector_level")
    @classmethod
    def validate_sector_level(cls, value: int) -> int:
        if value <= 0:
            raise ValueError("sectorLevel must be positive")
        return value

    @model_validator(mode="after")
    def validate_contract(self) -> SectorTransitionAnalyzeJobMessage:
        self.sector_codes = _normalize_non_empty_codes(self.sector_codes, "sectorCodes")
        self.focus_sector_codes = _normalize_focus_codes(
            self.focus_sector_codes, self.sector_codes
        )
        self.prediction_horizons = _normalize_horizons(self.prediction_horizons)
        return self


class SectorTransitionOutcomeEvaluationJobMessage(JobMessage):
    evaluation_date: date = Field(alias="evaluationDate")
    sector_codes: list[str] = Field(alias="sectorCodes")
    focus_sector_codes: list[str] = Field(
        default_factory=list, alias="focusSectorCodes"
    )
    sector_level: int = Field(alias="sectorLevel")
    timeframe: str
    strategy: str = "SECTOR_TRANSITION_V1"
    prediction_horizons: list[int] = Field(alias="predictionHorizons")
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)

    @field_validator("timeframe")
    @classmethod
    def validate_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("strategy")
    @classmethod
    def validate_strategy(cls, value: str) -> str:
        normalized = value.strip().upper()
        if normalized not in SUPPORTED_SECTOR_TRANSITION_STRATEGIES:
            raise ValueError(f"Unsupported sector transition strategy: {value}")
        return normalized

    @field_validator("sector_level")
    @classmethod
    def validate_sector_level(cls, value: int) -> int:
        if value <= 0:
            raise ValueError("sectorLevel must be positive")
        return value

    @model_validator(mode="after")
    def validate_contract(self) -> SectorTransitionOutcomeEvaluationJobMessage:
        self.sector_codes = _normalize_non_empty_codes(self.sector_codes, "sectorCodes")
        self.focus_sector_codes = _normalize_focus_codes(
            self.focus_sector_codes, self.sector_codes
        )
        self.prediction_horizons = _normalize_horizons(self.prediction_horizons)
        return self


def _normalize_non_empty_codes(values: list[str], field_name: str) -> list[str]:
    normalized = sorted({value.strip().upper() for value in values if value.strip()})
    if not normalized:
        raise ValueError(f"{field_name} must include at least one code")
    return normalized


def _normalize_focus_codes(values: list[str], sector_codes: list[str]) -> list[str]:
    normalized = sorted({value.strip().upper() for value in values if value.strip()})
    focus = normalized or sector_codes
    invalid = sorted(set(focus) - set(sector_codes))
    if invalid:
        raise ValueError(f"focusSectorCodes must be within sectorCodes: {invalid}")
    return focus


def _normalize_horizons(values: list[int]) -> list[int]:
    normalized = sorted({int(value) for value in values})
    if not normalized or any(value <= 0 for value in normalized):
        raise ValueError(
            "predictionHorizons must include positive trading-session offsets"
        )
    return normalized
