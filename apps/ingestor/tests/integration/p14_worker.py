"""Disposable P14 fixture worker. Only the external market provider is replaced."""

from __future__ import annotations

import asyncio
import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import pandas as pd
from py_common.config.models import KafkaSettings, MinioSettings

from app.handlers import stock_prices, symbols
from app.messaging import consumer
from app.settings import Settings


class FixtureProvider:
    def __init__(self, control: Path | None = None):
        self.control = control

    async def fetch_symbols(self, exchange=None, size=100):
        return [
            {"code": "HPG", "floor": exchange, "status": "listed", "name": "Fixture"}
        ]

    async def fetch_recent_stock(self, symbol, size):
        rows = (
            181
            if self.control and (self.control / "second-generation").exists()
            else 180
        )
        return pd.DataFrame(
            {
                "date": pd.date_range("2026-01-01", periods=rows, freq="D"),
                "ad_open": range(1, rows + 1),
                "ad_high": range(2, rows + 2),
                "ad_low": range(rows),
                "ad_close": range(1, rows + 1),
                "nm_volume": range(100, rows + 100),
            }
        ).to_dict("records")

    async def close(self):
        pass


async def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    settings = Settings(
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
    control = (
        Path(config["overlapControlDir"]) if "overlapControlDir" in config else None
    )
    provider = FixtureProvider(control)
    consumer.get_or_create_client = lambda *args: provider
    stock_prices.get_or_create_client = lambda *args: provider
    symbols.get_or_create_client = lambda *args: provider
    await consumer.IngestorKafkaRoutingService(settings).run()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(main())
