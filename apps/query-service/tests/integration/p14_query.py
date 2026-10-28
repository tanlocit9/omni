"""Query actual READY objects from the P14 disposable environment."""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from py_common.config.models import MinioSettings
from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.global_metadata import GlobalMetadataReader

from app.executor import DuckDBExecutor
from app.models import DatasetRef
from app.security import validate_read_only_sql
from app.settings import QueryServiceSettings
from app.storage import DatasetResolver


async def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    settings = QueryServiceSettings(
        minio=MinioSettings(
            endpoint=config["minio"],
            access_key=config["accessKey"],
            secret_key=config["secretKey"],
            bucket=config["bucket"],
            secure=False,
        ),
        query_storage_scheme="s3",
    )
    assert settings.minio.endpoint == config["minio"], "MinIO isolation mismatch"
    assert settings.minio.bucket == config["bucket"], "Bucket isolation mismatch"
    adapter = MinioStorageAdapter(create_minio_client(settings.minio))
    await adapter.validate()
    reader = GlobalMetadataReader(adapter, settings.minio.bucket)
    document = await reader.read()
    eod = document.resolve("eod", {"exchange": "hose", "code": "hpg"})
    indicators = document.resolve(
        "indicators",
        {"source": "ad_close", "timeframe": "1d", "exchange": "hose", "code": "hpg"},
    )
    assert eod is not None and indicators is not None
    assert indicators.inputs and indicators.inputs[0].dataVersion == eod.dataVersion
    refs = [
        DatasetRef(
            dataset="indicators",
            partition=indicators.values,
            dataVersion=indicators.dataVersion,
        )
    ]
    datasets = await DatasetResolver(reader, settings).resolve_many(refs)
    result = await DuckDBExecutor(settings).execute(
        "p14-integration",
        validate_read_only_sql(
            "SELECT count(*) AS row_count, min(eod_data_version) AS input_version "
            "FROM indicators",
            {"indicators"},
        ),
        datasets,
        {},
        10,
    )
    expected_rows = config.get("expectedRows", 180)
    assert result.rows[0]["row_count"] == expected_rows
    assert result.rows[0]["input_version"] == eod.dataVersion
    Path(config["result"]).write_text(
        json.dumps(
            {
                "rows": expected_rows,
                "eodDataVersion": eod.dataVersion,
                "indicatorDataVersion": indicators.dataVersion,
                "ready": True,
            }
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
