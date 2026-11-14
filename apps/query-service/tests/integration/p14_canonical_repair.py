"""Disposable drained-writer repair; never a retained-environment operator CLI."""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import pandas as pd
from py_common.config.models import MinioSettings
from py_common.storage.adapters import MinioStorageAdapter, create_minio_client
from py_common.storage.global_metadata import GLOBAL_METADATA_PATH
from py_common.storage.output_readiness import PostgresMetadataReader
from py_common.storage.parquet import ParquetCodec, ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry
from pydantic import SecretStr

from app.settings import QueryServiceSettings


async def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    control = Path(config["providerControlDir"])
    plan = json.loads((control / "canonical-repair-heads.json").read_text())
    assert plan["disposableRehearsal"] is True
    assert config["bucket"] == "p14-integration"
    settings = QueryServiceSettings(
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
    )
    adapter = MinioStorageAdapter(create_minio_client(settings.minio))
    await adapter.validate()
    reader = PostgresMetadataReader(
        settings.output_readiness_api_url,
        config["outputReadinessToken"],
        paths=settings.stock_data_paths,
    )
    parquet = ParquetStorage(
        StorageProviderRegistry([adapter]),
        StorageProvider.MINIO,
        settings.minio.bucket,
        settings.stock_data_paths,
    )
    discovery = await adapter.read_bytes(settings.minio.bucket, GLOBAL_METADATA_PATH)
    document = await reader.read()
    checked = []
    for dataset in document.datasets:
        for proof in dataset.partitions:
            frame = await parquet.read_bound_dataframe(
                dataset.name, proof, proof.dataVersion
            )
            checked.append((dataset.name, proof, frame))
    assert checked
    accepted = []
    for head in plan["heads"]:
        path = getattr(settings.stock_data_paths, head["dataset"])(**head["partition"])
        data = await adapter.read_versioned_bytes(
            settings.minio.bucket, path, head["storageVersionId"]
        )
        accepted.append((head, path, data))
    eod = next(item for item in accepted if item[0]["dataset"] == "eod")
    stale = ParquetCodec.decode(eod[2]).copy()
    stale["ad_close"] = -999
    await adapter.write_versioned_bytes(
        settings.minio.bucket,
        eod[1],
        ParquetCodec.encode(stale),
        "application/octet-stream",
    )
    assert await adapter.read_bytes(settings.minio.bucket, eod[1]) != eod[2]
    # PostgreSQL still resolves the accepted bytes while canonical latest is stale.
    for dataset, proof, expected in checked:
        actual = await parquet.read_bound_dataframe(dataset, proof, proof.dataVersion)
        pd.testing.assert_frame_equal(expected, actual, check_exact=True)
    for head, path, data in accepted:
        restored = await adapter.write_versioned_bytes(
            settings.minio.bucket, path, data, "application/octet-stream"
        )
        assert restored != head["storageVersionId"]
        assert await adapter.read_bytes(settings.minio.bucket, path) == data
        assert (
            await adapter.read_versioned_bytes(
                settings.minio.bucket, path, head["storageVersionId"]
            )
            == data
        )
    for dataset, proof, expected in checked:
        actual = await parquet.read_bound_dataframe(dataset, proof, proof.dataVersion)
        pd.testing.assert_frame_equal(expected, actual, check_exact=True)
    assert (
        await adapter.read_bytes(settings.minio.bucket, GLOBAL_METADATA_PATH)
        == discovery
    )
    (control / "canonical-repair-result.json").write_text(
        json.dumps(
            {
                "restoredPartitions": len(accepted),
                "validatedProofs": len(checked),
                "canonicalWasStale": True,
                "discoveryUnchanged": True,
            }
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
