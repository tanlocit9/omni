from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


class JobMessage(BaseModel):
    """Base payload fields shared by scheduler-driven Kafka job messages."""

    job_definition_id: str = Field(alias="jobDefinitionId")
    execution_id: str = Field(alias="executionId")
    parent_execution_id: str | None = Field(default=None, alias="parentExecutionId")
    source: str

    @field_validator("execution_id")
    @classmethod
    def validate_execution_id(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("executionId is required")
        return value
