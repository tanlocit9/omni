"""Exercise real symbol normalization/publication instead of stubbing safety helpers."""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pandas as pd
import pytest
from py_common.messaging import JobStatus, utc_now
from py_common.messaging.atomic_work import atomic_work_id

from app.handlers import symbols
from app.messaging.status import build_status


def previous_snapshot():
    return pd.DataFrame(
        [
            {
                "code": "HPG",
                "exchange": "HOSE",
                "sectorTaxonomy": "ICB",
                "sectorLevel": 2,
                "sourceSectorCode": "8300",
                "sectorLv2Code": "BANKS",
                "icbLv2Code": "8300",
                "icbLv2NameEn": "Banks",
                "classificationUpdatedAt": "2026-10-30T00:00:00Z",
            }
        ]
    )


def test_stale_classification_is_preserved_by_real_normalizer():
    source = pd.DataFrame([{"code": "HPG", "floor": "HOSE", "status": "listed"}])
    result = symbols.normalize_symbols_dataframe(
        source,
        exchange="HOSE",
        metadata={},
        sectors={},
        previous_df=previous_snapshot(),
        include_sector=True,
    )
    assert result.loc[0, "sourceSectorCode"] == "8300"
    assert result.loc[0, "sectorLv2Code"] == "BANKS"
    assert result.loc[0, "classificationUpdatedAt"] == "2026-10-30T00:00:00Z"


@pytest.mark.anyio
async def test_catalog_history_is_rejected_before_provider_or_storage_access():
    from datetime import date

    client, storage, publisher, producer = (
        AsyncMock(),
        AsyncMock(),
        AsyncMock(),
        AsyncMock(),
    )
    day = date(2026, 10, 30)
    parameters = {"source": "VND"}
    payload = {
        "jobDefinitionId": "definition",
        "executionId": "execution",
        "source": "VND",
        "workType": "EXCHANGE",
        "workKey": "HOSE",
        "exchange": "HOSE",
        "jobType": "SYNC_SYMBOLS",
        "businessDate": day.isoformat(),
        "parameters": parameters,
        "atomicWorkId": atomic_work_id(
            "SYNC_SYMBOLS", "EXCHANGE", "HOSE", day, parameters
        ),
    }
    result = await symbols.process_sync_symbols_message(
        payload, producer, publisher, client, storage
    )
    assert result.status == JobStatus.ERROR
    assert result.meta_json["atomicWorkId"] == payload["atomicWorkId"]
    client.fetch_symbols.assert_not_awaited()
    storage.write_dataframe.assert_not_awaited()
    producer.send_and_wait.assert_not_awaited()
    publisher.publish.assert_awaited_once_with(result, key="HOSE")


@pytest.mark.anyio
@pytest.mark.parametrize(
    "mode",
    [
        "fresh",
        "stale",
        "no-previous",
        "disabled",
        "bad-coverage",
        "write-failure",
        "publish-failure",
    ],
)
async def test_real_symbols_pipeline_preserves_classification_and_reports_failures(
    monkeypatch, mode
):
    client, storage, publisher, producer = (
        AsyncMock(),
        AsyncMock(),
        AsyncMock(),
        AsyncMock(),
    )
    client.fetch_symbols.return_value = [
        {
            "code": "hpg",
            "floor": "HOSE",
            "status": "listed",
            "organCode": "HPG",
            "organName": "Hoa Phat",
            "listedDate": pd.Timestamp("2007-01-01"),
            "delistedDate": None,
            "missingMetric": float("nan"),
        }
    ]
    storage.read_optional_dataframe.return_value = (
        pd.DataFrame() if mode == "no-previous" else previous_snapshot()
    )
    sectors = {
        "HPG": {
            "symbol": "HPG",
            "icb_lv1_code": "8000",
            "icb_lv1_name_en": "Financials",
            "icb_lv2_code": "8300",
            "icb_lv2_name_en": "Banks",
            "icb_lv2_name_vi": "Ngan hang",
        }
    }
    cached = AsyncMock(return_value=sectors)
    if mode in ("stale", "no-previous"):
        cached.side_effect = RuntimeError("classification provider down")
    if mode == "bad-coverage":
        cached.return_value = {"OTHER": {"symbol": "OTHER", "icb_lv2_code": "8300"}}
    monkeypatch.setattr(symbols, "get_or_create_client", lambda source: client)
    monkeypatch.setattr(symbols, "get_cached_sectors", cached)
    producer.send_and_wait.side_effect = lambda topic, **kwargs: SimpleNamespace(
        topic=topic, partition=0, offset=1
    )
    if mode == "write-failure":
        storage.write_dataframe.side_effect = RuntimeError("write failed")
    if mode == "publish-failure":
        producer.send_and_wait.side_effect = RuntimeError("publish failed")
    payload = {
        "jobDefinitionId": "job",
        "executionId": "execution",
        "parentExecutionId": "parent",
        "exchange": "hose",
        "workType": "EXCHANGE",
        "workKey": "HOSE",
        "metadata": {
            "includeSectorClassification": mode != "disabled",
            "sectorLevel": 2,
            "symbolCount": 2,
        },
    }
    status = await symbols.process_sync_symbols_message(
        payload, producer, publisher, client, storage
    )
    failed = mode in ("no-previous", "bad-coverage", "write-failure", "publish-failure")
    assert status.status == (JobStatus.ERROR if failed else JobStatus.SUCCESS)
    assert status.execution_id == "execution" and status.parent_execution_id == "parent"
    publisher.publish.assert_awaited_once_with(status, key="HOSE")
    if mode in ("no-previous", "bad-coverage", "write-failure"):
        producer.send_and_wait.assert_not_awaited()
    if mode in ("no-previous", "bad-coverage"):
        storage.write_dataframe.assert_not_awaited()
    if not failed:
        frame = storage.write_dataframe.await_args.args[1]
        assert frame["code"].tolist() == ["HPG"]
        events = [
            json.loads(call.kwargs["value"])
            for call in producer.send_and_wait.await_args_list
        ]
        assert all(
            event["executionId"] == "execution"
            and event["parentExecutionId"] == "parent"
            for event in events
        )
        event = next(event for event in events if "symbols" in event)
        assert event["symbols"][0]["listedDate"] == "2007-01-01"
        assert "missingMetric" not in event["symbols"][0]["meta"]
        if mode == "stale":
            assert status.meta_json["classificationSource"] == "STALE"
            assert status.meta_json["result"] == "PARTIAL_SUCCESS"
            assert frame.loc[0, "sectorLv2Code"] == "BANKS"
            assert event["symbols"][0]["sourceSectorCode"] == "8300"
        elif mode == "fresh":
            assert any("sectors" in event for event in events)
        else:
            assert len(events) == 1


