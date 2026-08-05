"""Storage provider enumeration."""

from enum import StrEnum


class StorageProvider(StrEnum):
    """Supported storage provider identifiers.

    Mirrors ``StorageProvider.java`` in the platform module.
    Only ``MINIO`` has an adapter implementation in the current phase;
    ``AWS_S3`` is declared for parity with the Java enum and to allow
    typed configuration without a concrete adapter.

    Examples:
        >>> StorageProvider.MINIO
        <StorageProvider.MINIO: 'minio'>
        >>> str(StorageProvider.MINIO)
        'minio'
    """

    MINIO = "minio"
    AWS_S3 = "aws_s3"
