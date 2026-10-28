from __future__ import annotations

import asyncio
import contextlib
import logging
from abc import ABC, abstractmethod
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, ConsumerRecord

from py_common.config.models import KafkaSettings
from py_common.kafka.factory import KafkaClientFactory
from py_common.kafka.processing import consume_job_records
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
        if self._task is not None and not self._task.done():
            return
        # Keep initial startup failures visible to the application lifecycle.
        try:
            await self._start_clients()
        except BaseException:
            await self._close_clients()
            raise
        self._task = asyncio.create_task(self._consume_loop(), name=self._task_name)

    async def _start_clients(self) -> None:
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
        _logger.info(
            "Started %s Kafka consumer topic=%s groupId=%s",
            self._service_name,
            self._input_topic,
            self._group_id,
        )

    async def _close_clients(self) -> None:
        consumer, producer = self._consumer, self._producer
        self._consumer = self._producer = self._publisher = None
        try:
            if consumer is not None:
                await consumer.stop()
        finally:
            if producer is not None:
                await producer.stop()

    async def stop(self) -> None:
        try:
            if self._task is not None:
                self._task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await self._task
        finally:
            await self._close_clients()
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
        # TODO(TD-009): Add bounded concurrency only after offset and capacity
        # evidence. See the TD-009 technical-debt record.
        await consume_job_records(self._consumer, self._process_record)

    async def _process_record(self, record: ConsumerRecord) -> JobStatusMessage | None:
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
        return await self.process_payload(payload)

    @abstractmethod
    async def process_payload(
        self,
        payload: str | bytes | dict[str, Any],
    ) -> JobStatusMessage | None:
        """Await output/status publication, then return the acknowledged status.

        Returning None leaves the command uncommitted and stops consumption.
        """
