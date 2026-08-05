"""Abstract base class for storage adapters.

``BaseStorageAdapter`` provides the lifecycle management (``validate`` /
``is_active`` / ``last_error``) that the registry depends on.  Concrete
adapters subclass this and also implement one or more port protocols from
``py_common.storage.ports``.

The class is intentionally lean — it does NOT inherit from any port protocol.
Capability detection is performed by the registry via ``isinstance`` checks
against the ``runtime_checkable`` protocols.
"""

from abc import ABC, abstractmethod

from py_common.storage.exceptions import StorageValidationError
from py_common.storage.providers import StorageProvider


class BaseStorageAdapter(ABC):
    """Lifecycle-managed base for storage adapters.

    Subclass this and implement:
    - ``provider`` property — return the ``StorageProvider`` enum value.
    - ``_do_validate()`` — perform a real connectivity check (e.g. list buckets).
    - Port protocol methods (``read_bytes``, ``write_bytes``, etc.) as needed.

    Example::

        class MinioStorageAdapter(
            BaseStorageAdapter,
            ReadableStorage,
            WritableStorage,
        ):
            @property
            def provider(self) -> StorageProvider:
                return StorageProvider.MINIO

            async def _do_validate(self) -> None:
                await asyncio.to_thread(self._client.list_buckets)
    """

    def __init__(self) -> None:
        self._is_active: bool = False
        self._last_error: str | None = None

    # ------------------------------------------------------------------
    # Abstract interface — subclasses must implement
    # ------------------------------------------------------------------

    @property
    @abstractmethod
    def provider(self) -> StorageProvider:
        """The storage provider this adapter represents."""
        ...

    @abstractmethod
    async def _do_validate(self) -> None:
        """Perform the actual connectivity / credential check.

        Raise any exception on failure; ``validate()`` will catch it and
        convert it to ``StorageValidationError``.
        """
        ...

    # ------------------------------------------------------------------
    # Lifecycle — used by the registry
    # ------------------------------------------------------------------

    @property
    def is_active(self) -> bool:
        """``True`` only after a successful ``validate()`` call."""
        return self._is_active

    @property
    def last_error(self) -> str | None:
        """Message from the most recent validation failure, or ``None``."""
        return self._last_error

    async def validate(self) -> None:
        """Run ``_do_validate()`` and update ``is_active`` accordingly.

        On success sets ``is_active = True`` and clears ``last_error``.
        On failure sets ``is_active = False``, stores the error message, and
        raises ``StorageValidationError`` — the service startup layer can
        decide whether to abort or continue depending on ``fail_fast``.

        Raises:
            StorageValidationError: Wraps the underlying exception with
                provider context.
        """
        try:
            await self._do_validate()
            self._is_active = True
            self._last_error = None
        except Exception as exc:
            self._is_active = False
            self._last_error = str(exc)
            raise StorageValidationError(
                provider=self.provider,
                cause=exc,
            ) from exc

    def __repr__(self) -> str:
        status = "active" if self._is_active else "inactive"
        return f"<{type(self).__name__} provider={self.provider!r} status={status}>"
