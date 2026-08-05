from __future__ import annotations

from typing import Any

from py_common.config import validate_indicator_timeframe
from pydantic import BaseModel, Field, field_validator, model_validator

SUPPORTED_SIGNAL_STRATEGIES = ["TREND_MOMENTUM_V1"]


class SignalJobMessage(BaseModel):
    job_definition_id: str = Field(alias="jobDefinitionId")
    execution_id: str = Field(alias="executionId")
    parent_execution_id: str | None = Field(default=None, alias="parentExecutionId")
    source: str
    symbol_key: str = Field(alias="symbolKey")
    timeframe: str
    strategy: str
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("execution_id")
    @classmethod
    def validate_execution_id(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("executionId is required")
        return value

    @field_validator("timeframe")
    @classmethod
    def validate_enabled_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("strategy")
    @classmethod
    def validate_supported_strategy(cls, value: str) -> str:
        normalized = value.upper()
        if normalized not in SUPPORTED_SIGNAL_STRATEGIES:
            raise ValueError(
                "Signal calculation requires one supported strategy: "
                + ", ".join(SUPPORTED_SIGNAL_STRATEGIES)
            )
        return normalized

    @model_validator(mode="after")
    def validate_symbol_key_format(self) -> SignalJobMessage:
        self.parse_symbol_key()
        return self

    def parse_symbol_key(self) -> tuple[str, str]:
        parts = self.symbol_key.split("-", maxsplit=1)
        if len(parts) != 2 or not parts[0].strip() or not parts[1].strip():
            raise ValueError("symbolKey must use '<exchange>-<code>' format")
        return parts[0], parts[1]
