from __future__ import annotations

import logging
from typing import Any

from py_common.config import ConsumerGroup
from py_common.kafka import JobStatusKafkaService, decode_json_object_payload
from py_common.messaging import (
    JobStatus,
    JobStatusMessage,
    build_job_error_status,
    calculate_duration_ms,
    utc_now,
)
from py_common.storage.metadata_sync import MetadataSyncEmptyError

from app.metadata.handler import MetadataSyncJobHandler
from app.metadata.messages import SyncMetadataJobMessage
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


class MetadataSyncKafkaService(JobStatusKafkaService):
    def __init__(self, settings: AppSettings, handler: MetadataSyncJobHandler) -> None:
        self._handler = handler
        topic = settings.topic_sync_metadata
        super().__init__(
            kafka_settings=settings.kafka,
            input_topic=topic,
            status_topic=settings.sync_job_status_topic,
            group_id=ConsumerGroup.ANALYZER.for_topic(topic),
            service_name="metadata-sync",
            task_name="metadata-sync-kafka-consumer",
        )

    async def process_payload(
        self, payload: str | bytes | dict[str, Any]
    ) -> JobStatusMessage:
        started_at = utc_now()
        raw: dict[str, Any] = {}
        try:
            raw = decode_json_object_payload(payload, "Metadata sync job")
            message = SyncMetadataJobMessage.model_validate(raw)
            result = await self._handler.handle(message)
            finished_at = utc_now()
            status = JobStatusMessage(
                job_definition_id=message.job_definition_id,
                execution_id=message.execution_id,
                parent_execution_id=message.parent_execution_id,
                work_type=message.work_type,
                work_key=message.work_key,
                status=(
                    JobStatus.PARTIAL_SUCCESS
                    if result.is_partial
                    else JobStatus.SUCCESS
                ),
                started_at=started_at,
                finished_at=finished_at,
                records_processed=result.partitions_published,
                duration_ms=calculate_duration_ms(started_at, finished_at),
                meta_json={
                    "mode": result.mode,
                    "objectsSeen": result.objects_seen,
                    "partitionsAdded": result.partitions_added,
                    "partitionsReplaced": result.partitions_replaced,
                    "partitionsRemoved": result.partitions_removed,
                    "partitionsUnchanged": result.partitions_unchanged,
                    "objectsSkipped": result.objects_skipped,
                    "objectsFailed": result.objects_failed,
                },
            )
        except Exception as exc:
            _logger.exception("Metadata sync job failed")
            error_message = (
                str(exc)
                if isinstance(exc, MetadataSyncEmptyError)
                else "Metadata synchronization failed"
            )
            status = build_job_error_status(
                raw=raw,
                started_at=started_at,
                finished_at=utc_now(),
                error_message=error_message,
            )

        await self.publish_status(status)
        return status
