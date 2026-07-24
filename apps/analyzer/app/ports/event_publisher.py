from typing import Protocol


class EventPublisher(Protocol):
    """Event publishing port for analyzer integration events."""

    async def publish_json(self, topic: str, payload: dict) -> None:
        """Publish a JSON-compatible payload to a topic."""
