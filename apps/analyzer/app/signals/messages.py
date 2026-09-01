from __future__ import annotations

from typing import Any

from py_common.config import validate_indicator_timeframe
from py_common.messaging import JobMessage
from pydantic import ConfigDict, Field, field_validator, model_validator

SUPPORTED_SIGNAL_STRATEGIES = ["TREND_MOMENTUM_V1", "ICHIMOKU_V1"]


class SignalEvaluationJobMessage(JobMessage):
    exchange: str
    timeframe: str
    strategy: str
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)

    @field_validator("exchange")
    @classmethod
    def validate_exchange(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("exchange is required")
        return value.strip().upper()

    @field_validator("timeframe")
    @classmethod
    def validate_timeframe(cls, value: str) -> str:
        return validate_indicator_timeframe(value).value

    @field_validator("strategy")
    @classmethod
    def validate_supported_strategy(cls, value: str) -> str:
        strategy = value.upper()
        if strategy not in SUPPORTED_SIGNAL_STRATEGIES:
            raise ValueError(f"Unsupported signal strategy: {value}")
        return strategy


class SignalJobMessage(JobMessage):
    symbol_key: str = Field(alias="symbolKey")
    timeframe: str
    strategy: str
    metadata: dict[str, Any] = Field(default_factory=dict)

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
