"""MinIO storage adapter.

Implements ``ReadableStorage``, ``WritableStorage``, ``CopyableStorage``,
``DeletableStorage``, and ``ListableStorage`` on top of the synchronous MinIO
Python SDK.

All SDK calls are offloaded to a thread pool via ``asyncio.to_thread()`` so
they never block the event loop.  Both ingestor (aiokafka consumer loop) and
analyzer (FastAPI/Uvicorn) are async, making this non-negotiable.

Exception mapping:
    minio.error.S3Error  (NoSuchKey / NoSuchBucket)
        → StorageObjectNotFoundError
    minio.error.S3Error  (all other codes)
        → StorageReadError / StorageWriteError / StorageDeleteError
    Any other exception
        → StorageReadError / StorageWriteError / StorageDeleteError

MinIO SDK errors must NOT propagate into business logic.
"""

from __future__ import annotations

import asyncio
import io
import logging
from typing import TYPE_CHECKING

from py_common.storage.base import BaseStorageAdapter
from py_common.storage.exceptions import (
    StorageDeleteError,
    StorageObjectNotFoundError,
    StorageReadError,
    StorageWriteError,
)
from py_common.storage.ports import (
    CopyableStorage,
    DeletableStorage,
    ListableStorage,
    ReadableStorage,
    WritableStorage,
)
from py_common.storage.providers import StorageProvider

if TYPE_CHECKING:
    from minio import Minio

logger = logging.getLogger(__name__)

# S3 error codes that mean "object does not exist"
_NOT_FOUND_CODES = frozenset({"NoSuchKey", "NoSuchObject", "NoSuchBucket"})


def _is_not_found(exc: Exception) -> bool:
    """Return ``True`` if the exception represents a missing object/bucket."""
    try:
        # minio.error.S3Error has a ``code`` attribute
        from minio.error import S3Error  # noqa: PLC0415

        return isinstance(exc, S3Error) and exc.code in _NOT_FOUND_CODES
    except ImportError:
        return False


