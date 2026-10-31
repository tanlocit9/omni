"""Create stale EOD only inside the disposable P14 Kafka/MinIO fixture."""

from __future__ import annotations

import asyncio
import json
import sys
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from py_common.config.models import MinioSettings
from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry


async def main():
    config_path = Path(sys.argv[1])
    config = json.loads(config_path.read_text())
    assert config["bucket"] == "p14-integration", "Disposable bucket required"
    assert config["minio"].startswith("127.0.0.1:"), "Disposable endpoint required"
    settings = MinioSettings(
        endpoint=config["minio"],
        access_key=config["accessKey"],
        secret_key=config["secretKey"],
        bucket=config["bucket"],
        secure=False,
    )
    adapter = MinioStorageAdapter(create_minio_client(settings))
    registry = StorageProviderRegistry([adapter])
    await registry.validate_all(fail_fast=True)
    storage = ParquetStorage(
        registry=registry,
        provider=StorageProvider.MINIO,
        bucket=config["bucket"],
    )
    path = "eod/hose/hpg.parquet"
    frame = await storage.read_dataframe(path)
    day = (
        date.fromisoformat(config["businessDate"])
        if "businessDate" in config
        else datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date()
    )
    old = frame.loc[pd.to_datetime(frame["date"]).dt.date < day].copy()
    assert len(old) >= 100, "Preserve enough history for row-count readiness"
    await storage.write_dataframe(path, old)
    (config_path.parent / "stale-eod.json").write_text(
        json.dumps(
            {
                "businessDate": day.isoformat(),
                "latestInputDay": pd.to_datetime(old["date"]).max().date().isoformat(),
                "rows": len(old),
            }
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
