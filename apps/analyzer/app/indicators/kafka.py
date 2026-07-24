from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from datetime import datetime
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from py_common.config import ConsumerGroup
from py_common.kafka.factory import KafkaClientFactory
from py_common.messaging import JobStatus, JobStatusMessage, utc_now

from app.indicators.handler import IndicatorJobHandler
from app.indicators.messages import IndicatorJobMessage
from app.settings import AppSettings

_logger = logging.getLogger(__name__)


class IndicatorKafkaService:
    """Kafka lifecycle for Analyzer indicator jobs."""

    def __init__(self, settings: AppSettings, handler: IndicatorJobHandler) -> None:
        self._settings = settings
        self._handler = handler
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        topic = self._settings.topic_sync_indicators
        group_id = ConsumerGroup.ANALYZER.for_topic(topic)
        self._consumer = KafkaClientFactory.create_consumer(
            self._settings.kafka,
            topics=topic,
            group_id=group_id,
        )
        self._producer = KafkaClientFactory.create_producer(self._settings.kafka)
        await self._consumer.start()
        await self._producer.start()
        self._task = asyncio.create_task(
            self._consume_loop(), name="indicator-kafka-consumer"
        )
        _logger.info(
            "Started indicator Kafka consumer topic=%s groupId=%s", topic, group_id
        )

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
        if self._consumer is not None:
            await self._consumer.stop()
        if self._producer is not None:
            await self._producer.stop()
        _logger.info("Stopped indicator Kafka service")

    async def _consume_loop(self) -> None:
        assert self._consumer is not None
        async for record in self._consumer:
            payload = (
                record.value.decode("utf-8")
                if isinstance(record.value, bytes)
                else record.value
            )
            await self.process_payload(payload)

    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage:
        started_at = utc_now()
        raw: dict[str, Any] = {}
        try:
            raw = self._decode_payload(payload)
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
            status = self._build_error_status(raw, started_at, exc)

        await self._publish_status(status)
        return status

    async def _publish_status(self, status: JobStatusMessage) -> None:
        assert self._producer is not None
        await self._producer.send_and_wait(
            self._settings.sync_job_status_topic,
            status.model_dump_json(by_alias=True).encode("utf-8"),
            key=status.symbol_key.encode("utf-8"),
        )

    def _build_status(
        self,
        message: IndicatorJobMessage,
        started_at: datetime,
        finished_at: datetime,
        status: JobStatus,
        records_processed: int,
        error_message: str | None = None,
    ) -> JobStatusMessage:
        duration_ms = int((finished_at - started_at).total_seconds() * 1000)
        return JobStatusMessage(
            job_definition_id=message.job_definition_id,
            execution_id=message.execution_id,
            parent_execution_id=message.parent_execution_id,
            symbol_key=message.symbol_key,
            status=status,
            started_at=started_at,
            finished_at=finished_at,
            error_message=error_message,
            records_processed=records_processed,
            duration_ms=duration_ms,
            meta_json={"recordsProcessed": records_processed},
        )

    def _build_error_status(
        self,
        raw: dict[str, Any],
        started_at: datetime,
        exc: Exception,
    ) -> JobStatusMessage:
        finished_at = utc_now()
        duration_ms = int((finished_at - started_at).total_seconds() * 1000)
        return JobStatusMessage(
            job_definition_id=str(raw.get("jobDefinitionId", "")),
            execution_id=str(raw.get("executionId", "")),
            parent_execution_id=raw.get("parentExecutionId"),
            symbol_key=str(raw.get("symbolKey", "")),
            status=JobStatus.ERROR,
            started_at=started_at,
            finished_at=finished_at,
            error_message=str(exc),
            records_processed=0,
            duration_ms=duration_ms,
            meta_json={"recordsProcessed": 0},
        )

    def _decode_payload(self, payload: str | bytes | dict[str, Any]) -> dict[str, Any]:
        if isinstance(payload, dict):
            return payload
        if isinstance(payload, bytes):
            payload = payload.decode("utf-8")
        decoded = json.loads(payload)
        if not isinstance(decoded, dict):
            raise ValueError("Indicator job payload must be a JSON object")
        return decoded
