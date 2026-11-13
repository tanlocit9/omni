import json
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from py_common.messaging.output_publication import publish_terminal_status
from py_common.messaging.worker_completion import (
    WorkerCompletionEvent,
    publish_completion,
)


def fixture():
    return json.loads(
        (
            Path(__file__).resolve().parents[4]
            / "libs/contracts/fixtures/worker-completion-v1.json"
        ).read_text()
    )


def test_shared_completion_fixture_round_trips_with_exact_output_identity():
    raw = fixture()
    assert json.loads(WorkerCompletionEvent.model_validate(raw).wire_bytes()) == raw


@pytest.mark.parametrize("value", [True, [], "invalid", "2147483648", " 1"])
def test_invalid_metadata_counter_cannot_make_platform_ignore_completion(value):
    raw = fixture()
    raw["status"]["metaJson"]["recordsInserted"] = value
    with pytest.raises(ValueError):
        WorkerCompletionEvent.model_validate(raw).wire_bytes()


@pytest.mark.parametrize("value", [None, 1, "1", 1.0])
def test_supported_legacy_metadata_counters_do_not_change_completion_identity(value):
    raw = fixture()
    raw["status"]["metaJson"]["recordsProcessed"] = value
    assert (
        WorkerCompletionEvent.model_validate(raw).status.execution_id
        == raw["status"]["executionId"]
    )


@pytest.mark.parametrize(
    "failure",
    [
        "version",
        "kind",
        "running",
        "missing",
        "failed-output",
        "event",
        "attempt",
        "execution",
        "work",
        "clock",
        "counter",
        "uuid",
        "oversize",
    ],
)
def test_invalid_completion_never_crosses_the_wire(failure):
    raw = fixture()
    if failure == "version":
        raw["schemaVersion"] = True
    if failure == "kind":
        raw["kind"] = "UNKNOWN"
    if failure == "running":
        raw["status"].update(status="RUNNING", finishedAt=None)
    if failure == "missing":
        raw["registration"] = None
    if failure == "failed-output":
        raw["status"]["status"] = "ERROR"
    if failure == "event":
        raw["eventId"] = "00000000-0000-0000-0000-000000000099"
    if failure == "attempt":
        raw["atomicAttemptId"] = "00000000-0000-0000-0000-000000000099"
    if failure == "execution":
        raw["status"]["executionId"] = "00000000-0000-0000-0000-000000000099"
    if failure == "work":
        raw["status"]["workKey"] = "OTHER"
    if failure == "clock":
        raw["status"]["finishedAt"] = "2035-01-01T00:00:00Z"
    if failure == "counter":
        raw["status"]["recordsProcessed"] = -1
    if failure == "uuid":
        raw["status"]["jobDefinitionId"] = "invalid"
    if failure == "oversize":
        raw["status"]["metaJson"]["padding"] = "x" * 262144
    with pytest.raises(ValueError):
        WorkerCompletionEvent.model_validate(raw).wire_bytes()


@pytest.mark.asyncio
@pytest.mark.parametrize("success", [True, False])
async def test_one_broker_ack_for_terminal_result_and_evidence(success):
    raw = fixture()
    if not success:
        raw["status"]["status"] = "ERROR"
        raw["registration"] = None
    event = WorkerCompletionEvent.model_validate(raw)
    producer, legacy = AsyncMock(), AsyncMock()
    command = {
        "metadata": {
            "outputCompletion": {
                "protocol": "WORKER_COMPLETION_V1",
                "eventId": raw["eventId"],
                "atomicAttemptId": raw["atomicAttemptId"],
            }
        }
    }
    from types import SimpleNamespace

    publication = SimpleNamespace(completion_registration=event.registration)
    await publish_terminal_status(producer, legacy, event.status, command, publication)
    producer.send_and_wait.assert_awaited_once()
    legacy.assert_not_awaited()
    args = producer.send_and_wait.await_args
    assert args.args[0] == "topic-register-output-metadata-v1"
    assert json.loads(args.args[1]) == raw
    assert args.kwargs["key"] == raw["atomicAttemptId"].encode()


@pytest.mark.asyncio
async def test_failed_broker_ack_cannot_fall_back_to_independent_success_status():
    event = WorkerCompletionEvent.model_validate(fixture())
    producer = AsyncMock()
    producer.send_and_wait.side_effect = RuntimeError("broker unavailable")
    with pytest.raises(RuntimeError, match="broker unavailable"):
        await publish_completion(producer, event)
    with pytest.raises(RuntimeError, match="unavailable"):
        await publish_completion(None, event)


@pytest.mark.asyncio
async def test_legacy_status_keeps_transport_and_unknown_protocol_fails():
    event = WorkerCompletionEvent.model_validate(fixture())
    legacy = AsyncMock()
    await publish_terminal_status(None, legacy, event.status, {})
    legacy.assert_awaited_once_with(event.status)
    with pytest.raises(ValueError, match="Unsupported"):
        await publish_terminal_status(
            None,
            legacy,
            event.status,
            {"metadata": {"outputCompletion": {"protocol": "UNKNOWN"}}},
        )
