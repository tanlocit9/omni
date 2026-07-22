from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from pydantic import BaseModel, Field, field_validator
from py_common.config import validate_indicator_timeframe

SUPPORTED_INDICATORS = ["MA20", "MA50", "RSI14", "MACD"]


class IndicatorJobMessage(BaseModel):
    job_definition_id: str = Field(alias="jobDefinitionId")
    execution_id: str = Field(alias="executionId")
    parent_execution_id: str | None = Field(default=None, alias="parentExecutionId")
    source: str
    indicator_source: str = Field(alias="indicatorSource")
    symbol_key: str = Field(alias="symbolKey")
    timeframe: str
    indicators: list[str]
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("timeframe")
    @classmethod
    def validate_enabled_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("indicators")
    @classmethod
    def validate_complete_indicator_set(cls, value: list[str]) -> list[str]:
        normalized = [indicator.upper() for indicator in value]
        if normalized != SUPPORTED_INDICATORS:
            raise ValueError(
                "Indicator calculation requires the complete supported set: "
                + ", ".join(SUPPORTED_INDICATORS)
            )
        return normalized

    def parse_symbol_key(self) -> tuple[str, str]:
        parts = self.symbol_key.split("-", maxsplit=1)
        if len(parts) != 2 or not parts[0].strip() or not parts[1].strip():
            raise ValueError("symbolKey must use '<exchange>-<code>' format")
        return parts[0], parts[1]


class JobStatusMessage(BaseModel):
    jobDefinitionId: str
    executionId: str
    parentExecutionId: str | None
    symbolKey: str
    status: str
    startedAt: datetime
    finishedAt: datetime
    errorMessage: str | None = None
    recordsProcessed: int = 0
    durationMs: int = 0
    metaJson: dict[str, Any] = Field(default_factory=dict)
    newOffset: str | None = None


def utc_now() -> datetime:
    return datetime.now(UTC)
