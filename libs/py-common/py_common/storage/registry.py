"""Storage provider registry.

``StorageProviderRegistry`` is the central dependency-injection point for
storage in Python services.  It mirrors the role of
``StorageProviderRegistry.java`` in the platform app but uses explicit
registration (no Spring-style DI) and async validation.

Typical service startup::

    registry = StorageProviderRegistry([minio_adapter])
    await registry.validate_all(fail_fast=True)

    readable = registry.get_port(StorageProvider.MINIO, ReadableStorage)
    writable = registry.get_port(StorageProvider.MINIO, WritableStorage)
"""

from __future__ import annotations

import asyncio
import logging
from typing import TypeVar

from py_common.storage.base import BaseStorageAdapter
from py_common.storage.capabilities import StorageCapability
from py_common.storage.exceptions import (
    DuplicateStorageProviderError,
    StorageCapabilityNotSupportedError,
    StorageProviderInactiveError,
    StorageProviderNotFoundError,
    StorageValidationError,
)
from py_common.storage.ports import (
    CopyableStorage,
    DeletableStorage,
    ListableStorage,
    ReadableStorage,
    WritableStorage,
)
from py_common.storage.providers import StorageProvider

logger = logging.getLogger(__name__)

# TypeVar bound to the union of all port protocols.
# Callers receive a correctly-typed object from ``get_port()``.
TStoragePort = TypeVar(
    "TStoragePort",
    ReadableStorage,
    WritableStorage,
    CopyableStorage,
    DeletableStorage,
    ListableStorage,
)

# Maps port protocols → the capability flag they represent.
_PORT_TO_CAPABILITY: dict[type, StorageCapability] = {
    ReadableStorage: StorageCapability.READ,
    WritableStorage: StorageCapability.WRITE,
    CopyableStorage: StorageCapability.COPY,
    DeletableStorage: StorageCapability.DELETE,
    ListableStorage: StorageCapability.LIST,
}


class StorageProviderRegistry:
    """Registry that maps providers to their adapters and exposes typed ports.

    Args:
        adapters: Optional list of adapters to register on construction.

    Raises:
        DuplicateStorageProviderError: If the same provider is registered twice.
    """

    def __init__(
        self,
        adapters: list[BaseStorageAdapter] | None = None,
    ) -> None:
        self._adapters: dict[StorageProvider, BaseStorageAdapter] = {}

        for adapter in adapters or []:
            self.register(adapter)

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def register(self, adapter: BaseStorageAdapter) -> None:
        """Add an adapter to the registry.

        Args:
            adapter: A concrete ``BaseStorageAdapter`` instance.

        Raises:
            DuplicateStorageProviderError: Provider already registered.
        """
        if adapter.provider in self._adapters:
            raise DuplicateStorageProviderError(adapter.provider)

        self._adapters[adapter.provider] = adapter
        logger.debug("Registered storage adapter for provider '%s'", adapter.provider)

    # ------------------------------------------------------------------
    # Validation
    # ------------------------------------------------------------------

    async def validate_all(self, *, fail_fast: bool = True) -> None:
        """Validate all registered adapters concurrently.

        Args:
            fail_fast: If ``True`` (default), re-raise the first validation
                error encountered.  If ``False``, log all failures and
                continue — the service can still start but some providers
                will be inactive.

        Raises:
            StorageValidationError: The first failure when ``fail_fast=True``.
        """
        if not self._adapters:
            logger.warning("StorageProviderRegistry has no registered adapters")
            return

        tasks = {
            provider: asyncio.create_task(adapter.validate())
            for provider, adapter in self._adapters.items()
        }

        first_error: StorageValidationError | None = None

        for provider, task in tasks.items():
            try:
                await task
                logger.info("Storage provider '%s' validated successfully", provider)
            except StorageValidationError as exc:
                logger.error(
                    "Storage provider '%s' failed validation: %s", provider, exc
                )
                if fail_fast and first_error is None:
                    first_error = exc

        if first_error is not None:
            raise first_error

    # ------------------------------------------------------------------
    # Adapter access
    # ------------------------------------------------------------------

    def get_adapter(self, provider: StorageProvider) -> BaseStorageAdapter:
        """Return the raw adapter for a provider.

        Prefer ``get_port()`` in application code; use this only when you
        need adapter-specific methods (e.g. ``ensure_bucket``).

        Args:
            provider: The provider to look up.

        Returns:
            The registered ``BaseStorageAdapter`` for that provider.

        Raises:
            StorageProviderNotFoundError: Provider not registered.
            StorageProviderInactiveError: Provider registered but not active.
        """
        adapter = self._require_active(provider)
        return adapter

    # ------------------------------------------------------------------
    # Port access
    # ------------------------------------------------------------------

    def get_port(
        self,
        provider: StorageProvider,
        port_type: type[TStoragePort],
    ) -> TStoragePort:
        """Return a typed port from the adapter for the given provider.

        Checks in order:
        1. Provider is registered.
        2. Adapter is active (``validate()`` succeeded).
        3. Adapter implements the requested port protocol.

        Args:
            provider: The target storage provider.
            port_type: One of ``ReadableStorage``, ``WritableStorage``,
                ``CopyableStorage``, ``DeletableStorage``, or
                ``ListableStorage``.

        Returns:
            The adapter cast to the requested port type.

        Raises:
            StorageProviderNotFoundError: Provider not registered.
            StorageProviderInactiveError: Provider registered but not active.
            StorageCapabilityNotSupportedError: Adapter does not implement
                the requested port.
        """
        adapter = self._require_active(provider)

        if not isinstance(adapter, port_type):
            raise StorageCapabilityNotSupportedError(
                provider=provider,
                port_type=port_type,
            )

        return adapter  # type: ignore[return-value]

    # ------------------------------------------------------------------
    # Capability inspection
    # ------------------------------------------------------------------

    def get_capabilities(self, provider: StorageProvider) -> set[StorageCapability]:
        """Return the set of capabilities supported by the adapter.

        Does NOT require the adapter to be active — useful for capability
        negotiation before validation has run.

        Args:
            provider: The provider to inspect.

        Returns:
            Set of ``StorageCapability`` values the adapter supports.

        Raises:
            StorageProviderNotFoundError: Provider not registered.
        """
        adapter = self._require_registered(provider)

        capabilities: set[StorageCapability] = set()
        for port_type, capability in _PORT_TO_CAPABILITY.items():
            if isinstance(adapter, port_type):
                capabilities.add(capability)

        return capabilities

    # ------------------------------------------------------------------
    # Introspection helpers
    # ------------------------------------------------------------------

    def registered_providers(self) -> list[StorageProvider]:
        """Return all currently registered provider keys."""
        return list(self._adapters.keys())

    def __repr__(self) -> str:
        providers = ", ".join(str(p) for p in self._adapters)
        return f"<StorageProviderRegistry providers=[{providers}]>"

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _require_registered(self, provider: StorageProvider) -> BaseStorageAdapter:
        """Return the adapter or raise ``StorageProviderNotFoundError``."""
        try:
            return self._adapters[provider]
        except KeyError:
            raise StorageProviderNotFoundError(provider) from None

    def _require_active(self, provider: StorageProvider) -> BaseStorageAdapter:
        """Return the adapter only if active, raising descriptive errors."""
        adapter = self._require_registered(provider)

        if not adapter.is_active:
            raise StorageProviderInactiveError(
                provider=provider,
                last_error=adapter.last_error,
            )

        return adapter
