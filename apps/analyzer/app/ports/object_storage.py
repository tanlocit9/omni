from typing import Protocol


class ObjectStorage(Protocol):
    """Read-only object storage port for analyzer data lake access."""

    async def read_bytes(self, object_name: str) -> bytes:
        """Read an object from the configured data lake bucket."""