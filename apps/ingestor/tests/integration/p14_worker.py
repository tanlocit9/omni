"""Disposable P14 fixture worker. Only the external market provider is replaced."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import sys
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import pandas as pd
from py_common.config.models import KafkaSettings, MinioSettings
from py_common.messaging import atomic_work as atomic_contract

from app.handlers import stock_prices, symbols
from app.messaging import consumer
from app.settings import Settings


class FixtureProvider:
    def __init__(self, control: Path | None = None, business_date: date | None = None):
        self.control = control
        self.business_date = business_date

    async def fetch_symbols(self, exchange=None, size=100):
        row = {"code": "HPG", "floor": exchange, "status": "listed", "name": "Fixture"}
        if self.control and (self.control / "symbol-classification.json").exists():
            row.update(json.loads((self.control / "symbol-classification.json").read_text()))
        return [row]

    async def fetch_recent_stock(self, symbol, size):
        if self.control:
            with (self.control / "stock-fetches.jsonl").open("a", encoding="utf-8") as log:
                log.write(json.dumps({"symbol": symbol, "size": size}) + "\n")
        if self.control and (self.control / "fail-stock").exists():
            raise TimeoutError("Fixture provider ReadTimeout before any canonical write")
        today = self.business_date or datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date()
        revised = self.control and (self.control / "second-generation").exists()
        rows = 180
        frame = pd.DataFrame(
            {
                "date": pd.bdate_range(end=today, periods=rows),
                "ad_open": range(1, rows + 1),
                "ad_high": range(2, rows + 2),
                "ad_low": range(rows),
                "ad_close": range(1, rows + 1),
                "nm_volume": range(100, rows + 100),
            }
        )
        # A correction of the same atomic day must still change the input version.
        if revised:
            frame.loc[frame.index[-1], "ad_close"] = 999
        if self.control and (self.control / "stock-price-overrides.json").exists():
            overrides = json.loads((self.control / "stock-price-overrides.json").read_text())
            for day, price in overrides.items():
                frame.loc[frame["date"].dt.date.eq(date.fromisoformat(day)), "ad_close"] = price
        if self.control and (self.control / "omit-stock-date").exists():
            omitted = date.fromisoformat((self.control / "omit-stock-date").read_text().strip())
            frame = frame.loc[~frame["date"].dt.date.eq(omitted)]
        return frame.to_dict("records")

    async def close(self):
        pass


async def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    if "businessDate" in config:
        day = date.fromisoformat(config["businessDate"])

        class SnapshotClock(datetime):
            @classmethod
            def now(cls, tz=None):
                if (
                    Path(config.get("providerControlDir", config.get("overlapControlDir", ".")))
                    / "use-current-snapshot-clock"
                ).exists():
                    return datetime.now(tz)
                return datetime.now(tz).replace(
                    year=day.year, month=day.month, day=day.day
                )

        # A test clock dependency, not a bypass of require_current_snapshot validation.
        atomic_contract.datetime = SnapshotClock
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
        Path(config["providerControlDir"])
        if "providerControlDir" in config
        else Path(config["overlapControlDir"]) if "overlapControlDir" in config else None
    )
    provider = FixtureProvider(
        control,
        date.fromisoformat(config["businessDate"])
        if "businessDate" in config
        else None,
    )
    consumer.get_or_create_client = lambda *args: provider
    stock_prices.get_or_create_client = lambda *args: provider
    symbols.get_or_create_client = lambda *args: provider
    if "providerControlDir" in config:
        original = consumer.process_stock_price_message

        async def observe_stock(raw, publisher, client, storage):
            payload = raw if isinstance(raw, dict) else json.loads(raw)
            exchange, code = payload["symbolKey"].split("-", 1)
            day = date.fromisoformat(payload["businessDate"])

            async def snapshot():
                frame = await storage.read_optional_dataframe(settings.get_eod_path(exchange, code))
                if frame is None or frame.empty:
                    return {"dayRows": 0, "close": None, "otherRows": None}
                dates = pd.to_datetime(frame["date"]).dt.date
                selected = frame.loc[dates.eq(day)]
                other = frame.loc[~dates.eq(day)].sort_values("date").sort_index(axis=1)
                digest = hashlib.sha256(other.to_csv(index=False, float_format="%.12g").encode()).hexdigest()
                return {"dayRows": len(selected), "close": float(selected["ad_close"].iloc[0]) if len(selected) else None,
                        "otherRows": digest}

            before = await snapshot()
            result = await original(raw, publisher, client, storage)
            after = await snapshot()
            (control / f"stock-proof-{payload['executionId']}.json").write_text(json.dumps({
                "executionId": payload["executionId"], "status": result.status,
                "policy": payload.get("metadata", {}).get("backfillPolicy", {}),
                "before": before, "after": after,
            }), encoding="utf-8")
            return result

        consumer.process_stock_price_message = observe_stock
    service = consumer.IngestorKafkaRoutingService(settings)
    await service.run()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(main())
