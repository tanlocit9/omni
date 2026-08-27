from __future__ import annotations

from datetime import datetime
from typing import Any

from py_common.messaging import (
    JobStatus,
    JobStatusMessage,
    calculate_duration_ms,
    utc_now,
)


def build_status(
    payload: dict[str, Any],
    started_at: datetime,
    status: JobStatus | str,
    records_inserted: int = 0,
    total_records: int = 0,
    new_offset: str | None = None,
    error_message: str | None = None,
) -> JobStatusMessage:
    finished_at = utc_now()
    normalized_status = _normalize_status(status)
    meta_json = {
        "recordsInserted": records_inserted,
        "totalRecords": total_records,
    }

    return JobStatusMessage(
        job_definition_id=_optional_str(payload.get("jobDefinitionId")),
        execution_id=_optional_str(payload.get("executionId")),
        parent_execution_id=_optional_str(payload.get("parentExecutionId")),
        work_type=payload.get("workType"),
        work_key=_optional_str(payload.get("workKey")),
        status=normalized_status,
        meta_json=meta_json,
        new_offset=new_offset,
        started_at=started_at,
        finished_at=finished_at,
        duration_ms=calculate_duration_ms(started_at, finished_at),
        error_message=error_message,
        records_processed=records_inserted,
    )


def _normalize_status(status: JobStatus | str) -> JobStatus:
    if isinstance(status, JobStatus):
        return status
    normalized = status.strip().upper()
    if normalized == "PARTIAL_SUCCESS":
        return JobStatus.PARTIAL_SUCCESS
    return JobStatus(normalized)


def _optional_str(value: Any) -> str | None:
    return str(value) if value is not None else None
