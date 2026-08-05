"""Storage layer exceptions.

All exceptions raised by storage adapters and the registry are defined here.
MinIO-specific exceptions must not leak into business logic — adapters are
responsible for catching SDK errors and re-raising them as one of the types
below.

Hierarchy:
    StorageError
    ├── StorageValidationError
    ├── StorageProviderNotFoundError
    ├── StorageProviderInactiveError
    ├── StorageCapabilityNotSupportedError
    ├── DuplicateStorageProviderError
    ├── StorageObjectNotFoundError
    ├── StorageReadError
    └── StorageWriteError
    └── StorageDeleteError

    ParquetError (separate hierarchy — data format concern)
    ├── ParquetDecodeError
    └── ParquetEncodeError
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from py_common.storage.providers import StorageProvider


class StorageError(Exception):
    """Base class for all storage-layer errors."""


class StorageValidationError(StorageError):
    """Raised when an adapter fails its connectivity/health validation.

    Unlike the Java implementation that swallows validation failures silently,
    the Python layer raises this exception so services can fail fast at startup
    rather than discovering an unavailable backend at request time.

    Args:
        provider: The storage provider that failed validation.
        cause: The underlying exception from the SDK or network layer.
    """

    def __init__(
        self,
        provider: StorageProvider,
        cause: Exception,
    ) -> None:
        self.provider = provider
        self.cause = cause
        super().__init__(f"Storage provider '{provider}' failed validation: {cause}")


class StorageProviderNotFoundError(StorageError):
    """Raised when a provider is requested that has not been registered.

    Args:
        provider: The provider that was not found in the registry.
    """

    def __init__(self, provider: StorageProvider) -> None:
        self.provider = provider
        super().__init__(f"Storage provider '{provider}' is not registered")


class StorageProviderInactiveError(StorageError):
    """Raised when a registered provider is not in an active/validated state.

    Call ``registry.validate_all()`` during service startup to ensure all
    providers are active before accepting requests.

    Args:
        provider: The inactive provider.
        last_error: Optional last known error message from the adapter.
    """

    def __init__(
        self,
        provider: StorageProvider,
        last_error: str | None = None,
    ) -> None:
        self.provider = provider
        self.last_error = last_error
        detail = f" Last error: {last_error}" if last_error else ""
        super().__init__(
            f"Storage provider '{provider}' is registered but not active.{detail}"
        )


class StorageCapabilityNotSupportedError(StorageError):
    """Raised when a port is requested that the adapter does not implement.

    Args:
        provider: The provider whose adapter was checked.
        port_type: The protocol/port type that was not supported.
    """

    def __init__(self, provider: StorageProvider, port_type: type) -> None:
        self.provider = provider
        self.port_type = port_type
        super().__init__(
            f"Storage provider '{provider}' does not support port "
            f"'{port_type.__name__}'"
        )


class DuplicateStorageProviderError(StorageError):
    """Raised when an adapter for an already-registered provider is re-registered.

    Args:
        provider: The duplicate provider.
    """

    def __init__(self, provider: StorageProvider) -> None:
        self.provider = provider
        super().__init__(
            f"Storage provider '{provider}' is already registered. "
            "Each provider may only be registered once."
        )


class StorageObjectNotFoundError(StorageError):
    """Raised when a requested object does not exist in the storage backend.

    This is a distinct, recoverable condition — callers should handle it
    explicitly rather than treating it as a generic read failure.

    Use ``ParquetStorage.read_optional_dataframe()`` if absence is expected
    (e.g., first-run with no prior data).

    Args:
        bucket: The bucket that was queried.
        object_name: The object key that was not found.
    """

    def __init__(self, bucket: str, object_name: str) -> None:
        self.bucket = bucket
        self.object_name = object_name
        super().__init__(f"Object not found: s3://{bucket}/{object_name}")


class StorageReadError(StorageError):
    """Raised when reading an object fails for reasons other than absence.

    Examples: authentication failure, network timeout, server error.

    Args:
        bucket: The bucket being read from.
        object_name: The object key being read.
        cause: The underlying SDK or network exception.
    """

    def __init__(
        self,
        bucket: str,
        object_name: str,
        cause: Exception,
    ) -> None:
        self.bucket = bucket
        self.object_name = object_name
        self.cause = cause
        super().__init__(f"Failed to read s3://{bucket}/{object_name}: {cause}")


class StorageWriteError(StorageError):
    """Raised when writing an object fails.

    Args:
        bucket: The bucket being written to.
        object_name: The object key being written.
        cause: The underlying SDK or network exception.
    """

    def __init__(
        self,
        bucket: str,
        object_name: str,
        cause: Exception,
    ) -> None:
        self.bucket = bucket
        self.object_name = object_name
        self.cause = cause
        super().__init__(f"Failed to write s3://{bucket}/{object_name}: {cause}")


class StorageDeleteError(StorageError):
    """Raised when deleting an object fails.

    Args:
        bucket: The bucket containing the object.
        object_name: The object key being deleted.
        cause: The underlying SDK or network exception.
    """

    def __init__(
        self,
        bucket: str,
        object_name: str,
        cause: Exception,
    ) -> None:
        self.bucket = bucket
        self.object_name = object_name
        self.cause = cause
        super().__init__(f"Failed to delete s3://{bucket}/{object_name}: {cause}")


# ---------------------------------------------------------------------------
# Parquet-specific exceptions (data-format concern, not storage transport)
# ---------------------------------------------------------------------------


class ParquetError(Exception):
    """Base class for Parquet serialisation errors."""


class ParquetDecodeError(ParquetError):
    """Raised when bytes cannot be deserialised as a valid Parquet file.

    ``object_name`` is optional so ``ParquetCodec.decode`` (which has no
    path context) can raise without it.  ``ParquetStorage.read_dataframe``
    catches and re-raises with the object name to add path context.

    Args:
        cause: The underlying pyarrow or pandas exception.
        object_name: The S3 object key that contained the corrupt data,
            if known at the raise site.
    """

    def __init__(
        self,
        cause: Exception,
        object_name: str | None = None,
    ) -> None:
        self.cause = cause
        self.object_name = object_name
        location = f" from '{object_name}'" if object_name else ""
        super().__init__(f"Cannot decode Parquet data{location}: {cause}")


class ParquetEncodeError(ParquetError):
    """Raised when a DataFrame cannot be serialised to Parquet bytes.

    Args:
        cause: The underlying pyarrow or pandas exception.
    """

    def __init__(self, cause: Exception) -> None:
        self.cause = cause
        super().__init__(f"Cannot encode DataFrame to Parquet: {cause}")
