"""Parquet data-format service layered on top of storage ports.

``ParquetStorage`` sits above the storage ports and handles
serialization/deserialization of pandas DataFrames to/from Parquet bytes.
It is completely decoupled from MinIO — swap the provider in the registry
and the Parquet logic stays unchanged.

``ParquetCodec`` is separated out so it can be unit-tested independently
of any network or I/O concerns.

Semantics:
    read_dataframe(path)
        Object missing  → StorageObjectNotFoundError  (caller decides)
        Corrupt bytes   → ParquetDecodeError
    read_optional_dataframe(path)
        Object missing  → None
        Corrupt bytes   → ParquetDecodeError  (still propagated)
    write_dataframe(path, df)
        Upload failure  → StorageWriteError

Empty DataFrame and None have different meanings:
    None              → dataset does not exist yet
    empty DataFrame   → dataset exists but has no rows
"""

from __future__ import annotations

import asyncio
import io
import logging
from collections.abc import Callable
from pathlib import PurePosixPath
from uuid import uuid4

import pandas as pd

from py_common.storage.exceptions import (
    ParquetDecodeError,
    StorageObjectNotFoundError,
)
from py_common.storage.ports import (
    CopyableStorage,
    DeletableStorage,
    ReadableStorage,
    WritableStorage,
)
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

logger = logging.getLogger(__name__)

_PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet"


class ParquetCodec:
    """Stateless codec for converting DataFrames to/from Parquet bytes.

    Separated from ``ParquetStorage`` so serialization logic can be
    tested without any I/O.
    """

    @staticmethod
    def encode(dataframe: pd.DataFrame, *, index: bool = False) -> bytes:
        """Serialize a DataFrame to Parquet bytes.

        Args:
            dataframe: The DataFrame to encode.
            index: Whether to include the DataFrame index. Default ``False``.

        Returns:
            Parquet-encoded bytes.
        """
        buffer = io.BytesIO()
        dataframe.to_parquet(buffer, index=index)
        return buffer.getvalue()

    @staticmethod
    def decode(data: bytes) -> pd.DataFrame:
        """Deserialize Parquet bytes into a DataFrame.

        Args:
            data: Raw Parquet bytes.

        Returns:
            Decoded DataFrame.

        Raises:
            ParquetDecodeError: If the bytes are not valid Parquet.
        """
        try:
            return pd.read_parquet(io.BytesIO(data))
        except Exception as exc:
            raise ParquetDecodeError(cause=exc) from exc


