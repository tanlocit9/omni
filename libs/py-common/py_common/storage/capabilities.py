"""Storage capability enumeration."""

from enum import StrEnum


class StorageCapability(StrEnum):
    """Capabilities that a storage adapter may support.

    Used by ``StorageProviderRegistry.get_capabilities()`` to inspect which
    operations an adapter supports at runtime, and by ``get_port()`` to verify
    the adapter implements the requested port protocol before returning it.

    Notes:
        ``SHARE`` (presigned URL generation) is intentionally omitted from the
        Python layer until there is a concrete use case.  Add it here and in
        the MinIO adapter when presigned URLs are needed.

    Examples:
        >>> StorageCapability.READ
        <StorageCapability.READ: 'read'>
        >>> StorageCapability.WRITE in {StorageCapability.READ, StorageCapability.WRITE}
        True
    """

    READ = "read"
    WRITE = "write"
    DELETE = "delete"
    LIST = "list"