class MinioStorageAdapter(
    BaseStorageAdapter,
    ReadableStorage,
    WritableStorage,
    CopyableStorage,
    DeletableStorage,
    ListableStorage,
):
    """Async storage adapter backed by the MinIO Python SDK.

    The MinIO SDK is synchronous; every method wraps SDK calls in
    ``asyncio.to_thread()`` to avoid blocking the event loop.

    Args:
        client: A fully configured ``minio.Minio`` client instance.
            Use ``create_minio_client(settings.minio)`` from
            ``py_common.storage.adapters.factory`` to construct one.

    Example::

        from minio import Minio
        from py_common.storage.adapters.minio import MinioStorageAdapter

        client = Minio(
            endpoint="localhost:9000",
            access_key="minioadmin",
            secret_key="minioadmin",
            secure=False,
        )
        adapter = MinioStorageAdapter(client)
        await adapter.validate()
    """

    def __init__(self, client: Minio) -> None:
        super().__init__()
        self._client = client

    # ------------------------------------------------------------------
    # BaseStorageAdapter interface
    # ------------------------------------------------------------------

    @property
    def provider(self) -> StorageProvider:
        return StorageProvider.MINIO

    async def _do_validate(self) -> None:
        """List buckets to assert connectivity and credential validity."""
        await asyncio.to_thread(self._client.list_buckets)

    # ------------------------------------------------------------------
    # Bucket provisioning (MinIO-specific — not part of any port)
    # ------------------------------------------------------------------

    async def ensure_bucket(self, bucket: str) -> None:
        """Create the bucket if it does not already exist.

        This is intentionally NOT called during ``validate()`` or the
        constructor.  Services that need to provision storage (e.g.
        ingestor on startup) call this explicitly.  Read-only services
        (e.g. analyzer) should validate that the bucket exists rather
        than creating it.

        Args:
            bucket: Bucket name to create if absent.
        """

        def _ensure() -> None:
            if not self._client.bucket_exists(bucket):
                self._client.make_bucket(bucket)
                logger.info("Created bucket '%s'", bucket)
            else:
                logger.debug("Bucket '%s' already exists", bucket)

        await asyncio.to_thread(_ensure)

    # ------------------------------------------------------------------
    # ReadableStorage
    # ------------------------------------------------------------------

    async def read_bytes(
        self,
        bucket: str,
        object_name: str,
    ) -> bytes:
        """Download an object and return its full content as bytes.

        The HTTP response is always released after reading to avoid
        connection-pool exhaustion.

        Args:
            bucket: Bucket name.
            object_name: Object key.

        Returns:
            Raw bytes of the object.

        Raises:
            StorageObjectNotFoundError: Object or bucket does not exist.
            StorageReadError: Any other transport / server failure.
        """

        def _read() -> bytes:
            response = None
            try:
                response = self._client.get_object(bucket, object_name)
                return response.read()
            finally:
                if response is not None:
                    response.close()
                    response.release_conn()

        try:
            return await asyncio.to_thread(_read)
        except Exception as exc:
            if _is_not_found(exc):
                raise StorageObjectNotFoundError(bucket, object_name) from exc
            raise StorageReadError(bucket, object_name, exc) from exc

    # ------------------------------------------------------------------
    # WritableStorage
    # ------------------------------------------------------------------

    async def write_bytes(
        self,
        bucket: str,
        object_name: str,
        data: bytes,
        content_type: str,
    ) -> None:
        """Upload bytes as an object, overwriting any existing content.

        Args:
            bucket: Bucket name.
            object_name: Object key.
            data: Raw bytes to upload.
            content_type: MIME type string.

        Raises:
            StorageWriteError: If the upload fails.
        """

        def _write() -> None:
            self._client.put_object(
                bucket_name=bucket,
                object_name=object_name,
                data=io.BytesIO(data),
                length=len(data),
                content_type=content_type,
            )

        try:
            await asyncio.to_thread(_write)
        except Exception as exc:
            raise StorageWriteError(bucket, object_name, exc) from exc

    # ------------------------------------------------------------------
    # CopyableStorage
    # ------------------------------------------------------------------

    async def copy_object(
        self,
        bucket: str,
        source_object_name: str,
        target_object_name: str,
        content_type: str | None = None,
    ) -> None:
        """Copy an object to another key in the same bucket.

        Args:
            bucket: Bucket name.
            source_object_name: Source object key.
            target_object_name: Target object key.
            content_type: Optional target content type metadata. MinIO copy
                preserves source metadata by default; callers should not rely
                on metadata mutation in this first implementation.

        Raises:
            StorageObjectNotFoundError: Source object or bucket does not exist.
            StorageWriteError: Copy operation fails.
        """

        def _copy() -> None:
            from minio.commonconfig import CopySource  # noqa: PLC0415

            self._client.copy_object(
                bucket_name=bucket,
                object_name=target_object_name,
                source=CopySource(bucket, source_object_name),
            )

        try:
            await asyncio.to_thread(_copy)
        except Exception as exc:
            if _is_not_found(exc):
                raise StorageObjectNotFoundError(bucket, source_object_name) from exc
            raise StorageWriteError(bucket, target_object_name, exc) from exc

    # ------------------------------------------------------------------
    # DeletableStorage
    # ------------------------------------------------------------------

    async def delete(
        self,
        bucket: str,
        object_name: str,
    ) -> None:
        """Remove an object from the bucket.

        Args:
            bucket: Bucket name.
            object_name: Object key.

        Raises:
            StorageObjectNotFoundError: Object or bucket does not exist.
            StorageDeleteError: Any other failure.
        """
        try:
            await asyncio.to_thread(
                self._client.remove_object,
                bucket,
                object_name,
            )
        except Exception as exc:
            if _is_not_found(exc):
                raise StorageObjectNotFoundError(bucket, object_name) from exc
            raise StorageDeleteError(bucket, object_name, exc) from exc

    # ------------------------------------------------------------------
    # ListableStorage
    # ------------------------------------------------------------------

    async def list_objects(
        self,
        bucket: str,
        prefix: str = "",
    ) -> list[str]:
        """List object keys in the bucket, optionally filtered by prefix.

        Args:
            bucket: Bucket name.
            prefix: Key prefix filter (default: ``""`` lists all objects).

        Returns:
            Sorted list of object keys matching the prefix.

        Raises:
            StorageReadError: If the listing operation fails.
        """

        def _list() -> list[str]:
            objects = self._client.list_objects(
                bucket_name=bucket,
                prefix=prefix or None,
                recursive=True,
            )
            return sorted(obj.object_name for obj in objects)

        try:
            return await asyncio.to_thread(_list)
        except Exception as exc:
            if _is_not_found(exc):
                raise StorageObjectNotFoundError(bucket, prefix) from exc
            raise StorageReadError(bucket, prefix, exc) from exc