class ParquetStorage:
    """Read and write pandas DataFrames as Parquet objects in object storage.

    Constructed with a registry and provider so it remains independent of
    any specific storage SDK.  Business logic injects this class and never
    touches ``Minio`` directly.

    Args:
        registry: The ``StorageProviderRegistry`` holding registered adapters.
        provider: Which storage provider to use (e.g. ``StorageProvider.MINIO``).
        bucket: Default bucket name for all operations.

    Example::

        parquet = ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket="stock-data",
        )

        df = await parquet.read_dataframe("eod/hose/hpg.parquet")
        await parquet.write_dataframe("eod/hose/hpg.parquet", merged_df)
    """

    CONTENT_TYPE = _PARQUET_CONTENT_TYPE

    def __init__(
        self,
        registry: StorageProviderRegistry,
        provider: StorageProvider,
        bucket: str,
    ) -> None:
        self._readable: ReadableStorage = registry.get_port(provider, ReadableStorage)
        self._writable: WritableStorage = registry.get_port(provider, WritableStorage)
        self._copyable: CopyableStorage = registry.get_port(provider, CopyableStorage)
        self._deletable: DeletableStorage = registry.get_port(
            provider,
            DeletableStorage,
        )
        self._bucket = bucket

    # ------------------------------------------------------------------
    # Read operations
    # ------------------------------------------------------------------

    async def read_dataframe(self, object_name: str) -> pd.DataFrame:
        """Download and deserialize a Parquet object.

        Args:
            object_name: Object key within the configured bucket.

        Returns:
            Decoded DataFrame.

        Raises:
            StorageObjectNotFoundError: Object does not exist.
            ParquetDecodeError: Bytes exist but are not valid Parquet.
            StorageReadError: Transport / server failure.
        """
        data = await self._readable.read_bytes(self._bucket, object_name)
        try:
            return await asyncio.to_thread(ParquetCodec.decode, data)
        except ParquetDecodeError as exc:
            # Re-raise with object_name context so callers see the full path.
            raise ParquetDecodeError(
                cause=exc.cause,
                object_name=f"{self._bucket}/{object_name}",
            ) from exc.cause

    async def read_optional_dataframe(
        self, object_name: str
    ) -> pd.DataFrame | None:
        """Download and deserialize a Parquet object, returning ``None`` if absent.

        Use this when the absence of an object is a normal condition (e.g.
        first-time ingest — no existing file to merge with).  Corrupt data
        still raises ``ParquetDecodeError`` so data integrity errors are
        never silently swallowed.

        Args:
            object_name: Object key within the configured bucket.

        Returns:
            Decoded DataFrame, or ``None`` if the object does not exist.

        Raises:
            ParquetDecodeError: Bytes exist but are not valid Parquet.
            StorageReadError: Transport / server failure (not a 404).
        """
        try:
            return await self.read_dataframe(object_name)
        except StorageObjectNotFoundError:
            logger.debug(
                "Object '%s/%s' not found — returning None",
                self._bucket,
                object_name,
            )
            return None

    # ------------------------------------------------------------------
    # Write operations
    # ------------------------------------------------------------------

    async def write_dataframe(
        self,
        object_name: str,
        dataframe: pd.DataFrame,
        *,
        index: bool = False,
    ) -> None:
        """Serialize a DataFrame and upload it as a Parquet object.

        Overwrites any existing object at ``object_name``.

        Args:
            object_name: Object key within the configured bucket.
            dataframe: DataFrame to serialize and upload.
            index: Whether to include the DataFrame index. Default ``False``.

        Raises:
            StorageWriteError: If the upload fails.
        """
        data = await asyncio.to_thread(ParquetCodec.encode, dataframe, index=index)

        await self._writable.write_bytes(
            bucket=self._bucket,
            object_name=object_name,
            data=data,
            content_type=_PARQUET_CONTENT_TYPE,
        )

        logger.debug(
            "Wrote DataFrame (%d rows, %d cols) to '%s/%s'",
            len(dataframe),
            len(dataframe.columns),
            self._bucket,
            object_name,
        )

    async def replace_dataframe(
        self,
        object_name: str,
        dataframe: pd.DataFrame,
        *,
        index: bool = False,
        temp_object_name: str | None = None,
        validate: Callable[[pd.DataFrame], None] | None = None,
    ) -> str:
        """Best-effort atomic replacement for a Parquet object.

        The candidate DataFrame is written to a temporary object, read back,
        optionally validated, copied over the final object, and then cleaned up.
        If any step before the final copy fails, the existing final object is
        preserved. Object stores do not provide database-style transactions, so
        this method is explicitly best-effort.

        Args:
            object_name: Final object key within the configured bucket.
            dataframe: DataFrame to serialize and replace the final object with.
            index: Whether to include the DataFrame index. Default ``False``.
            temp_object_name: Optional explicit temporary object key. Tests and
                orchestrators can pass this for deterministic cleanup.
            validate: Optional callback that receives the read-back DataFrame
                and raises on schema/content validation failure.

        Returns:
            The temporary object key used for staging.

        Raises:
            ParquetDecodeError: Temporary bytes are not valid Parquet.
            StorageWriteError: Temporary write or final copy fails.
            Any exception raised by ``validate``.
        """
        temp_name = temp_object_name or self._build_temp_object_name(object_name)

        await self.write_dataframe(temp_name, dataframe, index=index)
        try:
            staged = await self.read_dataframe(temp_name)
            if validate is not None:
                validate(staged)

            await self._copyable.copy_object(
                bucket=self._bucket,
                source_object_name=temp_name,
                target_object_name=object_name,
                content_type=_PARQUET_CONTENT_TYPE,
            )
        except Exception:
            await self._delete_temp_safely(temp_name)
            raise

        await self._delete_temp_safely(temp_name)

        logger.debug(
            "Replaced DataFrame (%d rows, %d cols) at '%s/%s' via temp '%s'",
            len(dataframe),
            len(dataframe.columns),
            self._bucket,
            object_name,
            temp_name,
        )
        return temp_name

    def _build_temp_object_name(self, object_name: str) -> str:
        path = PurePosixPath(object_name)
        parent = "" if str(path.parent) == "." else f"{path.parent}/"
        return f"{parent}.tmp/{path.name}.{uuid4().hex}.tmp"

    async def _delete_temp_safely(self, object_name: str) -> None:
        try:
            await self._deletable.delete(self._bucket, object_name)
        except StorageObjectNotFoundError:
            logger.debug(
                "Temporary object '%s/%s' already absent during cleanup",
                self._bucket,
                object_name,
            )
        except Exception:
            logger.warning(
                "Failed to clean up temporary object '%s/%s'",
                self._bucket,
                object_name,
                exc_info=True,
            )