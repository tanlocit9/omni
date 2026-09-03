from unittest.mock import AsyncMock

import pytest
from py_common.messaging import JobStatus
from py_common.storage.metadata_sync import MetadataSyncResult, MetadataSyncTarget

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
async def test_metadata_worker_publishes_partial_status_counts() -> None:
    synchronizer = AsyncMock()
    synchronizer.sync.return_value = MetadataSyncResult(
        mode="FULL",
        objects_seen=3,
        partitions_added=1,
        partitions_replaced=0,
        partitions_removed=0,
        partitions_unchanged=0,
        objects_skipped=1,
        objects_failed=1,
    )
    service = MetadataSyncKafkaService(
        AppSettings(metadata_kafka_enabled=False),
        MetadataSyncJobHandler(synchronizer),
    )
    service.publish_status = AsyncMock()

    status = await service.process_payload(_payload())

    assert status.status == JobStatus.PARTIAL_SUCCESS
    assert status.records_processed == 1
    assert status.meta_json == {
        "mode": "FULL",
        "objectsSeen": 3,
        "partitionsAdded": 1,
        "partitionsReplaced": 0,
        "partitionsRemoved": 0,
        "partitionsUnchanged": 0,
        "objectsSkipped": 1,
        "objectsFailed": 1,
    }
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
