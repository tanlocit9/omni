import json
import logging
from collections.abc import Mapping
from typing import Any

from py_common.kafka.factory import KafkaClientFactory

from app.settings import Settings, settings

_logger = logging.getLogger(__name__)


class KafkaEventPublisher:
    """Kafka-backed event publisher adapter."""

    def __init__(self, config: Settings = settings) -> None:
        self._config = config

    async def publish_json(self, topic: str, payload: Mapping[str, Any]) -> None:
        producer = KafkaClientFactory.create_producer(
            self._config.kafka,
            value_serializer=lambda value: json.dumps(value).encode("utf-8"),
        )
        await producer.start()
        _logger.info(
            "Kafka event producer connected topic=%s bootstrap=%s",
            topic,
            self._config.kafka.bootstrap_servers,
        )
        try:
            result = await producer.send_and_wait(topic, dict(payload))
            _logger.info(
                "Published Kafka event topic=%s partition=%s offset=%s",
                result.topic,
                result.partition,
                result.offset,
            )
        finally:
            await producer.stop()
            _logger.info("Kafka event producer stopped topic=%s", topic)
