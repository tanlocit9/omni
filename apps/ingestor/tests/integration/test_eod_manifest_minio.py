from __future__ import annotations

import hashlib
import json
import os
from uuid import uuid4

import pandas as pd
import pytest
from minio import Minio
from py_common.messaging import JobStatus
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.manifest import (
    ManifestReader,
    ManifestWriter,
    calculate_data_version,
    calculate_schema_hash,
    extract_schema_from_dataframe,
    publish_dataset_manifest,
    ready_manifest_path,
    versioned_manifest_path,
)
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.handlers import stock_prices
from app.handlers.stock_prices import process_stock_price_message

pytestmark = pytest.mark.anyio


class _StockClient:
    async def fetch_recent_stock(
        self,
        symbol: str,
        size: int,
    ) -> list[dict[str, object]]:
        assert symbol == "hpg"
        assert size > 0
        return [
            {"date": "2024-01-02", "close": 27.5, "totalVolume": 1_000},
            {"date": "2024-01-03", "close": 28.0, "totalVolume": 1_200},
        ]


class _StatusPublisher:
    def __init__(self) -> None:
        self.statuses = []

    async def publish(self, status, key=None) -> None:
        self.statuses.append((status, key))


class _FailReadyWriteAdapter(MinioStorageAdapter):
    def __init__(self, client: Minio, ready_path: str) -> None:
        super().__init__(client)
        self._ready_path = ready_path

    async def write_bytes(
        self,
        bucket: str,
        object_name: str,
        data: bytes,
        content_type: str,
    ) -> None:
        if object_name == self._ready_path:
            raise RuntimeError("injected READY replacement failure")
        await super().write_bytes(bucket, object_name, data, content_type)


def _client() -> Minio:
    endpoint = os.getenv("MINIO_ENDPOINT", "http://localhost:9000")
    endpoint = endpoint.removeprefix("http://").removeprefix("https://")
    return Minio(
        endpoint,
        access_key=os.getenv("MINIO_ACCESS_KEY", "minioadmin"),
        secret_key=os.getenv("MINIO_SECRET_KEY", "minioadmin"),
        secure=os.getenv("MINIO_SECURE", "false").lower() == "true",
    )


async def _active_registry(adapter: MinioStorageAdapter) -> StorageProviderRegistry:
    registry = StorageProviderRegistry([adapter])
    await registry.validate_all()
    return registry


async def test_eod_ready_publication_persists_exact_identity_and_preserves_prior_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = _client()
    bucket = f"omni-eod-manifest-it-{uuid4().hex}"
    object_name = "eod/hose/hpg.parquet"
    partition = {"exchange": "hose", "code": "hpg"}
    ready_path = ready_manifest_path("eod", partition)

    adapter = MinioStorageAdapter(client)
    registry = await _active_registry(adapter)
    await adapter.ensure_bucket(bucket)

    try:
        parquet_storage = ParquetStorage(registry, StorageProvider.MINIO, bucket)
        writer = ManifestWriter(registry, StorageProvider.MINIO, bucket)
        reader = ManifestReader(registry, StorageProvider.MINIO, bucket)
        status_publisher = _StatusPublisher()
        monkeypatch.setattr(
            stock_prices,
            "settings",
            type("Settings", (), {"get_eod_path": lambda *_args: object_name})(),
        )

        status = await process_stock_price_message(
            {
                "jobDefinitionId": "job-minio-integration",
                "executionId": "execution-minio-integration",
                "symbolKey": "hose-hpg",
            },
            status_publisher,
            _StockClient(),
            parquet_storage,
            writer,
        )

        assert status.status == JobStatus.SUCCESS
        parquet_bytes = await adapter.read_bytes(bucket, object_name)
        manifest = await reader.read_manifest("eod", partition)
        ready_bytes = await adapter.read_bytes(bucket, ready_path)
        immutable_path = versioned_manifest_path(
            manifest.dataset,
            manifest.partition,
            manifest.dataVersion,
        )
        immutable_bytes = await adapter.read_bytes(bucket, immutable_path)
        payload = json.loads(ready_bytes)

        assert immutable_bytes == ready_bytes
        assert manifest.status == "READY"
        assert manifest.path == object_name
        assert manifest.rowCount == 2
        assert manifest.columnCount == 3
        assert manifest.objectCount == 1
        assert manifest.totalBytes == len(parquet_bytes)
        assert payload["totalBytes"] == len(parquet_bytes)
        assert payload["dataVersion"] == manifest.dataVersion
        persisted_dataframe = await parquet_storage.read_dataframe(object_name)
        expected_dataframe = pd.DataFrame(
            [
                {"date": "2024-01-02", "close": 27.5, "total_volume": 1_000},
                {"date": "2024-01-03", "close": 28.0, "total_volume": 1_200},
            ]
        )
        pd.testing.assert_frame_equal(
            persisted_dataframe,
            expected_dataframe,
            check_dtype=False,
        )
        persisted_checksum = f"sha256:{hashlib.sha256(parquet_bytes).hexdigest()}"
        assert manifest.dataVersion == calculate_data_version(
            dataset="eod",
            partition=partition,
            schema_hash=calculate_schema_hash(
                extract_schema_from_dataframe(expected_dataframe)
            ),
            object_checksums=[(object_name, persisted_checksum)],
            inputs=[],
        )

        failing_adapter = _FailReadyWriteAdapter(client, ready_path)
        failing_registry = await _active_registry(failing_adapter)
        failing_writer = ManifestWriter(
            failing_registry,
            StorageProvider.MINIO,
            bucket,
        )
        changed = pd.DataFrame(
            [{"date": "2024-01-04", "close": 29.0, "total_volume": 1_400}]
        )
        changed_result = await parquet_storage.write_dataframe(object_name, changed)

        with pytest.raises(RuntimeError, match="injected READY replacement failure"):
            await publish_dataset_manifest(
                writer=failing_writer,
                dataset="eod",
                partition=partition,
                data_path=object_name,
                dataframe=changed,
                object_checksums=[
                    (changed_result.object_name, changed_result.checksum),
                ],
                object_count=1,
                total_bytes=changed_result.total_bytes,
            )

        assert await adapter.read_bytes(bucket, ready_path) == ready_bytes
    finally:
        if client.bucket_exists(bucket):
            for item in client.list_objects(bucket, recursive=True):
                client.remove_object(bucket, item.object_name)
            client.remove_bucket(bucket)
