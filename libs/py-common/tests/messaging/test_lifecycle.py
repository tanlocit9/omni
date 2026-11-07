import json
from uuid import uuid4

import pytest

from py_common.messaging import JobStatus, JobStatusMessage
from py_common.messaging.lifecycle import running_status


def command():
    return {
        "jobDefinitionId": str(uuid4()),
        "executionId": str(uuid4()),
        "parentExecutionId": str(uuid4()),
        "workType": "SYMBOL",
        "workKey": "HOSE-HPG",
        "atomicWorkId": "work:" + "a" * 64,
        "businessDate": "2026-11-02",
        "jobType": "SYNC_INDICATORS",
        "parameters": {"timeframe": "1d"},
    }


@pytest.mark.parametrize("encoding", ["dict", "text", "bytes"])
def test_running_receipt_has_no_completion_and_preserves_work_identity(encoding):
    raw = command()
    payload = raw if encoding == "dict" else json.dumps(raw)
    if encoding == "bytes":
        payload = payload.encode()
    status = running_status(payload)
    assert status.status == JobStatus.RUNNING
    assert status.finished_at is None
    assert status.execution_id == raw["executionId"]
    assert status.parent_execution_id == raw["parentExecutionId"]
    assert status.meta_json["atomicWorkId"] == raw["atomicWorkId"]
    assert status.meta_json["workerStarted"] is True
    assert status.meta_json["workBusinessDate"] == raw["businessDate"]
    assert status.model_dump(by_alias=True)["finishedAt"] is None


@pytest.mark.parametrize(
    "payload", ["not-json", "[]", {}, {"jobDefinitionId": 123, "executionId": "invalid"}]
)
def test_malformed_commands_do_not_get_fabricated_running_receipts(payload):
    assert running_status(payload) is None


@pytest.mark.parametrize(
    "field", ["jobDefinitionId", "executionId", "parentExecutionId", "workType", "workKey"]
)
def test_invalid_identity_does_not_get_receipt(field):
    raw = command()
    raw[field] = "" if field == "workKey" else "invalid"
    assert running_status(raw) is None


def test_absent_parent_is_supported_but_terminal_completion_still_required():
    raw = command()
    raw.pop("parentExecutionId")
    started = running_status(raw)
    assert started.parent_execution_id is None
    with pytest.raises(ValueError, match="Terminal status requires"):
        JobStatusMessage.model_validate({**started.model_dump(), "status": "SUCCESS"})
    with pytest.raises(ValueError, match="RUNNING status cannot"):
        JobStatusMessage.model_validate(
            {**started.model_dump(), "finished_at": started.started_at}
        )
