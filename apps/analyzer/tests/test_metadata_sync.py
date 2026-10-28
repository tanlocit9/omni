import json
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import AsyncMock

import pytest
from py_common.messaging import JobStatus
from py_common.storage.metadata_sync import (
    MetadataSyncEmptyError,
    MetadataSyncResult,
    MetadataSyncTarget,
)

from app.metadata.handler import MetadataSyncJobHandler
from app.metadata.kafka import MetadataSyncKafkaService
from app.metadata.messages import SyncMetadataJobMessage
from app.settings import AppSettings


def _payload(**overrides):
    payload = {
        "jobDefinitionId": "job-1",
        "executionId": "execution-1",
        "parentExecutionId": None,
        "source": "ANALYZER",
        "workType": "GLOBAL",
        "workKey": "SYNC_METADATA",
        "metadata": {},
    }
    payload.update(overrides)
    return payload


def test_metadata_message_accepts_logical_exact_target() -> None:
    message = SyncMetadataJobMessage.model_validate(
        _payload(
            target={"dataset": "eod", "partition": {"exchange": "hose", "code": "hpg"}}
        )
    )

    assert message.target is not None
    assert message.target.dataset == "eod"
    assert message.target.partition == {"exchange": "hose", "code": "hpg"}


@pytest.mark.asyncio
@pytest.mark.parametrize("skipped,failed", [(1, 0), (0, 1), (1, 1), (0, 0)])
async def test_metadata_worker_warns_on_partial_preserving_counts(
    skipped, failed, caplog
) -> None:
    synchronizer = AsyncMock()
    synchronizer.sync.return_value = MetadataSyncResult(
        mode="FULL",
        objects_seen=3,
        partitions_added=1,
        partitions_replaced=0,
        partitions_removed=0,
        partitions_unchanged=0,
        objects_skipped=skipped,
        objects_failed=failed,
    )
    service = MetadataSyncKafkaService(
        AppSettings(metadata_kafka_enabled=False),
        MetadataSyncJobHandler(synchronizer),
    )
    service.publish_status = AsyncMock()

    status = await service.process_payload(_payload())

    assert status.status == JobStatus.SUCCESS
    assert status.error_message is None
    warning = (
        f"Metadata synchronization incomplete: {skipped} skipped object(s), "
        f"{failed} failed object(s)"
        if skipped or failed
        else None
    )
    assert status.records_processed == 1
    assert status.meta_json == {
        "mode": "FULL",
        "objectsSeen": 3,
        "partitionsAdded": 1,
        "partitionsReplaced": 0,
        "partitionsRemoved": 0,
        "partitionsUnchanged": 0,
        "objectsSkipped": skipped,
        "objectsFailed": failed,
        **({"result": "PARTIAL_SUCCESS", "warning": warning} if warning else {}),
    }
    if warning:
        assert warning in caplog.text
    else:
        assert not caplog.records
    synchronizer.sync.assert_awaited_once_with(
        target=None,
        execution_id="execution-1",
    )
    service.publish_status.assert_awaited_once_with(status)


@pytest.mark.asyncio
async def test_metadata_handler_forwards_dataset_target() -> None:
    synchronizer = AsyncMock()
    handler = MetadataSyncJobHandler(synchronizer)
    message = SyncMetadataJobMessage.model_validate(_payload(target={"dataset": "eod"}))

    await handler.handle(message)

    synchronizer.sync.assert_awaited_once_with(
        target=MetadataSyncTarget(dataset="eod", partition=None),
        execution_id="execution-1",
    )


@pytest.mark.asyncio
async def test_metadata_worker_publishes_error_status() -> None:
    synchronizer = AsyncMock()
    synchronizer.sync.side_effect = RuntimeError("synchronization failed")
    service = MetadataSyncKafkaService(
        AppSettings(metadata_kafka_enabled=False),
        MetadataSyncJobHandler(synchronizer),
    )
    service.publish_status = AsyncMock()

    status = await service.process_payload(_payload())

    assert status.status == JobStatus.ERROR
    assert status.error_message == "Metadata synchronization failed"


@pytest.mark.asyncio
async def test_metadata_partial_wire_payload_matches_platform_fixture(monkeypatch):
    frozen = datetime(2026, 10, 26, tzinfo=UTC)
    monkeypatch.setattr("app.metadata.kafka.utc_now", lambda: frozen)
    fixture = json.loads(
        (
            Path(__file__).resolve().parents[2]
            / "core/src/test/resources/contracts/metadata-sync-partial-status.json"
        ).read_text()
    )
    handler = AsyncMock()
    handler.handle.return_value = MetadataSyncResult("FULL", 2, 1, 0, 0, 0, 1, 0)
    service = MetadataSyncKafkaService(
        AppSettings(metadata_kafka_enabled=False), handler
    )
    service.publish_status = AsyncMock()
    status = await service.process_payload(
        _payload(
            jobDefinitionId=fixture["jobDefinitionId"],
            executionId=fixture["executionId"],
        )
    )
    assert json.loads(status.model_dump_json(by_alias=True)) == fixture
    service.publish_status.assert_awaited_once_with(status)


@pytest.mark.asyncio
async def test_empty_sync_keeps_specific_error_and_status_publish_failure_propagates():
    handler = AsyncMock()
    handler.handle.side_effect = MetadataSyncEmptyError("No valid partitions")
    service = MetadataSyncKafkaService(
        AppSettings(metadata_kafka_enabled=False), handler
    )
    service.publish_status = AsyncMock()
    status = await service.process_payload(_payload())
    assert status.status == JobStatus.ERROR
    assert status.error_message == "No valid partitions"
    service.publish_status.side_effect = RuntimeError("status unavailable")
    with pytest.raises(RuntimeError, match="status unavailable"):
        await service.process_payload(_payload())
