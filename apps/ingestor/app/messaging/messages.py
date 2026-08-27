from __future__ import annotations

from datetime import datetime
from typing import Any

from py_common.messaging import WorkType
from pydantic import BaseModel, Field, field_validator


class JobMessage(BaseModel):
    job_definition_id: str = Field(
        validation_alias="jobDefinitionId",
        serialization_alias="jobDefinitionId",
    )
    execution_id: str = Field(
        validation_alias="executionId",
        serialization_alias="executionId",
    )
    parent_execution_id: str | None = Field(
        default=None,
        validation_alias="parentExecutionId",
        serialization_alias="parentExecutionId",
    )
    source: str | None = None
    work_type: WorkType = Field(alias="workType")
    work_key: str = Field(alias="workKey", min_length=1)
    metadata: dict[str, Any] = Field(default_factory=dict)

    @property
    def status_payload(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True)


class SymbolJobMessage(JobMessage):
    symbol_key: str = Field(alias="symbolKey")
    from_offset: datetime | None = Field(default=None, alias="fromOffset")
    to_offset: datetime | None = Field(default=None, alias="toOffset")

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


class SyncSymbolsJobMessage(JobMessage):
    exchange: str
    timestamp: datetime | None = None

    @field_validator("exchange")
    @classmethod
    def normalize_exchange(cls, value: str) -> str:
        normalized = value.strip().upper()
        if not normalized:
            raise ValueError("exchange must not be blank")
        return normalized

    @property
    def expected_count(self) -> int | None:
        raw_value = self.metadata.get("symbolCount")
        if raw_value is None:
            return None
        return int(raw_value)

    @property
    def include_sector_classification(self) -> bool:
        return bool(self.metadata.get("includeSectorClassification", False))
