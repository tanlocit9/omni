from unittest.mock import AsyncMock

import pytest
from py_common.messaging import JobStatus
from py_common.storage.metadata_sync import MetadataSyncResult

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
        "metadataType": "EOD",
        "metadata": {},
    }
    payload.update(overrides)
    return payload


def test_metadata_message_accepts_legacy_definition_as_eod() -> None:
    message = SyncMetadataJobMessage.model_validate(_payload(metadataType="UNIVERSAL"))
    assert message.metadata_type == "EOD"


@pytest.mark.asyncio
async def test_metadata_worker_publishes_partial_status_counts() -> None:
    synchronizer = AsyncMock()
    synchronizer.sync.return_value = MetadataSyncResult(3, 1, 0, 1, 1)
    service = MetadataSyncKafkaService(
        AppSettings(metadata_kafka_enabled=False),
        MetadataSyncJobHandler(synchronizer),
    )
    service.publish_status = AsyncMock()

    status = await service.process_payload(_payload())

    assert status.status == JobStatus.PARTIAL_SUCCESS
    assert status.records_processed == 1
    assert status.meta_json == {
        "dataset": "eod",
        "objectsSeen": 3,
        "manifestsPublished": 1,
        "manifestsUnchanged": 0,
        "objectsSkipped": 1,
        "objectsFailed": 1,
    }
    synchronizer.sync.assert_awaited_once_with(execution_id="execution-1")
    service.publish_status.assert_awaited_once_with(status)


@pytest.mark.asyncio
async def test_metadata_worker_publishes_error_status() -> None:
    synchronizer = AsyncMock()
    synchronizer.sync.side_effect = RuntimeError("no EOD data")
    service = MetadataSyncKafkaService(
        AppSettings(metadata_kafka_enabled=False),
        MetadataSyncJobHandler(synchronizer),
    )
    service.publish_status = AsyncMock()

    status = await service.process_payload(_payload())

    assert status.status == JobStatus.ERROR
    assert status.error_message == "Metadata synchronization failed"