@pytest.mark.parametrize("state", ["success", "PARTIAL_SUCCESS", JobStatus.ERROR])
def test_status_builder_keeps_terminal_identity_and_optional_fields(state):
    result = build_status({"workType": "GLOBAL", "workKey": "key"}, utc_now(), state)
    assert result.status == JobStatus(str(state).upper())
    assert result.execution_id is None and result.parent_execution_id is None


def test_invalid_snapshot_is_rejected_and_duplicates_do_not_inflate_membership():
    with pytest.raises(ValueError, match="required symbol fields"):
        symbols.validate_symbols_snapshot(pd.DataFrame({"code": ["HPG"]}), "HOSE")
    frame = pd.DataFrame(
        [
            {"code": "hpg", "floor": "HNX", "status": "listed"},
            {"code": "HPG", "floor": "hnx", "status": "listed"},
        ]
    )
    assert len(symbols.validate_symbols_snapshot(frame, "HOSE")) == 1


@pytest.mark.parametrize(
    "previous",
    [
        pd.DataFrame(),
        pd.DataFrame({"code": ["HPG"]}),
        pd.DataFrame({"code": ["HPG"], "exchange": ["HOSE"]}),
    ],
)
def test_missing_previous_classification_cannot_invent_sector_data(previous):
    frame = pd.DataFrame({"code": ["HPG"], "exchange": ["HOSE"]})
    original = frame.copy(deep=True)
    symbols.preserve_previous_classification(frame, previous)
    pd.testing.assert_frame_equal(frame, original)


def test_empty_provider_mapping_retains_legacy_floor_classification():
    previous = previous_snapshot().rename(columns={"exchange": "floor"})
    previous["icbLv1NameEn"] = None
    result = symbols.normalize_symbols_dataframe(
        pd.DataFrame({"code": ["HPG"], "floor": ["HOSE"], "status": ["listed"]}),
        exchange="HOSE",
        metadata={},
        sectors={"empty": {}},
        previous_df=previous,
        include_sector=True,
    )
    assert result.loc[0, "sectorLv2Code"] == "BANKS"
    assert result.loc[0, "sectorLv1Code"] is None


def test_partial_coverage_is_visible_without_inventing_full_classification():
    metrics = symbols.calculate_classification_metrics(
        pd.DataFrame({"sourceSectorCode": ["8300"] * 19 + [None]}), {"one": {}}
    )
    assert metrics["matchedCount"] == 19 and metrics["unmatchedCount"] == 1
    assert "95.00%" in symbols.validate_classification_coverage(metrics)
    assert (
        symbols.calculate_classification_metrics(pd.DataFrame(), {})["matchPercentage"]
        == 100
    )


@pytest.mark.anyio
async def test_upserts_merge_shared_sectors_and_skip_missing_symbol_identity():
    first = previous_snapshot().iloc[0].to_dict()
    first.update(icbLv2NameVi=None, listedDate=pd.NaT)
    second = {
        **first,
        "code": "MBB",
        "icbLv2NameVi": "Ngan hang",
        "extra": float("nan"),
    }
    producer = AsyncMock()
    producer.send_and_wait.side_effect = lambda topic, **kwargs: SimpleNamespace(
        topic=topic, partition=0, offset=0
    )
    frame = pd.DataFrame([first, second, {"code": "", "exchange": "HOSE"}])
    kwargs = dict(
        job_definition_id="job",
        execution_id="execution",
        parent_execution_id=None,
        exchange="HOSE",
        merged_df=frame,
        expected_count=3,
    )
    await symbols.publish_symbol_upsert_batch(producer, **kwargs)
    await symbols.publish_sector_upsert_batch(producer, **kwargs)
    events = [
        json.loads(call.kwargs["value"])
        for call in producer.send_and_wait.await_args_list
    ]
    assert events[0]["actualCount"] == 2 and events[0]["expectedCount"] == 3
    assert events[0]["parentExecutionId"] == "execution"
    assert events[1]["actualCount"] == 1
    assert events[1]["sectors"][0]["sourceSectorNameVi"] == "Ngan hang"
    empty = AsyncMock()
    await symbols.publish_sector_upsert_batch(
        empty, **{**kwargs, "merged_df": pd.DataFrame({"code": ["HPG"]})}
    )
    empty.send_and_wait.assert_not_awaited()
