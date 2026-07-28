from unittest.mock import patch

from py_common.config.models import MinioSettings
from py_common.storage.adapters.factory import create_minio_client


def test_create_minio_client_strips_http_scheme_from_endpoint():
    settings = MinioSettings(
        endpoint="http://localhost:9000",
        access_key="access",
        secret_key="secret",
        secure=True,
    )

    with patch("py_common.storage.adapters.factory.Minio") as minio_cls:
        create_minio_client(settings)

    minio_cls.assert_called_once_with(
        endpoint="localhost:9000",
        access_key="access",
        secret_key="secret",
        secure=False,
    )


def test_create_minio_client_strips_https_scheme_and_enables_secure():
    settings = MinioSettings(
        endpoint="https://minio.example.com",
        access_key="access",
        secret_key="secret",
        secure=False,
    )

    with patch("py_common.storage.adapters.factory.Minio") as minio_cls:
        create_minio_client(settings)

    minio_cls.assert_called_once_with(
        endpoint="minio.example.com",
        access_key="access",
        secret_key="secret",
        secure=True,
    )


def test_create_minio_client_preserves_host_port_endpoint_and_configured_secure():
    settings = MinioSettings(
        endpoint="localhost:9000",
        access_key="access",
        secret_key="secret",
        secure=True,
    )

    with patch("py_common.storage.adapters.factory.Minio") as minio_cls:
        create_minio_client(settings)

    minio_cls.assert_called_once_with(
        endpoint="localhost:9000",
        access_key="access",
        secret_key="secret",
        secure=True,
    )
