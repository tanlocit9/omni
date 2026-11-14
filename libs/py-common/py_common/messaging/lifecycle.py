"""Worker receipt signals; terminal acknowledgement still controls command commits."""

from __future__ import annotations

import json
from uuid import UUID

from py_common.messaging.atomic_work import carry_work_status
from py_common.messaging.messages import JobStatus, JobStatusMessage, utc_now


def running_status(payload: str | bytes | dict) -> JobStatusMessage | None:
    """Build a receipt for identifiable commands, leaving malformed ones to handlers."""
    try:
        raw = payload if isinstance(payload, dict) else json.loads(payload)
        if not isinstance(raw, dict):
            return None
        for field in ("jobDefinitionId", "executionId"):
            UUID(str(raw[field]))
        if raw.get("parentExecutionId"):
            UUID(str(raw["parentExecutionId"]))
        status = JobStatusMessage(
            job_definition_id=raw["jobDefinitionId"],
            execution_id=raw["executionId"],
            parent_execution_id=raw.get("parentExecutionId"),
            work_type=raw["workType"],
            work_key=raw["workKey"],
            status=JobStatus.RUNNING,
            started_at=utc_now(),
            finished_at=None,
            meta_json={"workerStarted": True},
        )
        return carry_work_status(status, raw)
    except (ValueError, TypeError, KeyError):
        return None
