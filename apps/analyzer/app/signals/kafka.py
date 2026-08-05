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
from pydantic import ValidationError

from app.settings import AppSettings
from app.signals.handler import SignalJobHandler
from app.signals.messages import SignalJobMessage
from app.signals.storage import SignalTransition

_logger = logging.getLogger(__name__)


class SignalKafkaService:
    """Kafka lifecycle for Analyzer Market Signal V1 jobs."""

    def __init__(self, settings: AppSettings, handler: SignalJobHandler) -> None:
        self._settings = settings
        self._handler = handler
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        topic = self._settings.topic_sync_signals
        group_id = ConsumerGroup.ANALYZER.for_topic(topic)
        self._consumer = KafkaClientFactory.create_consumer(
            self._settings.kafka,
            topics=topic,
            group_id=group_id,
        )
        self._producer = KafkaClientFactory.create_producer(self._settings.kafka)
        await self._consumer.start()
        _logger.info(
            "Signal Kafka consumer connected topic=%s groupId=%s bootstrap=%s",
            topic,
            group_id,
            self._settings.kafka.bootstrap_servers,
        )
        await self._producer.start()
        _logger.info(
            "Signal Kafka producer connected statusTopic=%s bootstrap=%s",
            self._settings.sync_job_status_topic,
            self._settings.kafka.bootstrap_servers,
        )
        self._task = asyncio.create_task(
            self._consume_loop(), name="signal-kafka-consumer"
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
        _logger.info("Stopped signal Kafka service")

    async def _consume_loop(self) -> None:
        assert self._consumer is not None
        async for record in self._consumer:
            _logger.info(
                "Received signal Kafka message topic=%s partition=%s offset=%s key=%s",
                record.topic,
                record.partition,
                record.offset,
                record.key.decode("utf-8", errors="replace") if record.key else None,
            )
            payload = (
                record.value.decode("utf-8")
                if isinstance(record.value, bytes)
                else record.value
            )
            await self.process_payload(payload)

    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage | None:
        started_at = utc_now()
        raw: dict[str, Any] = {}
        try:
            raw = self._decode_payload(payload)
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
            status = self._build_error_status(raw, started_at, exc)

        await self._publish_status(status)
        return status

    async def _publish_status(self, status: JobStatusMessage) -> None:
        assert self._producer is not None
        result = await self._producer.send_and_wait(
            self._settings.sync_job_status_topic,
            status.model_dump_json(by_alias=True).encode("utf-8"),
            key=status.symbol_key.encode("utf-8"),
        )
        _logger.info(
            "Published signal sync status for %s to topic=%s partition=%s offset=%s",
            status.symbol_key,
            result.topic,
            result.partition,
            result.offset,
        )

    def _build_status(
        self,
        message: SignalJobMessage,
        started_at: datetime,
        finished_at: datetime,
        transition: SignalTransition,
    ) -> JobStatusMessage:
        duration_ms = int((finished_at - started_at).total_seconds() * 1000)
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
            duration_ms=duration_ms,
            meta_json=meta_json,
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
            raise ValueError("Signal job payload must be a JSON object")
        return decoded
