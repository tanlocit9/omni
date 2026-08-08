from __future__ import annotations

import json
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from py_common.messaging import (
    JobMessage,
    JobStatus,
    JobStatusMessage,
    build_job_error_status,
    calculate_duration_ms,
    utc_now,
)


def _status_payload(**overrides):
    payload = {
        "jobDefinitionId": "job-definition-id",
        "executionId": "execution-id",
        "parentExecutionId": "parent-execution-id",
        "symbolKey": "HOSE-HPG",
        "status": "SUCCESS",
        "startedAt": "2026-01-01T00:00:00Z",
        "finishedAt": "2026-01-01T00:00:01Z",
        "errorMessage": None,
        "recordsProcessed": 60,
        "durationMs": 1000,
        "metaJson": {"recordsProcessed": 60},
        "newOffset": "123",
    }
    payload.update(overrides)
    return payload


def test_job_status_message_accepts_camel_case_wire_payload():
    message = JobStatusMessage.model_validate(_status_payload())

    assert message.job_definition_id == "job-definition-id"
    assert message.execution_id == "execution-id"
    assert message.parent_execution_id == "parent-execution-id"
    assert message.symbol_key == "HOSE-HPG"
    assert message.status == JobStatus.SUCCESS
    assert message.records_processed == 60
    assert message.duration_ms == 1000
    assert message.meta_json == {"recordsProcessed": 60}
    assert message.new_offset == "123"


def test_job_status_message_accepts_snake_case_python_payload():
    started_at = datetime(2026, 1, 1, tzinfo=UTC)
    finished_at = datetime(2026, 1, 1, 0, 0, 1, tzinfo=UTC)

    message = JobStatusMessage(
        job_definition_id="job-definition-id",
        execution_id="execution-id",
        parent_execution_id=None,
        symbol_key="HOSE-HPG",
        status="ERROR",
        started_at=started_at,
        finished_at=finished_at,
        error_message="boom",
    )

    assert message.job_definition_id == "job-definition-id"
    assert message.status == JobStatus.ERROR
    assert message.parent_execution_id is None
    assert message.records_processed == 0
    assert message.duration_ms == 0
    assert message.meta_json == {}
    assert message.new_offset is None


def test_job_status_message_emits_camel_case_json_by_alias():
    message = JobStatusMessage.model_validate(_status_payload())

    encoded = message.model_dump_json(by_alias=True)
    decoded = json.loads(encoded)

    assert decoded["jobDefinitionId"] == "job-definition-id"
    assert decoded["executionId"] == "execution-id"
    assert decoded["parentExecutionId"] == "parent-execution-id"
    assert decoded["symbolKey"] == "HOSE-HPG"
    assert decoded["startedAt"] == "2026-01-01T00:00:00Z"
    assert decoded["finishedAt"] == "2026-01-01T00:00:01Z"
    assert decoded["recordsProcessed"] == 60
    assert decoded["durationMs"] == 1000
    assert decoded["metaJson"] == {"recordsProcessed": 60}
    assert decoded["status"] == "SUCCESS"
    assert decoded["newOffset"] == "123"


def test_job_status_message_rejects_unknown_status():
    with pytest.raises(ValidationError, match="status"):
        JobStatusMessage.model_validate(_status_payload(status="UNKNOWN"))


def test_job_status_message_meta_json_default_factory_is_isolated():
    timestamp = datetime(2026, 1, 1, tzinfo=UTC)
    first = JobStatusMessage(
        job_definition_id="job-definition-id",
        execution_id="execution-id",
        symbol_key="HOSE-HPG",
        status="SUCCESS",
        started_at=timestamp,
        finished_at=timestamp,
    )
    second = JobStatusMessage(
        job_definition_id="job-definition-id",
        execution_id="execution-id",
        symbol_key="HNX-NTP",
        status="SUCCESS",
        started_at=timestamp,
        finished_at=timestamp,
    )

    first.meta_json["recordsProcessed"] = 1

    assert second.meta_json == {}


def test_calculate_duration_ms_returns_elapsed_milliseconds():
    started_at = datetime(2026, 1, 1, 0, 0, 0, tzinfo=UTC)
    finished_at = datetime(2026, 1, 1, 0, 0, 1, 250000, tzinfo=UTC)

    assert calculate_duration_ms(started_at, finished_at) == 1250


def test_build_job_error_status_maps_wire_fields_and_zero_records():
    started_at = datetime(2026, 1, 1, 0, 0, 0, tzinfo=UTC)
    finished_at = datetime(2026, 1, 1, 0, 0, 1, tzinfo=UTC)

    status = build_job_error_status(
        raw={
            "jobDefinitionId": "job-definition-id",
            "executionId": "execution-id",
            "parentExecutionId": "parent-execution-id",
            "symbolKey": "HOSE-HPG",
        },
        started_at=started_at,
        finished_at=finished_at,
        error_message="boom",
    )

    assert status.job_definition_id == "job-definition-id"
    assert status.execution_id == "execution-id"
    assert status.parent_execution_id == "parent-execution-id"
    assert status.symbol_key == "HOSE-HPG"
    assert status.status == JobStatus.ERROR
    assert status.error_message == "boom"
    assert status.records_processed == 0
    assert status.duration_ms == 1000
    assert status.meta_json == {"recordsProcessed": 0}


def test_utc_now_returns_timezone_aware_utc_datetime():
    now = utc_now()

    assert now.tzinfo == UTC


def test_job_message_accepts_shared_scheduler_fields():
    message = JobMessage.model_validate(
        {
            "jobDefinitionId": "job-definition-id",
            "executionId": "execution-id",
            "parentExecutionId": "parent-execution-id",
            "source": "VNDIRECT",
        }
    )

    assert message.job_definition_id == "job-definition-id"
    assert message.execution_id == "execution-id"
    assert message.parent_execution_id == "parent-execution-id"
    assert message.source == "VNDIRECT"


def test_job_message_rejects_blank_execution_id():
    with pytest.raises(ValidationError, match="executionId is required"):
        JobMessage.model_validate(
            {
                "jobDefinitionId": "job-definition-id",
                "executionId": " ",
                "source": "VNDIRECT",
            }
        )
