from __future__ import annotations

import logging
from datetime import datetime
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

from app.indicators.handler import IndicatorJobHandler
from app.indicators.messages import IndicatorJobMessage
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


class IndicatorKafkaService(JobStatusKafkaService):
    """Kafka lifecycle for Analyzer indicator jobs."""

    def __init__(self, settings: AppSettings, handler: IndicatorJobHandler) -> None:
        self._settings = settings
        self._handler = handler
        topic = settings.topic_sync_indicators
        super().__init__(
            kafka_settings=settings.kafka,
            input_topic=topic,
            status_topic=settings.sync_job_status_topic,
            group_id=ConsumerGroup.ANALYZER.for_topic(topic),
            service_name="indicator",
            task_name="indicator-kafka-consumer",
        )

    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage | None:
        started_at = utc_now()
        try:
            raw = decode_json_object_payload(payload, "Indicator job")
        except Exception:
            _logger.warning(
                "Skipping malformed indicator payload without publishing status"
            )
            return None

        if not all(
            str(raw.get(field, "")).strip()
            for field in ("executionId", "workType", "workKey")
        ):
            _logger.warning(
                "Skipping indicator payload without canonical execution identity"
            )
            return None

        try:
            message = IndicatorJobMessage.model_validate(raw)
            records_processed = await self._handler.handle(raw)
            status = self._build_status(
                message=message,
                started_at=started_at,
                finished_at=utc_now(),
                status=JobStatus.SUCCESS,
                records_processed=records_processed,
            )
        except Exception as exc:
            _logger.exception("Indicator job failed")
            status = build_job_error_status(
                raw=raw,
                started_at=started_at,
                finished_at=utc_now(),
                error_message=str(exc),
            )

        await self.publish_status(status)
        return status

    def _build_status(
        self,
        message: IndicatorJobMessage,
        started_at: datetime,
        finished_at: datetime,
        status: JobStatus,
        records_processed: int,
        error_message: str | None = None,
    ) -> JobStatusMessage:
        return JobStatusMessage(
            job_definition_id=message.job_definition_id,
            execution_id=message.execution_id,
            parent_execution_id=message.parent_execution_id,
            work_type=message.work_type,
            work_key=message.work_key,
            status=status,
            started_at=started_at,
            finished_at=finished_at,
            error_message=error_message,
            records_processed=records_processed,
            duration_ms=calculate_duration_ms(started_at, finished_at),
            meta_json={"recordsProcessed": records_processed},
        )
