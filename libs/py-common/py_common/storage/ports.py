"""Storage port protocols (read, write, delete, list).

Each protocol is ``runtime_checkable`` so that ``isinstance()`` can be used
by the registry to detect which capabilities an adapter supports without
requiring explicit capability declarations.

Design notes:
- All methods are async; both ingestor and analyzer run async event loops.
- These are structural protocols (duck-typing) — adapters do not need to
  import or subclass these protocols, only satisfy their signatures.
- The ``StorageProviderInfo`` protocol is implemented by ``BaseStorageAdapter``
  and is used by the registry to manage adapter lifecycle.
"""

from typing import Protocol, runtime_checkable

from py_common.storage.providers import StorageProvider


@runtime_checkable
class StorageProviderInfo(Protocol):
    """Protocol for adapter identity and health status.

    Implemented by ``BaseStorageAdapter``.  The registry uses these properties
    to route requests and guard against inactive adapters.
    """

    @property
    def provider(self) -> StorageProvider:
        """The provider identifier for this adapter."""
        ...

    @property
    def is_active(self) -> bool:
        """``True`` after a successful ``validate()`` call."""
        ...

    @property
    def last_error(self) -> str | None:
        """Human-readable message from the most recent validation failure."""
        ...

    async def validate(self) -> None:
        """Assert the backend is reachable and credentials are valid.

        Raises:
            StorageValidationError: If the connectivity check fails.
        """
        ...


@runtime_checkable
class ReadableStorage(Protocol):
    """Port for reading raw bytes from object storage."""

    async def read_bytes(
        self,
        bucket: str,
        object_name: str,
    ) -> bytes:
        """Download an object and return its contents as bytes.

        Args:
            bucket: Bucket (or container) name.
            object_name: Full object key / path within the bucket.

        Returns:
            Raw object bytes.

        Raises:
            StorageObjectNotFoundError: Object does not exist.
            StorageReadError: Any other read failure (auth, network, server).
        """
        ...


@runtime_checkable
class WritableStorage(Protocol):
    """Port for writing raw bytes to object storage."""

    async def write_bytes(
        self,
        bucket: str,
        object_name: str,
        data: bytes,
        content_type: str,
    ) -> None:
        """Upload bytes as an object.

        Args:
            bucket: Bucket (or container) name.
            object_name: Full object key / path within the bucket.
            data: Raw bytes to upload.
            content_type: MIME type (e.g. ``"application/vnd.apache.parquet"``).

        Raises:
            StorageWriteError: If the upload fails.
        """
        ...


@runtime_checkable
class CopyableStorage(Protocol):
    """Port for copying objects within object storage."""

    async def copy_object(
        self,
        bucket: str,
        source_object_name: str,
        target_object_name: str,
        content_type: str | None = None,
    ) -> None:
        """Copy an object to another key within the same bucket.

        Args:
            bucket: Bucket (or container) name.
            source_object_name: Source object key / path within the bucket.
            target_object_name: Target object key / path within the bucket.
            content_type: Optional MIME type metadata for the target object.

        Raises:
            StorageObjectNotFoundError: Source object does not exist.
            StorageWriteError: Copy operation fails.
        """
        ...


@runtime_checkable
class DeletableStorage(Protocol):
    """Port for deleting objects from object storage."""

    async def delete(
        self,
        bucket: str,
        object_name: str,
    ) -> None:
        """Remove an object.

        Args:
            bucket: Bucket (or container) name.
            object_name: Full object key / path within the bucket.

        Raises:
            StorageObjectNotFoundError: Object does not exist.
            StorageDeleteError: Any other deletion failure.
        """
        ...


@runtime_checkable
class ListableStorage(Protocol):
    """Port for listing objects in object storage."""

    async def list_objects(
        self,
        bucket: str,
        prefix: str = "",
    ) -> list[str]:
        """List object keys under an optional prefix.

        Args:
            bucket: Bucket (or container) name.
            prefix: Key prefix filter (empty string lists all objects).

        Returns:
            Sorted list of object keys matching the prefix.

        Raises:
            StorageReadError: If the listing operation fails.
        """
        ...
