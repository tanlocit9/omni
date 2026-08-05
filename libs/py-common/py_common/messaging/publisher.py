from __future__ import annotations

import logging

from aiokafka import AIOKafkaProducer

from py_common.messaging.messages import JobStatusMessage

_logger = logging.getLogger(__name__)


class JobStatusPublisher:
    """Publish job status messages to a shared status topic."""

    def __init__(
        self,
        producer: AIOKafkaProducer,
        status_topic: str,
        service_name: str = "job",
    ) -> None:
        self._producer = producer
        self._status_topic = status_topic
        self._service_name = service_name

    async def publish(self, status: JobStatusMessage, key: str | None = None) -> None:
        publish_key = key if key is not None else status.symbol_key
        result = await self._producer.send_and_wait(
            self._status_topic,
            status.model_dump_json(by_alias=True).encode("utf-8"),
            key=publish_key.encode("utf-8") if publish_key else None,
        )
        _logger.info(
            "Published %s status for %s to topic=%s partition=%s offset=%s",
            self._service_name,
            publish_key,
            result.topic,
            result.partition,
            result.offset,
        )
