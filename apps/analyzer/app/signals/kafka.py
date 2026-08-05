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
from pydantic import ValidationError

from app.settings import AppSettings
from app.signals.handler import SignalJobHandler
from app.signals.messages import SignalJobMessage
from app.signals.storage import SignalTransition

_logger = logging.getLogger(__name__)


class SignalKafkaService(JobStatusKafkaService):
    """Kafka lifecycle for Analyzer Market Signal V1 jobs."""

    def __init__(self, settings: AppSettings, handler: SignalJobHandler) -> None:
        self._settings = settings
        self._handler = handler
        topic = settings.topic_sync_signals
        super().__init__(
            kafka_settings=settings.kafka,
            input_topic=topic,
            status_topic=settings.sync_job_status_topic,
            group_id=ConsumerGroup.ANALYZER.for_topic(topic),
            service_name="signal",
            task_name="signal-kafka-consumer",
        )

    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage | None:
        started_at = utc_now()
        raw: dict[str, Any] = {}
        try:
            raw = decode_json_object_payload(payload, "Signal job")
        except Exception:
            _logger.warning(
                "Skipping malformed signal payload without publishing status"
            )
            return None

        if not str(raw.get("executionId", "")).strip():
            _logger.warning("Skipping signal payload without executionId")
            return None

        try:
            message = SignalJobMessage.model_validate(raw)
            transition = await self._handler.handle(raw)
            status = self._build_status(message, started_at, utc_now(), transition)
        except ValidationError:
            _logger.warning("Skipping invalid signal payload contract", exc_info=True)
            return None
        except Exception as exc:
            _logger.exception("Signal job failed")
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
        message: SignalJobMessage,
        started_at: datetime,
        finished_at: datetime,
        transition: SignalTransition,
    ) -> JobStatusMessage:
        meta_json = dict(transition.metadata)
        meta_json["recordsProcessed"] = 1
        return JobStatusMessage(
            job_definition_id=message.job_definition_id,
            execution_id=message.execution_id,
            parent_execution_id=message.parent_execution_id,
            symbol_key=message.symbol_key,
            status=JobStatus.SUCCESS,
            started_at=started_at,
            finished_at=finished_at,
            error_message=None,
            records_processed=1,
            duration_ms=calculate_duration_ms(started_at, finished_at),
            meta_json=meta_json,
        )
