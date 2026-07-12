import json
from collections.abc import Mapping
from typing import Any

from aiokafka import AIOKafkaProducer

from app.settings import Settings, settings


class KafkaEventPublisher:
    """Kafka-backed event publisher adapter."""

    def __init__(self, config: Settings = settings) -> None:
        self._bootstrap_servers = config.kafka_bootstrap

    async def publish_json(self, topic: str, payload: Mapping[str, Any]) -> None:
        producer = AIOKafkaProducer(
            bootstrap_servers=self._bootstrap_servers,
            value_serializer=lambda value: json.dumps(value).encode("utf-8"),
        )
        await producer.start()
        try:
            await producer.send_and_wait(topic, dict(payload))
        finally:
            await producer.stop()