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
from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.global_metadata import GlobalMetadataReader, GlobalMetadataWriter
from py_common.storage.metadata_sync import MetadataSynchronizer
from py_common.storage.output_readiness import (
    PostgresMetadataReader,
    ShadowMetadataReader,
)
from py_common.storage.output_reconciliation import OutputStorageBaselineCoordinator
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry
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
    for (dataset, partition), expected in zip(targets, frames, strict=True):
        manifest = after.resolve(dataset, partition)
        assert manifest.storageVersionId is not None
        assert manifest.dataVersion == before.resolve(dataset, partition).dataVersion
        actual = await parquet.read_bound_dataframe(
            dataset, manifest, manifest.dataVersion
        )
        pd.testing.assert_frame_equal(expected, actual, check_exact=True)
    # Seed legacy discovery with its canonical SYNC_METADATA implementation.
    # This is fixture preparation, not a manual production trigger or a new writer.
    legacy_reader = GlobalMetadataReader(adapter, settings.minio.bucket)
    synchronizer = MetadataSynchronizer(
        readable=adapter,
        listable=adapter,
        reader=legacy_reader,
        writer=GlobalMetadataWriter(adapter, adapter, settings.minio.bucket),
        registry=OMNI_DATASET_REGISTRY,
        bucket=settings.minio.bucket,
    )
    await synchronizer.sync()
    shadow = ShadowMetadataReader(legacy_reader, reader, parquet=parquet)
    legacy = await shadow.read()
    assert shadow.last_comparison["matches"] is True, shadow.last_comparison
    assert shadow.last_comparison["scope"] == "WORK_ROWS_V1"
    assert shadow.last_comparison["comparedRows"] == 2
    assert (
        legacy.resolve("eod", targets[0][1]).rowCount
        > after.resolve("eod", targets[0][1]).rowCount
    )
    (Path(config["providerControlDir"]) / "physical-baseline-result.json").write_text(
        json.dumps(
            {
                "pinnedDatasets": 2,
                "logicalVersionsUnchanged": True,
                "bytesPreserved": True,
                "shadow": shadow.last_comparison,
            }
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
