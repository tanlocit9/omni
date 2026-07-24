from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class JobStatus(StrEnum):
    SUCCESS = "SUCCESS"
    ERROR = "ERROR"
    PARTIAL_SUCCESS = "PARTIAL_SUCCESS"


class JobStatusMessage(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    job_definition_id: str = Field(alias="jobDefinitionId")
    execution_id: str = Field(alias="executionId")
    parent_execution_id: str | None = Field(default=None, alias="parentExecutionId")
    symbol_key: str = Field(alias="symbolKey")
    status: JobStatus
    started_at: datetime = Field(alias="startedAt")
    finished_at: datetime = Field(alias="finishedAt")
    error_message: str | None = Field(default=None, alias="errorMessage")
    records_processed: int = Field(default=0, alias="recordsProcessed")
    duration_ms: int = Field(default=0, alias="durationMs")
    meta_json: dict[str, Any] = Field(default_factory=dict, alias="metaJson")
    new_offset: str | None = Field(default=None, alias="newOffset")


def utc_now() -> datetime:
    return datetime.now(UTC)
