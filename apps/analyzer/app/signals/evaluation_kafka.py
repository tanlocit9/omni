from __future__ import annotations

import json
import logging
from typing import Any

from py_common.kafka.job_status_service import JobStatusKafkaService
from py_common.messaging import (
    JobStatus,
    JobStatusMessage,
    build_job_error_status,
    calculate_duration_ms,
    utc_now,
)

from app.settings import AppSettings
from app.signals.evaluator import SignalOutcomeEvaluator
from app.signals.messages import SignalEvaluationJobMessage

_logger = logging.getLogger(__name__)


class SignalEvaluationKafkaService(JobStatusKafkaService):
    """Kafka lifecycle for actual outcome evaluation jobs."""

    def __init__(
        self,
        settings: AppSettings,
        evaluator: SignalOutcomeEvaluator,
    ) -> None:
        super().__init__(
            kafka_settings=settings.kafka,
            input_topic=settings.topic_evaluate_signals,
            status_topic=settings.sync_job_status_topic,
            group_id="analyzer-signal-evaluation",
            service_name="analyzer-signal-evaluation",
        )
        self._evaluator = evaluator

    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage | None:
        started_at = utc_now()
        raw: dict[str, Any] | None = None
        try:
            raw = self._decode_payload(payload)
            message = SignalEvaluationJobMessage.model_validate(raw)
        except Exception as exc:
            if isinstance(raw, dict) and raw.get("executionId"):
                status = build_job_error_status(
                    raw=raw,
                    started_at=started_at,
                    finished_at=utc_now(),
                    error_message=str(exc),
                )
                await self.publish_status(status)
                return status
            _logger.warning("Skipping malformed signal evaluation payload: %s", exc)
            return None

        try:
            evaluation = await self._evaluator.evaluate(raw)
            status = self._build_status(message, started_at, utc_now(), evaluation)
        except Exception as exc:
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
        message: SignalEvaluationJobMessage,
        started_at,
        finished_at,
        evaluation,
    ) -> JobStatusMessage:
        metadata = {
            **evaluation.metadata,
            "exchange": message.exchange,
            "timeframe": message.timeframe,
            "strategy": message.strategy,
            "recordsProcessed": evaluation.records_scanned,
        }
        return JobStatusMessage(
            job_definition_id=message.job_definition_id,
            execution_id=message.execution_id,
            parent_execution_id=message.parent_execution_id,
            symbol_key=None,
            status=JobStatus.SUCCESS,
            started_at=started_at,
            finished_at=finished_at,
            records_processed=evaluation.records_scanned,
            duration_ms=calculate_duration_ms(started_at, finished_at),
            meta_json=metadata,
        )

    @staticmethod
    def _decode_payload(payload: str | bytes | dict[str, Any]) -> dict[str, Any]:
        if isinstance(payload, dict):
            return payload
        if isinstance(payload, bytes):
            payload = payload.decode("utf-8")
        return json.loads(payload)
