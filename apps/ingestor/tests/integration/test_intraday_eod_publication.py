from __future__ import annotations

import json
from datetime import date
from typing import Any

import pandas as pd
import pytest
from py_common.messaging import JobStatus
from py_common.storage.immutable_publication import ImmutableDatasetPublisher

from app.handlers.intraday_eod import process_intraday_eod_message


class MemoryStorage:
    def __init__(self) -> None:
        self.objects: dict[tuple[str, str], bytes] = {}
        self.writes: list[str] = []
        self.fail_suffix: str | None = None

    async def write_bytes(
        self, bucket: str, object_name: str, data: bytes, content_type: str
    ) -> None:
        del content_type
        self.writes.append(object_name)
        if self.fail_suffix is not None and object_name.endswith(self.fail_suffix):
            raise RuntimeError("injected publication failure")
        self.objects[(bucket, object_name)] = data

    async def read_bytes(self, bucket: str, object_name: str) -> bytes:
        return self.objects[(bucket, object_name)]


class StubAdapter:
    async def fetch_session(self, symbol: str, trading_date: date) -> pd.DataFrame:
        assert symbol == "HPG"
        assert trading_date == date(2026, 9, 9)
        return pd.DataFrame(
            [
                {
                    "time": "2026-09-09T09:15:00+07:00",
                    "price": 10,
                    "volume": 100,
                    "match_type": "LO",
                    "id": "trade-1",
                },
                {
                    "time": "2026-09-09T09:16:00+07:00",
                    "price": 10,
                    "volume": 200,
                    "match_type": "LO",
                    "id": "trade-2",
                },
            ]
        )


class StubParquetStorage:
    async def read_optional_dataframe(self, object_name: str) -> pd.DataFrame:
        assert object_name
        return pd.DataFrame(
            [
                {
                    "date": "2026-09-09",
                    "ad_close": 10,
                    "total_volume": 300,
                    "nm_value": 3000,
                }
            ]
        )


class RecordingStatusPublisher:
    def __init__(self) -> None:
        self.published: list[tuple[Any, str | None]] = []

    async def publish(self, status: Any, key: str | None = None) -> None:
        self.published.append((status, key))


def job_payload() -> dict[str, object]:
    return {
        "jobDefinitionId": "job-1",
        "executionId": "exec-1",
        "parentExecutionId": "parent-1",
        "source": "VCI",
        "workType": "SYMBOL",
        "workKey": "HOSE-HPG",
        "symbolKey": "HOSE-HPG",
        "exchange": "HOSE",
        "tradingDate": "2026-09-09",
        "provider": "VCI",
    }


@pytest.mark.anyio
async def test_handler_publishes_validated_manifest_then_ready_pointer() -> None:
    storage = MemoryStorage()
    status_publisher = RecordingStatusPublisher()

    status = await process_intraday_eod_message(
        job_payload(),
        status_publisher,
        ImmutableDatasetPublisher(storage, storage, "stock-data"),
        StubParquetStorage(),
        StubAdapter(),
    )

    assert status.status == JobStatus.SUCCESS
    assert status.meta_json["recordsInserted"] == 2
    assert status.new_offset.startswith("sha256:")
    assert status_publisher.published == [(status, "HOSE-HPG")]
    assert storage.writes[-1].endswith("/READY.json")
    assert storage.writes[-2].endswith("/manifest.json")
    manifest = json.loads(storage.objects[("stock-data", storage.writes[-2])])
    assert manifest["rowCount"] == 2
    assert manifest["reconciliation"]["status"] == "READY"
    ready = json.loads(storage.objects[("stock-data", storage.writes[-1])])
    assert ready["dataVersion"] == status.new_offset
    assert ready["manifestPath"] == storage.writes[-2]


@pytest.mark.anyio
async def test_manifest_failure_preserves_previous_ready_and_publishes_error() -> None:
    storage = MemoryStorage()
    status_publisher = RecordingStatusPublisher()
    prefix = (
        "intraday/trades/provider=vci/exchange=hose/trading_date=2026-09-09/symbol=hpg"
    )
    ready_object = f"{prefix}/READY.json"
    storage.objects[("stock-data", ready_object)] = b"previous-ready"
    storage.fail_suffix = "/manifest.json"

    status = await process_intraday_eod_message(
        job_payload(),
        status_publisher,
        ImmutableDatasetPublisher(storage, storage, "stock-data"),
        StubParquetStorage(),
        StubAdapter(),
    )

    assert status.status == JobStatus.ERROR
    assert "injected publication failure" in status.error_message
    assert status_publisher.published == [(status, "HOSE-HPG")]
    assert storage.objects[("stock-data", ready_object)] == b"previous-ready"
    assert ready_object not in storage.writes
