from __future__ import annotations

import asyncio
import contextlib
import logging
from abc import ABC, abstractmethod
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from py_common.config.models import KafkaSettings
from py_common.kafka.factory import KafkaClientFactory
from py_common.messaging import JobStatusMessage, JobStatusPublisher

_logger = logging.getLogger(__name__)


class JobStatusKafkaService(ABC):
    """Reusable Kafka lifecycle for jobs that publish JobStatusMessage results."""

    def __init__(
        self,
        *,
        kafka_settings: KafkaSettings,
        input_topic: str,
        status_topic: str,
        group_id: str,
        service_name: str,
        task_name: str | None = None,
    ) -> None:
        self._kafka_settings = kafka_settings
        self._input_topic = input_topic
        self._status_topic = status_topic
        self._group_id = group_id
        self._service_name = service_name
        self._task_name = task_name or f"{service_name}-kafka-consumer"
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._publisher: JobStatusPublisher | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        self._consumer = KafkaClientFactory.create_consumer(
            self._kafka_settings,
            topics=self._input_topic,
            group_id=self._group_id,
        )
        self._producer = KafkaClientFactory.create_producer(self._kafka_settings)
        self._publisher = JobStatusPublisher(
            self._producer,
            self._status_topic,
            self._service_name,
        )
        await self._consumer.start()
        _logger.info(
            "%s Kafka consumer connected topic=%s groupId=%s bootstrap=%s",
            self._service_name,
            self._input_topic,
            self._group_id,
            self._kafka_settings.bootstrap_servers,
        )
        await self._producer.start()
        _logger.info(
            "%s Kafka producer connected statusTopic=%s bootstrap=%s",
            self._service_name,
            self._status_topic,
            self._kafka_settings.bootstrap_servers,
        )
        self._task = asyncio.create_task(self._consume_loop(), name=self._task_name)
        _logger.info(
            "Started %s Kafka consumer topic=%s groupId=%s",
            self._service_name,
            self._input_topic,
            self._group_id,
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
        _logger.info("Stopped %s Kafka service", self._service_name)

    async def publish_status(self, status: JobStatusMessage) -> None:
        if self._publisher is None:
            assert self._producer is not None
            self._publisher = JobStatusPublisher(
                self._producer,
                self._status_topic,
                self._service_name,
            )
        await self._publisher.publish(status)

    async def _consume_loop(self) -> None:
        assert self._consumer is not None
        async for record in self._consumer:
            _logger.info(
                "Received %s Kafka message topic=%s partition=%s offset=%s key=%s",
                self._service_name,
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

    @abstractmethod
    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage | None:
        """Process one decoded Kafka payload and publish/return a status if needed."""
