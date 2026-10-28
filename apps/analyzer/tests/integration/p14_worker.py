"""Actual Analyzer metadata/indicator workers over disposable Kafka and MinIO."""

from __future__ import annotations

import asyncio
import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from py_common.config.models import KafkaSettings, MinioSettings
from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.global_metadata import GlobalMetadataReader, GlobalMetadataWriter
from py_common.storage.metadata_sync import MetadataSynchronizer
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.indicators.handler import IndicatorJobHandler
from app.indicators.kafka import IndicatorKafkaService
from app.indicators.messages import IndicatorJobMessage
from app.metadata.handler import MetadataSyncJobHandler
from app.metadata.kafka import MetadataSyncKafkaService
from app.settings import AppSettings


async def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    settings = AppSettings(
        kafka=KafkaSettings(bootstrap_servers=config["kafka"]),
        minio=MinioSettings(
            endpoint=config["minio"],
            access_key=config["accessKey"],
            secret_key=config["secretKey"],
            bucket=config["bucket"],
            secure=False,
        ),
    )
    assert settings.kafka.bootstrap_servers == config["kafka"], (
        "Kafka isolation mismatch"
    )
    assert settings.minio.endpoint == config["minio"], "MinIO isolation mismatch"
    assert settings.minio.bucket == config["bucket"], "Bucket isolation mismatch"
    adapter = MinioStorageAdapter(create_minio_client(settings.minio))
    await adapter.ensure_bucket(settings.minio.bucket)
    registry = StorageProviderRegistry([adapter])
    await registry.validate_all(fail_fast=True)
    reader = GlobalMetadataReader(adapter, settings.minio.bucket)
    synchronizer = MetadataSynchronizer(
        readable=adapter,
        listable=adapter,
        reader=reader,
        writer=GlobalMetadataWriter(adapter, adapter, settings.minio.bucket),
        registry=OMNI_DATASET_REGISTRY,
        bucket=settings.minio.bucket,
    )
    metadata = MetadataSyncKafkaService(settings, MetadataSyncJobHandler(synchronizer))
    parquet = ParquetStorage(
        registry=registry, provider=StorageProvider.MINIO, bucket=settings.minio.bucket
    )
    handler = IndicatorJobHandler(
        settings,
        parquet,
        reader,
    )
    control = (
        Path(config["overlapControlDir"]) if "overlapControlDir" in config else None
    )
    if control:
        actual_handle = handler.handle

        async def gated_handle(payload):
            execution = payload["executionId"]
            (control / f"entered-{execution}.json").write_text(
                json.dumps({"executionId": execution})
            )
            while not (control / "release-indicators").exists():
                await asyncio.sleep(0.05)
            count = await actual_handle(payload)
            message = IndicatorJobMessage.model_validate(payload)
            exchange, code = message.parse_symbol_key()
            path = handler._build_indicators_path(
                message.indicator_source,
                message.timeframe,
                exchange.lower(),
                code.lower(),
            )
            frame = await parquet.read_dataframe(path)
            write_marker(
                control / f"output-{execution}.json",
                {
                    "rows": len(frame),
                    "inputVersions": sorted(
                        frame["eod_data_version"].dropna().unique().tolist()
                    ),
                },
            )
            return count

        handler.handle = gated_handle
    indicators = IndicatorKafkaService(settings, handler)
    if control:
        actual_publish = indicators.publish_status

        async def observed_publish(status):
            await actual_publish(status)
            marker = control / f"ack-{status.execution_id}.json"
            attempts = (
                json.loads(marker.read_text())["attempts"] if marker.exists() else 0
            )
            write_marker(marker, {"attempts": attempts + 1, "status": status.status})

        indicators.publish_status = observed_publish
    await metadata.start()
    await indicators.start()
    try:
        # A failed consumer must fail the fixture process, not silently look alive.
        await asyncio.gather(metadata._task, indicators._task)
    finally:
        await asyncio.gather(metadata.stop(), indicators.stop(), return_exceptions=True)


def write_marker(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value))
    temporary.replace(path)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(main())
