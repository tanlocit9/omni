"""Factory for creating MinIO client instances from settings.

Keeps SDK construction details out of business code.  Services call
``create_minio_client(settings.minio)`` once at startup and pass the
resulting client to ``MinioStorageAdapter``.
"""

from __future__ import annotations

from urllib.parse import urlparse

from minio import Minio

from py_common.config.models import MinioSettings


def _normalize_minio_endpoint(endpoint: str) -> tuple[str, bool | None]:
    """Return a MinIO-SDK compatible endpoint and inferred secure flag.

    The MinIO Python SDK expects ``host[:port]`` and rejects endpoints that
    include a URL scheme.  Shared Omni environment variables use URL-style
    values such as ``http://localhost:9000`` for compatibility with other S3
    clients, so strip the scheme before constructing the SDK client.
    """
    raw_endpoint = endpoint.strip()
    parsed = urlparse(raw_endpoint)

    if parsed.scheme in {"http", "https"}:
        return parsed.netloc, parsed.scheme == "https"

    return raw_endpoint, None


def create_minio_client(settings: MinioSettings) -> Minio:
    """Build a ``minio.Minio`` client from application settings.

    Args:
        settings: Validated ``MinioSettings`` instance (endpoint, keys, etc.).

    Returns:
        A configured synchronous ``Minio`` client ready for use in
        ``MinioStorageAdapter``.
    """
    endpoint, inferred_secure = _normalize_minio_endpoint(settings.endpoint)
    return Minio(
        endpoint=endpoint,
        access_key=settings.access_key,
        secret_key=settings.secret_key,
        secure=settings.secure if inferred_secure is None else inferred_secure,
    )