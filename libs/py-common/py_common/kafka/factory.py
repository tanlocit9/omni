import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from py_common.config.models import KafkaSettings

_logger = logging.getLogger(__name__)


class KafkaClientFactory:
    """Factory for creating AIOKafka clients."""

    @staticmethod
    def create_producer(
        settings: KafkaSettings,
        value_serializer=None,
    ) -> AIOKafkaProducer:
        """Create an AIOKafkaProducer instance."""
        _logger.debug(f"Creating Kafka producer for bootstrap_servers={settings.bootstrap_servers}")
        return AIOKafkaProducer(
            bootstrap_servers=settings.bootstrap_servers,
            value_serializer=value_serializer,
            # Add other common configurations here if needed
        )

    @staticmethod
    def create_consumer(
        settings: KafkaSettings,
        topics: str | list[str],
        group_id: str,
        auto_offset_reset: str = "earliest",
    ) -> AIOKafkaConsumer:
        """Create an AIOKafkaConsumer instance."""
        _logger.debug(
            f"Creating Kafka consumer for topics={topics}, group_id={group_id}, "
            f"bootstrap_servers={settings.bootstrap_servers}"
        )
        if isinstance(topics, list):
            return AIOKafkaConsumer(
                *topics,
                bootstrap_servers=settings.bootstrap_servers,
                group_id=group_id,
                auto_offset_reset=auto_offset_reset,
                # Add other common configurations here if needed
            )
        return AIOKafkaConsumer(
            topics,
            bootstrap_servers=settings.bootstrap_servers,
            group_id=group_id,
            auto_offset_reset=auto_offset_reset,
            # Add other common configurations here if needed
        )
