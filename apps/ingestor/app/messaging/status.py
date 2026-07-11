from datetime import UTC, datetime
from typing import Any


def build_status(
    key_field: str,
    key_value: str | None,
    payload: dict[str, Any],
    started_at: datetime,
    status: str,
    records_inserted: int = 0,
    total_records: int = 0,
    new_offset: str | None = None,
    error_message: str | None = None,
) -> dict[str, Any]:
    finished_at = datetime.now(UTC)
    job_definition_id = payload.get("jobDefinitionId") or payload.get("jobId")
    execution_id = payload.get("executionId") or payload.get("logId")
    parent_execution_id = payload.get("parentExecutionId")

    return {
        key_field: key_value or payload.get(key_field, "unknown"),
        "jobDefinitionId": job_definition_id,
        "executionId": execution_id,
        "parentExecutionId": parent_execution_id,
        "status": status,
        "metaJson": {
            "recordsInserted": records_inserted,
            "totalRecords": total_records,
        },
        "newOffset": new_offset,
        "startedAt": started_at.isoformat(),
        "finishedAt": finished_at.isoformat(),
        "durationMs": int((finished_at - started_at).total_seconds() * 1000),
        "errorMessage": error_message,
    }