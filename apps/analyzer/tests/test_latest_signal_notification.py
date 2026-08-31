from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from py_common.storage.exceptions import StorageObjectNotFoundError

from app.signals.latest_notification import (
    LatestSignalNotificationService,
    LatestSignalRepository,
)
from main import app


class Paths:
    def signal_history(self, strategy, timeframe, exchange, code=None):
        return f"signals/{strategy}/{timeframe}/{exchange}.parquet"


class Settings:
    stock_data_paths = Paths()
    sector_wave_symbol_exchanges = ["HOSE", "HNX", "UPCOM"]


class Storage:
    def __init__(self, frames=None, missing=None):
        self.frames = frames or {}
        self.missing = set(missing or [])

    async def read_dataframe(self, path):
        if path in self.missing:
            raise StorageObjectNotFoundError("stock-data", path)
        return self.frames[path]


class Publisher:
    def __init__(self):
        self.payloads = []

    async def publish_signal_notification(self, payload):
        self.payloads.append(payload)


def row(
    symbol="HOSE-ACB",
    signal_date="2026-08-29",
    generated_at="2026-08-29T10:00:00Z",
):
    return {
        "symbol_key": symbol,
        "signal": "BULLISH",
        "signal_price": 25000.0,
        "signal_date": signal_date,
        "reason_codes": ["MOMENTUM"],
        "score": 4,
        "strategy": "TREND_MOMENTUM_V1",
        "timeframe": "1d",
        "generated_at": generated_at,
    }


def path(exchange):
    return f"signals/TREND_MOMENTUM_V1/1d/{exchange}.parquet"


@pytest.mark.asyncio
async def test_symbol_found_and_emitted_payload_contract():
    storage = Storage({path("HOSE"): pd.DataFrame([row()])})
    publisher = Publisher()
    service = LatestSignalNotificationService(
        LatestSignalRepository(Settings(), storage), publisher
    )

    latest = await service.publish_latest("hose-acb")

    assert latest.symbol_key == "HOSE-ACB"
    payload = publisher.payloads[0]
    assert payload["type"] == "SIGNAL_CHANGED"
    assert payload["source"] == "ANALYZER"
    assert payload["previousSignal"] is None
    assert payload["newSignal"] == "BULLISH"
    assert payload["signalChanged"] is True
    assert payload["metadata"] == {"manual": True, "generatedAt": latest.generated_at}
    assert payload["executionId"] != payload["parentExecutionId"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("reasons", "expected"),
    [
        (np.array(["MOMENTUM", "BREAKOUT"]), ["MOMENTUM", "BREAKOUT"]),
        ("MOMENTUM", ["MOMENTUM"]),
        (None, []),
        (np.nan, []),
    ],
)
async def test_reason_codes_support_parquet_arrays_and_scalar_values(reasons, expected):
    signal_row = row()
    signal_row["reason_codes"] = reasons
    repository = LatestSignalRepository(
        Settings(), Storage({path("HOSE"): pd.DataFrame([signal_row])})
    )

    latest = await repository.find_latest("HOSE-ACB")

    assert latest is not None
    assert latest.reason_codes == expected


@pytest.mark.asyncio
async def test_symbol_missing_returns_none():
    repository = LatestSignalRepository(
        Settings(), Storage({path("HOSE"): pd.DataFrame([row()])})
    )
    assert await repository.find_latest("HOSE-MISSING") is None


@pytest.mark.asyncio
async def test_malformed_symbol_is_rejected():
    repository = LatestSignalRepository(Settings(), Storage())
    with pytest.raises(ValueError, match="<exchange>-<code>"):
        await repository.find_latest("ACB")


@pytest.mark.asyncio
async def test_global_selects_latest_across_exchanges_and_skips_absent_object():
    storage = Storage(
        {
            path("HOSE"): pd.DataFrame([row()]),
            path("HNX"): pd.DataFrame(
                [row("HNX-SHC", "2026-08-30", "2026-08-30T09:00:00Z")]
            ),
        },
        missing={path("UPCOM")},
    )
    latest = await LatestSignalRepository(Settings(), storage).find_latest()
    assert latest.symbol_key == "HNX-SHC"


def test_endpoint_returns_accepted_and_not_found_without_live_dependencies():
    found = SimpleNamespace(
        symbol_key="HOSE-ACB",
        signal="BULLISH",
        signal_date="2026-08-29T00:00:00+00:00",
        generated_at="2026-08-29T10:00:00+00:00",
    )

    class Service:
        async def publish_latest(self, symbol_key=None):
            return None if symbol_key == "HOSE-NONE" else found

    with TestClient(app) as client:
        app.state.latest_signal_notification_service = Service()
        accepted = client.post("/v1/signals/notifications/latest?symbolKey=HOSE-ACB")
        missing = client.post("/v1/signals/notifications/latest?symbolKey=HOSE-NONE")
    assert accepted.status_code == 202
    assert accepted.json()["accepted"] is True
    assert missing.status_code == 404


def test_endpoint_returns_503_when_publisher_unavailable():
    with TestClient(app) as client:
        app.state.__delattr__("latest_signal_notification_service")
        response = client.post("/v1/signals/notifications/latest")
    assert response.status_code == 503
