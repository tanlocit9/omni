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


@pytest.mark.parametrize("field", ["rowCount", "totalBytes", "schemaVersion"])
@pytest.mark.parametrize("value", [True, 1.0, 1.5, "1", None, 2**64])
def test_numeric_wire_fields_reject_coercion_and_overflow(field, value):
    raw = fixture()
    raw["registrations"][0]["outputs"][0][field] = value
    with pytest.raises(ValueError):
        OutputRegistrationEvent.model_validate(raw)


@pytest.mark.parametrize("value", [True, 1.0, "1", None])
def test_envelope_version_rejects_numeric_coercion(value):
    raw = fixture()
    raw["schemaVersion"] = value
    with pytest.raises(ValueError):
        OutputRegistrationEvent.model_validate(raw)


@pytest.mark.parametrize("value", ["1-1-1-1-1", "00000000000000000000000000000004", 4, None])
def test_noncanonical_uuid_is_rejected(value):
    raw = fixture()
    raw["eventId"] = value
    with pytest.raises(ValueError):
        OutputRegistrationEvent.model_validate(raw)


@pytest.mark.parametrize("value", [0, 2051308800, "2035-01-02T00:00:00Z", "20350102"])
def test_business_date_rejects_epoch_and_timestamp_coercion(value):
    raw = fixture()
    raw["registrations"][0]["businessDate"] = value
    with pytest.raises(ValueError):
        OutputRegistrationEvent.model_validate(raw)


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


def test_shared_work_row_scope_fixture_round_trip():
    path = Path(__file__).resolve().parents[3] / "contracts/fixtures/output-registration-work-rows-v1.json"
    raw = json.loads(path.read_text())
    event = OutputRegistrationEvent.model_validate(raw)
    assert json.loads(event.wire_bytes()) == raw
    for change in ("identity", "duplicate", "count", "coverage", "date", "keys", "empty"):
        invalid = deepcopy(raw)
        output = invalid["registrations"][0]["outputs"][0]
        scope = output["workScope"]
        if change=="identity": scope["atomicWorkId"]="work:"+"0"*64
        if change=="duplicate": scope["rowKeys"]*=2
        if change=="count": output["rowCount"]=2
        if change=="coverage": output["coverageDates"]=["2035-01-03"]
        if change=="date": scope["rowKeys"][0]["date"]="20350102"
        if change=="keys": scope["rowKeys"][0]["symbol_key"]="HOSE-VCI"
        if change=="empty": scope["rowKeys"][0]["date"]=""
        with pytest.raises(ValueError):
            OutputRegistrationEvent.model_validate(invalid)
