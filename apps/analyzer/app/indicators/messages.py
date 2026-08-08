from __future__ import annotations

from typing import Any

from py_common.config import validate_indicator_timeframe
from py_common.messaging import JobMessage
from pydantic import Field, field_validator

SUPPORTED_INDICATORS = ["MA20", "MA50", "RSI14", "MACD", "ICHIMOKU"]


class IndicatorJobMessage(JobMessage):
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
    def validate_supported_indicator_set(cls, value: list[str]) -> list[str]:
        normalized = [indicator.upper() for indicator in value]
        unsupported = [
            indicator
            for indicator in normalized
            if indicator not in SUPPORTED_INDICATORS
        ]
        if not normalized or unsupported:
            raise ValueError(
                "Indicator calculation requires one or more supported indicators: "
                + ", ".join(SUPPORTED_INDICATORS)
            )
        return normalized

    def parse_symbol_key(self) -> tuple[str, str]:
        parts = self.symbol_key.split("-", maxsplit=1)
        if len(parts) != 2 or not parts[0].strip() or not parts[1].strip():
            raise ValueError("symbolKey must use '<exchange>-<code>' format")
        return parts[0], parts[1]
