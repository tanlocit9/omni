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

from app.sector_wave.handler import SectorWaveJobHandler
from app.sector_wave.messages import (
    SectorRotationBacktestJobMessage,
    SectorWaveSectorFeatureJobMessage,
    SectorWaveSymbolFeatureJobMessage,
)
from app.settings import AppSettings

SectorWaveJobMessage = (
    SectorRotationBacktestJobMessage
    | SectorWaveSectorFeatureJobMessage
    | SectorWaveSymbolFeatureJobMessage
)

_logger = logging.getLogger(__name__)


class SectorWaveKafkaService(JobStatusKafkaService):
    """Kafka lifecycle for one Sector Wave job topic."""

    def __init__(
        self,
        settings: AppSettings,
        handler: SectorWaveJobHandler,
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
            raw = decode_json_object_payload(payload, "Sector Wave job")
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
            _logger.exception("Sector Wave job failed")
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
        message: SectorWaveJobMessage,
        started_at: datetime,
        finished_at: datetime,
        status: JobStatus,
        records_processed: int,
        error_message: str | None = None,
    ) -> JobStatusMessage:
        symbol_key = getattr(message, "symbol_key", None)
        if symbol_key is None:
            symbol_key = getattr(message, "sector_code", None)
        if symbol_key is None:
            symbol_key = getattr(message, "strategy", None)
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
            meta_json={"recordsProcessed": records_processed},
        )


class SectorWaveSymbolFeatureKafkaService(SectorWaveKafkaService):
    def __init__(self, settings: AppSettings, handler: SectorWaveJobHandler) -> None:
        super().__init__(
            settings,
            handler,
            input_topic=settings.topic_precompute_symbol_features,
            service_name="sector-wave-symbol-features",
            model_type=SectorWaveSymbolFeatureJobMessage,
            handle=handler.handle_symbol_features,
        )


class SectorWaveSectorFeatureKafkaService(SectorWaveKafkaService):
    def __init__(self, settings: AppSettings, handler: SectorWaveJobHandler) -> None:
        super().__init__(
            settings,
            handler,
            input_topic=settings.topic_precompute_sector_features,
            service_name="sector-wave-sector-features",
            model_type=SectorWaveSectorFeatureJobMessage,
            handle=handler.handle_sector_features,
        )


class SectorRotationBacktestKafkaService(SectorWaveKafkaService):
    def __init__(self, settings: AppSettings, handler: SectorWaveJobHandler) -> None:
        super().__init__(
            settings,
            handler,
            input_topic=settings.topic_sector_rotation_backtest,
            service_name="sector-rotation-backtest",
            model_type=SectorRotationBacktestJobMessage,
            handle=handler.handle_sector_rotation_backtest,
        )
