from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from py_common.messaging.job_messages import WorkType


class JobStatus(StrEnum):
    SUCCESS = "SUCCESS"
    ERROR = "ERROR"
    PARTIAL_SUCCESS = "PARTIAL_SUCCESS"


class JobStatusMessage(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="allow")

    job_definition_id: str | None = Field(default=None, alias="jobDefinitionId")
    execution_id: str | None = Field(default=None, alias="executionId")
    parent_execution_id: str | None = Field(default=None, alias="parentExecutionId")
    work_type: WorkType = Field(alias="workType")
    work_key: str = Field(alias="workKey", min_length=1)
    status: JobStatus
    started_at: datetime = Field(alias="startedAt")
    finished_at: datetime = Field(alias="finishedAt")
    error_message: str | None = Field(default=None, alias="errorMessage")
    records_processed: int = Field(default=0, alias="recordsProcessed")
    duration_ms: int = Field(default=0, alias="durationMs")
    meta_json: dict[str, Any] = Field(default_factory=dict, alias="metaJson")
    new_offset: str | None = Field(default=None, alias="newOffset")


def calculate_duration_ms(started_at: datetime, finished_at: datetime) -> int:
    return int((finished_at - started_at).total_seconds() * 1000)


def build_job_error_status(
    *,
    raw: dict[str, Any],
    started_at: datetime,
    finished_at: datetime,
    error_message: str,
) -> JobStatusMessage:
    metadata = dict(raw.get("metaJson") or {})
    metadata.update(
        {
            key: value
            for key, value in raw.items()
            if key
            not in {
                "jobDefinitionId",
                "executionId",
                "parentExecutionId",
                "workType",
                "workKey",
                "metaJson",
            }
        }
    )
    metadata["recordsProcessed"] = 0
    metadata["errorMessage"] = error_message

    return JobStatusMessage(
        job_definition_id=str(raw.get("jobDefinitionId", "")),
        execution_id=str(raw.get("executionId", "")),
        parent_execution_id=raw.get("parentExecutionId"),
        work_type=raw.get("workType"),
        work_key=str(raw.get("workKey", "")),
        status=JobStatus.ERROR,
        started_at=started_at,
        finished_at=finished_at,
        error_message=error_message,
        records_processed=0,
        duration_ms=calculate_duration_ms(started_at, finished_at),
        meta_json=metadata,
    )


def utc_now() -> datetime:
    return datetime.now(UTC)
