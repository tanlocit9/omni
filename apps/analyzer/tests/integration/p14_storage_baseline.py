"""Fenced physical baseline over the actual private API and disposable MinIO."""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import pandas as pd
from py_common.config.models import MinioSettings
from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.output_readiness import PostgresMetadataReader
from py_common.storage.output_reconciliation import OutputStorageBaselineCoordinator
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry
from py_common.storage.work_rows import select_rows
from pydantic import SecretStr

from app.settings import AppSettings


async def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    settings = AppSettings(
        minio=MinioSettings(
            endpoint=config["minio"],
            access_key=config["accessKey"],
            secret_key=config["secretKey"],
            bucket=config["bucket"],
            secure=False,
        ),
        output_readiness_mode="postgres",
        output_readiness_api_url=config["outputReadinessUrl"],
        output_readiness_api_token=SecretStr(config["outputReadinessToken"]),
        output_readiness_reconcile_token=SecretStr(
            config["outputReadinessReconcileToken"]
        ),
    )
    assert (
        settings.minio.endpoint == config["minio"]
        and settings.minio.bucket == config["bucket"]
    )
    adapter = MinioStorageAdapter(create_minio_client(settings.minio))
    legacy_reads = 0
    original_read = adapter.read_bytes

    async def guarded_read(bucket, path):
        nonlocal legacy_reads
        if path == "_metadata/metadata.json":
            legacy_reads += 1
            raise AssertionError("PostgreSQL-only E2E attempted legacy JSON discovery")
        return await original_read(bucket, path)

    adapter.read_bytes = guarded_read
    registry = StorageProviderRegistry([adapter])
    await registry.validate_all(fail_fast=True)
    parquet = ParquetStorage(
        registry,
        StorageProvider.MINIO,
        settings.minio.bucket,
        settings.stock_data_paths,
    )
    reader = PostgresMetadataReader(
        settings.output_readiness_api_url,
        config["outputReadinessToken"],
        paths=settings.stock_data_paths,
    )
    coordinator = OutputStorageBaselineCoordinator(settings, reader, parquet)
    targets = [
        ("eod", {"exchange": "hose", "code": "hpg"}),
        (
            "indicators",
            {
                "exchange": "hose",
                "code": "hpg",
                "source": "ad_close",
                "timeframe": "1d",
            },
        ),
    ]
    before = await reader.read()
    frames = []
    for dataset, partition in targets:
        manifest = before.resolve(dataset, partition)
        assert manifest is not None and manifest.storageVersionId is None
        frames.append(
            await parquet.read_bound_dataframe(dataset, manifest, manifest.dataVersion)
        )
        await coordinator.pin(dataset, partition, allow_unproven_history=True)
    after = await reader.read()
    registered_rows = 0
    for (dataset, partition), expected in zip(targets, frames, strict=True):
        manifest = after.resolve(dataset, partition)
        assert manifest.storageVersionId is not None
        assert manifest.dataVersion == before.resolve(dataset, partition).dataVersion
        actual = await parquet.read_bound_dataframe(
            dataset, manifest, manifest.dataVersion
        )
        pd.testing.assert_frame_equal(expected, actual, check_exact=True)
        scopes = [proof["workScope"] for proof in manifest.workProofs] or [
            manifest.workScope
        ]
        keys = {
            tuple(sorted(key.items())) for scope in scopes for key in scope["rowKeys"]
        }
        scoped = select_rows(
            dataset, actual, {"rowKeys": [dict(key) for key in sorted(keys)]}
        )
        assert len(scoped) == manifest.rowCount
        registered_rows += len(scoped)
    from minio.error import S3Error

    try:
        await asyncio.to_thread(
            create_minio_client(settings.minio).stat_object,
            settings.minio.bucket,
            "_metadata/metadata.json",
        )
    except S3Error as error:
        assert error.code in {"NoSuchKey", "NoSuchObject"}
    else:
        raise AssertionError(
            "PostgreSQL-only fixture unexpectedly published legacy JSON"
        )
    assert legacy_reads == 0
    (Path(config["providerControlDir"]) / "physical-baseline-result.json").write_text(
        json.dumps(
            {
                "pinnedDatasets": 2,
                "logicalVersionsUnchanged": True,
                "bytesPreserved": True,
                "containerRowsPreserved": sum(len(frame) for frame in frames),
                "postgresValidatedRows": registered_rows,
                "postgresExpectedRows": sum(
                    before.resolve(dataset, partition).rowCount
                    for dataset, partition in targets
                ),
                "legacyJsonReads": legacy_reads,
                "legacyJsonAbsent": True,
            }
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
