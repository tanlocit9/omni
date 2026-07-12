"""Factory for creating MinIO client instances from settings.

Keeps SDK construction details out of business code.  Services call
``create_minio_client(settings.minio)`` once at startup and pass the
resulting client to ``MinioStorageAdapter``.
"""

from __future__ import annotations

from minio import Minio

from py_common.config.models import MinioSettings


def create_minio_client(settings: MinioSettings) -> Minio:
    """Build a ``minio.Minio`` client from application settings.

    Args:
        settings: Validated ``MinioSettings`` instance (endpoint, keys, etc.).

    Returns:
        A configured synchronous ``Minio`` client ready for use in
        ``MinioStorageAdapter``.
    """
    return Minio(
        endpoint=settings.endpoint,
        access_key=settings.access_key,
        secret_key=settings.secret_key,
        secure=settings.secure,
    )