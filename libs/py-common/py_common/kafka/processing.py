from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from uuid import UUID

from aiokafka import AIOKafkaConsumer, ConsumerRecord, TopicPartition

from py_common.messaging import JobStatusMessage

_logger = logging.getLogger(__name__)


async def consume_job_records(
    consumer: AIOKafkaConsumer,
    handle_record: Callable[[ConsumerRecord], Awaitable[JobStatusMessage | None]],
) -> None:
    """Commit each command only after its handler acknowledges terminal status.

    Handlers must await output and status publication before returning. Any
    exception, cancellation or missing execution identity stops this loop without
    advancing past the record. Recovery uses the last committed offset; duplicate
    processing after an output/status ack but before commit remains possible.
    """
    async for record in consumer:
        try:
            status = await handle_record(record)
            if status is None or not (status.execution_id or "").strip():
                raise ValueError("Command has no acknowledged execution status")
            # Platform parses execution IDs as UUIDs; never advance an invalid ID.
            UUID(status.execution_id)
            await consumer.commit(
                {TopicPartition(record.topic, record.partition): record.offset + 1}
            )
        except Exception:
            _logger.exception(
                "Command consumption stopped without advancing past failed record "
                "topic=%s partition=%s offset=%s; restart/recovery required",
                record.topic,
                record.partition,
                record.offset,
            )
            raise
