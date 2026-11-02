from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pandas as pd
import pytest

from py_common.messaging.atomic_work import atomic_work_id
from py_common.messaging.output_publication import capture_output_publication, record_output
from py_common.storage.manifest import calculate_schema_hash, extract_schema_from_dataframe
from py_common.storage.output_snapshots import OutputSnapshotIdentity


@pytest.fixture
def anyio_backend():
    return "asyncio"


def work():
    day = date(2026, 11, 2)
    return {
        "executionId": str(uuid4()), "jobType": "SYNC_STOCK_PRICE", "workType": "SYMBOL",
        "workKey": "UPCOM-HD6", "businessDate": day.isoformat(), "parameters": {},
        "atomicWorkId": atomic_work_id("SYNC_STOCK_PRICE", "SYMBOL", "UPCOM-HD6", day, {}),
        "metadata": {"outputCompletion": {
            "atomicAttemptId": str(uuid4()), "eventId": str(uuid4()), "registrationId": str(uuid4()),
            "requiredOutputs": [{"dataset": "eod", "partition": {"exchange": "upcom", "code": "hd6"}}],
        }},
    }


def evidence(frame):
    return SimpleNamespace(data_version="sha256:" + "a" * 64, total_bytes=128,
                           schema_hash=calculate_schema_hash(extract_schema_from_dataframe(frame)), inputs=())


@pytest.mark.anyio
async def test_missing_day_never_publishes_registration_or_invents_valid_empty():
    producer = AsyncMock()
    frame = pd.DataFrame({"date": [date(2026, 10, 30)], "close": [10]})
    with capture_output_publication(work(), True) as publication:
        with pytest.raises(ValueError, match="does not cover work/date"):
            record_output(OutputSnapshotIdentity("eod", {"exchange": "upcom", "code": "hd6"}), evidence(frame), frame)
        with pytest.raises(ValueError, match="every required output"):
            await publication.publish(producer)
    producer.send_and_wait.assert_not_awaited()


@pytest.mark.anyio
async def test_registration_uses_server_ids_and_only_actual_coverage():
    raw = work()
    frame = pd.DataFrame({"date": [date(2026, 10, 30), date(2026, 11, 2)], "close": [10, 11]})
    producer = AsyncMock()
    with capture_output_publication(raw, True) as publication:
        record_output(OutputSnapshotIdentity("eod", {"exchange": "upcom", "code": "hd6"}), evidence(frame), frame)
        await publication.publish(producer)
    import json
    event = json.loads(producer.send_and_wait.await_args.args[1])
    assert event["eventId"] == raw["metadata"]["outputCompletion"]["eventId"]
    assert event["registrations"][0]["outputs"][0]["coverageDates"] == ["2026-10-30", "2026-11-02"]
    assert "2026-10-31" not in str(event)


def test_enrollment_rejects_disabled_snapshots_before_writes():
    with pytest.raises(ValueError, match="requires immutable snapshots"):
        with capture_output_publication(work(), False):
            pytest.fail("Handler must not start")


@pytest.mark.anyio
async def test_registration_ack_failure_propagates_and_context_does_not_leak():
    producer = AsyncMock()
    producer.send_and_wait.side_effect = RuntimeError("ACK lost")
    frame = pd.DataFrame({"date": [date(2026, 11, 2)], "close": [10]})
    with capture_output_publication(work(), True) as publication:
        record_output(OutputSnapshotIdentity("eod", {"exchange": "upcom", "code": "hd6"}), evidence(frame), frame)
        with pytest.raises(RuntimeError, match="ACK lost"):
            await publication.publish(producer)
    with capture_output_publication({}, False) as legacy:
        await legacy.publish(None)


@pytest.mark.parametrize("dates", [None, [None], [date(2026, 11, 2), None]])
def test_incomplete_calendar_evidence_cannot_register(dates):
    frame = pd.DataFrame({"close": [10]}) if dates is None else pd.DataFrame({"date": dates})
    with capture_output_publication(work(), True):
        with pytest.raises(ValueError, match="complete calendar-date coverage"):
            record_output(OutputSnapshotIdentity("eod", {"exchange": "upcom", "code": "hd6"}), evidence(frame), frame)


def test_undeclared_output_cannot_replace_required_partition():
    frame = pd.DataFrame({"date": [date(2026, 11, 2)]})
    with capture_output_publication(work(), True):
        with pytest.raises(ValueError, match="undeclared"):
            record_output(OutputSnapshotIdentity("eod", {"exchange": "upcom", "code": "hd7"}), evidence(frame), frame)


@pytest.mark.anyio
async def test_missing_publisher_cannot_report_success_after_snapshot():
    frame = pd.DataFrame({"date": [date(2026, 11, 2)]})
    with capture_output_publication(work(), True) as publication:
        record_output(OutputSnapshotIdentity("eod", {"exchange": "upcom", "code": "hd6"}), evidence(frame), frame)
        with pytest.raises(RuntimeError, match="publisher unavailable"):
            await publication.publish(None)


def test_nested_context_restores_outer_publication_after_failure():
    frame = pd.DataFrame({"date": [date(2026, 11, 2)]})
    identity = OutputSnapshotIdentity("eod", {"exchange": "upcom", "code": "hd6"})
    with capture_output_publication(work(), True) as outer:
        with pytest.raises(RuntimeError):
            with capture_output_publication({}, False) as inner:
                record_output(identity, evidence(frame), frame)
                assert not inner.outputs
                raise RuntimeError("handler failed")
        record_output(identity, evidence(frame), frame)
        assert len(outer.outputs) == 1
    record_output(identity, evidence(frame), frame)
    assert len(outer.outputs) == 1


@pytest.mark.anyio
async def test_outcome_registration_retains_origin_dates_without_inventing_evaluation_day():
    import json

    raw = work()
    raw.update(jobType="EVALUATE_SIGNALS", workType="EXCHANGE", workKey="UPCOM")
    raw["atomicWorkId"] = atomic_work_id(raw["jobType"], raw["workType"], raw["workKey"],
                                        date(2026, 11, 2), {})
    partition = {"strategy": "trend", "timeframe": "1d", "exchange": "upcom"}
    raw["metadata"]["outputCompletion"]["requiredOutputs"] = [{"dataset": "signals", "partition": partition}]
    frame = pd.DataFrame({"signal_date": [date(2026, 10, 30)], "symbol_key": ["UPCOM-HD6"]})
    producer = AsyncMock()
    with capture_output_publication(raw, True) as publication:
        record_output(OutputSnapshotIdentity("signals", partition), evidence(frame), frame)
        await publication.publish(producer)
    output = json.loads(producer.send_and_wait.await_args.args[1])["registrations"][0]["outputs"][0]
    assert output["coverageDates"] == ["2026-10-30"]


def test_signal_day_for_another_symbol_cannot_cover_current_work():
    raw = work()
    partition = {"strategy": "trend", "timeframe": "1d", "exchange": "upcom"}
    raw["metadata"]["outputCompletion"]["requiredOutputs"] = [{"dataset": "signals", "partition": partition}]
    frame = pd.DataFrame({"signal_date": [date(2026, 11, 2)], "symbol_key": ["UPCOM-HD7"]})
    with capture_output_publication(raw, True):
        with pytest.raises(ValueError, match="does not cover work/date"):
            record_output(OutputSnapshotIdentity("signals", partition), evidence(frame), frame)
