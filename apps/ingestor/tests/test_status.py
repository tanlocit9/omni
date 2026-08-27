from datetime import UTC, datetime

from py_common.messaging import JobStatus, JobStatusMessage

from app.messaging.status import build_status


def test_build_status_returns_shared_job_status_message_with_work_identity():
    started_at = datetime(2026, 1, 1, 0, 0, 0, tzinfo=UTC)

    status = build_status(
        {
            "jobDefinitionId": "job-1",
            "executionId": "exec-1",
            "parentExecutionId": "parent-1",
            "workType": "SYMBOL",
            "workKey": "HOSE-FPT",
        },
        started_at,
        JobStatus.SUCCESS,
        records_inserted=5,
        total_records=10,
        new_offset="2026-01-01",
    )

    assert isinstance(status, JobStatusMessage)
    assert status.job_definition_id == "job-1"
    assert status.execution_id == "exec-1"
    assert status.parent_execution_id == "parent-1"
    assert status.work_type == "SYMBOL"
    assert status.work_key == "HOSE-FPT"
    assert status.status == JobStatus.SUCCESS
    assert status.records_processed == 5
    assert status.meta_json == {"recordsInserted": 5, "totalRecords": 10}
    assert status.new_offset == "2026-01-01"
    assert status.model_extra == {}


def test_build_status_uses_work_identity_for_symbol_snapshot_jobs():
    started_at = datetime(2026, 1, 1, 0, 0, 0, tzinfo=UTC)

    status = build_status(
        {
            "jobDefinitionId": "job-1",
            "executionId": "exec-1",
            "workType": "EXCHANGE",
            "workKey": "HOSE",
        },
        started_at,
        "partial_success",
        records_inserted=100,
        total_records=100,
    )

    dumped = status.model_dump(by_alias=True)
    assert status.work_type == "EXCHANGE"
    assert status.work_key == "HOSE"
    assert status.status == JobStatus.PARTIAL_SUCCESS
    assert "exchange" not in dumped
    assert status.model_extra == {}
