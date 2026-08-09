from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
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
from pydantic import BaseModel

from app.sector_transition.handler import SectorTransitionJobHandler
from app.sector_transition.messages import (
    SectorTransitionAnalyzeJobMessage,
    SectorTransitionOutcomeEvaluationJobMessage,
)
from app.settings import AppSettings

SectorTransitionJobMessage = (
    SectorTransitionAnalyzeJobMessage | SectorTransitionOutcomeEvaluationJobMessage
)

_logger = logging.getLogger(__name__)


class SectorTransitionKafkaService(JobStatusKafkaService):
    """Kafka lifecycle for one Sector Transition research job topic."""

    def __init__(
        self,
        settings: AppSettings,
        handler: SectorTransitionJobHandler,
        *,
        input_topic: str,
        service_name: str,
        model_type: type[BaseModel],
        handle: Callable[[dict[str, Any]], Awaitable[int]],
    ) -> None:
        self._handler = handler
        self._model_type = model_type
        self._handle = handle
        super().__init__(
            kafka_settings=settings.kafka,
            input_topic=input_topic,
            status_topic=settings.sync_job_status_topic,
            group_id=ConsumerGroup.ANALYZER.for_topic(input_topic),
            service_name=service_name,
            task_name=f"{service_name}-kafka-consumer",
        )

    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage:
        started_at = utc_now()
        raw: dict[str, Any] = {}
        try:
            raw = decode_json_object_payload(payload, "Sector Transition job")
            message = self._model_type.model_validate(raw)
            records_processed = await self._handle(raw)
            status = self._build_status(
                message=message,
                started_at=started_at,
                finished_at=utc_now(),
                status=JobStatus.SUCCESS,
                records_processed=records_processed,
            )
        except Exception as exc:
            _logger.exception("Sector Transition job failed")
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
        message: SectorTransitionJobMessage,
        started_at: datetime,
        finished_at: datetime,
        status: JobStatus,
        records_processed: int,
        error_message: str | None = None,
    ) -> JobStatusMessage:
        symbol_key = (
            f"sector-transition:{message.strategy}:"
            f"{message.evaluation_date.isoformat()}"
        )
        return JobStatusMessage(
            job_definition_id=message.job_definition_id,
            execution_id=message.execution_id,
            parent_execution_id=message.parent_execution_id,
            symbol_key=symbol_key,
            status=status,
            started_at=started_at,
            finished_at=finished_at,
            error_message=error_message,
            records_processed=records_processed,
            duration_ms=calculate_duration_ms(started_at, finished_at),
            meta_json={
                "recordsProcessed": records_processed,
                "evaluationDate": message.evaluation_date.isoformat(),
                "sectorCodes": message.sector_codes,
                "predictionHorizons": message.prediction_horizons,
            },
        )


class SectorTransitionAnalyzeKafkaService(SectorTransitionKafkaService):
    def __init__(
        self,
        settings: AppSettings,
        handler: SectorTransitionJobHandler,
    ) -> None:
        super().__init__(
            settings,
            handler,
            input_topic=settings.topic_sector_transition_analyze,
            service_name="sector-transition-analyze",
            model_type=SectorTransitionAnalyzeJobMessage,
            handle=handler.handle_analyze,
        )


class SectorTransitionOutcomeEvaluationKafkaService(SectorTransitionKafkaService):
    def __init__(
        self,
        settings: AppSettings,
        handler: SectorTransitionJobHandler,
    ) -> None:
        super().__init__(
            settings,
            handler,
            input_topic=settings.topic_sector_transition_evaluate_outcomes,
            service_name="sector-transition-outcome-evaluation",
            model_type=SectorTransitionOutcomeEvaluationJobMessage,
            handle=handler.handle_evaluate_outcomes,
        )
