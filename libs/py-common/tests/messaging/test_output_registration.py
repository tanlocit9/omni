from __future__ import annotations

import json
from copy import deepcopy
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from py_common.messaging.atomic_work import atomic_work_id
from py_common.messaging.output_registration import (
    MAX_EVENT_BYTES,
    REGISTRATION_TOPIC,
    OutputRegistrationEvent,
    OutputRegistrationPublisher,
)


def fixture():
    path = (
        Path(__file__).resolve().parents[3]
        / "contracts/fixtures/output-registration-v1.json"
    )
    return json.loads(path.read_text(encoding="utf-8"))


def test_shared_fixture_round_trip_retains_identity_and_dates():
    event = OutputRegistrationEvent.model_validate(fixture())
    assert json.loads(event.wire_bytes()) == fixture()
    assert event.registrations[0].outputs[0].coverage_dates == [date(2035, 1, 2)]


@pytest.mark.parametrize(
    "mutation",
    ["partial", "duplicate", "wrong_work", "path", "date_gap", "empty_with_output"],
)
def test_invalid_record_is_rejected_as_a_whole(mutation):
    raw = fixture()
    item = raw["registrations"][0]
    if mutation == "partial":
        item["outputs"] = []
    elif mutation == "duplicate":
        raw["registrations"].append(deepcopy(item))
    elif mutation == "wrong_work":
        item["atomicWorkId"] = "work:" + "0" * 64
    elif mutation == "path":
        item["outputs"][0]["path"] = "not-a-business-field"
    elif mutation == "date_gap":
        item["outputs"][0]["coverageDates"] *= 2
    else:
        item["validEmptyReason"] = "NO_OUTPUT"
    with pytest.raises(ValueError):
        OutputRegistrationEvent.model_validate(raw)


def test_valid_empty_never_claims_coverage():
    raw = fixture()
    raw["registrations"][0].update(
        requiredOutputs=[], outputs=[], validEmptyReason="NO_MATURED_PREDICTIONS"
    )
    event = OutputRegistrationEvent.model_validate(raw)
    assert not event.registrations[0].outputs


@pytest.mark.asyncio
async def test_registration_ack_precedes_status_and_replay_keeps_same_bytes():
    producer = SimpleNamespace(send_and_wait=AsyncMock())
    statuses = SimpleNamespace(publish=AsyncMock())
    event = OutputRegistrationEvent.model_validate(fixture())
    status = SimpleNamespace(
        status="SUCCESS",
        execution_id=str(event.registrations[0].execution_id),
        meta_json={},
    )

    async def publish_status(_):
        assert producer.send_and_wait.await_count > 0

    statuses.publish.side_effect = publish_status
    publisher = OutputRegistrationPublisher(producer)
    await publisher.publish_before_success(event, status, statuses)
    first = producer.send_and_wait.call_args
    await publisher.publish_before_success(event, status, statuses)
    assert producer.send_and_wait.call_args == first
    assert first.args[0] == REGISTRATION_TOPIC


@pytest.mark.asyncio
async def test_registration_failure_prevents_terminal_status():
    producer = SimpleNamespace(
        send_and_wait=AsyncMock(side_effect=RuntimeError("ACK unavailable"))
    )
    statuses = SimpleNamespace(publish=AsyncMock())
    event = OutputRegistrationEvent.model_validate(fixture())
    status = SimpleNamespace(
        status="SUCCESS",
        execution_id=str(event.registrations[0].execution_id),
        meta_json={},
    )
    with pytest.raises(RuntimeError, match="ACK unavailable"):
        await OutputRegistrationPublisher(producer).publish_before_success(
            event, status, statuses
        )
    statuses.publish.assert_not_awaited()


def test_byte_limit_applies_to_utf8_before_send():
    raw = fixture()
    raw["registrations"][0]["parameters"] = {"oversized": "x" * MAX_EVENT_BYTES}
    item = raw["registrations"][0]
    item["atomicWorkId"] = atomic_work_id(
        item["jobType"], item["workType"], item["workKey"],
        date(2035, 1, 2), item["parameters"],
    )
    with pytest.raises(ValueError, match="256 KiB"):
        OutputRegistrationEvent.model_validate(raw).wire_bytes()
