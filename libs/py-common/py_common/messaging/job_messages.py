from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field, field_validator


class WorkType(StrEnum):
    SYMBOL = "SYMBOL"
    SECTOR = "SECTOR"
    EXCHANGE = "EXCHANGE"
    GLOBAL = "GLOBAL"


class JobMessage(BaseModel):
    """Base payload fields shared by scheduler-driven Kafka job messages."""

    job_definition_id: str = Field(alias="jobDefinitionId")
    execution_id: str = Field(alias="executionId")
    parent_execution_id: str | None = Field(default=None, alias="parentExecutionId")
    source: str
    work_type: WorkType = Field(alias="workType")
    work_key: str = Field(alias="workKey", min_length=1)

    @field_validator("execution_id")
    @classmethod
    def validate_execution_id(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("executionId is required")
        return value
