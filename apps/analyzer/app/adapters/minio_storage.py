import asyncio

from minio import Minio

from app.settings import Settings, settings


class MinioObjectStorage:
    """MinIO-backed read-only object storage adapter."""

    def __init__(self, config: Settings = settings) -> None:
        self._bucket = config.minio_bucket
        self._client = Minio(
            endpoint=config.minio_endpoint,
            access_key=config.minio_access_key,
            secret_key=config.minio_secret_key,
            secure=config.minio_secure,
        )

    async def read_bytes(self, object_name: str) -> bytes:
        response = await asyncio.to_thread(
            self._client.get_object,
            self._bucket,
            object_name,
        )
        try:
            return await asyncio.to_thread(response.read)
        finally:
            response.close()
            response.release_conn()