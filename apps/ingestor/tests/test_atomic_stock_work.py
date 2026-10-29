from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
from zoneinfo import ZoneInfo

import pandas as pd
import pytest
from py_common.messaging.atomic_work import atomic_work_id

from app.handlers import stock_prices


@pytest.mark.asyncio
@pytest.mark.parametrize("has_day", [True, False])
@pytest.mark.parametrize("history", ["existing", "missing", "empty"])
@pytest.mark.parametrize("source", ["VND", ""])
async def test_atomic_stock_proves_requested_day_before_write_and_status(
    monkeypatch, has_day, history, source
):
    day = datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date() - timedelta(days=7)
    while day.weekday() >= 5:
        day -= timedelta(days=1)
    parameters = {"source": source}
    raw = {
        "jobDefinitionId": "job",
        "executionId": "execution",
        "workType": "SYMBOL",
        "workKey": "HOSE-HPG",
        "symbolKey": "HOSE-HPG",
        "source": source,
        "jobType": "SYNC_STOCK_PRICE",
        "businessDate": day.isoformat(),
        "parameters": parameters,
        "atomicWorkId": atomic_work_id(
            "SYNC_STOCK_PRICE", "SYMBOL", "HOSE-HPG", day, parameters
        ),
    }
    events = []
    client = AsyncMock()
    client.fetch_recent_stock.return_value = [
        {"date": (day - timedelta(days=1)).isoformat(), "close": 15},
        {"date": (day + timedelta(days=1)).isoformat(), "close": 999},
        *([{"date": day.isoformat(), "close": 20}] if has_day else []),
    ]
    storage = AsyncMock()
    existing = pd.DataFrame({"date": [day - timedelta(days=1), day], "close": [10, 2]})
    storage.read_optional_dataframe.return_value = {
        "existing": existing,
        "missing": None,
        "empty": pd.DataFrame(),
    }[history]

    async def write(*args, **kwargs):
        events.append("write")

    async def publish(status, **kwargs):
        events.append("status")

    storage.write_dataframe.side_effect = write
    publisher = SimpleNamespace(publish=publish)
    monkeypatch.setattr(stock_prices, "get_or_create_client", lambda *args: client)
    monkeypatch.setattr(
        stock_prices,
        "settings",
        SimpleNamespace(get_eod_path=lambda *args: "logical-stock"),
    )
    status = await stock_prices.process_stock_price_message(
        raw, publisher, client, storage
    )
    assert status.status == ("SUCCESS" if has_day else "ERROR")
    if has_day:
        assert events == ["write", "status"]
        written = storage.write_dataframe.await_args.args[1]
        assert written["close"].tolist() == [10 if history == "existing" else 15, 20]
        assert status.new_offset == day.isoformat()
        assert status.meta_json["atomicWorkId"] == raw["atomicWorkId"]
    else:
        assert events == ["status"]
        storage.write_dataframe.assert_not_awaited()
    expected_limit = (datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date() - day).days + 30
    if history != "existing":
        expected_limit = stock_prices._ALL_RECORDS_LIMIT
    client.fetch_recent_stock.assert_awaited_once_with(
        symbol="HPG", size=expected_limit
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure", ["future", "too_old", "empty", "missing_date", "write"]
)
async def test_atomic_stock_failures_never_report_success(monkeypatch, failure):
    day = datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date() - timedelta(days=7)
    if failure == "future":
        day += timedelta(days=21)
    elif failure == "too_old":
        day -= timedelta(days=10_001)
    while day.weekday() >= 5:
        day -= timedelta(days=1)
    parameters = {"source": "VND"}
    raw = {
        "jobDefinitionId": "job",
        "executionId": "execution",
        "source": "VND",
        "workType": "SYMBOL",
        "workKey": "HOSE-HPG",
        "symbolKey": "HOSE-HPG",
        "jobType": "SYNC_STOCK_PRICE",
        "businessDate": day.isoformat(),
        "parameters": parameters,
        "atomicWorkId": atomic_work_id(
            "SYNC_STOCK_PRICE", "SYMBOL", "HOSE-HPG", day, parameters
        ),
    }
    client, storage, publisher = AsyncMock(), AsyncMock(), AsyncMock()
    records = [{"date": day.isoformat(), "close": 1}]
    if failure == "empty":
        records = []
    elif failure == "missing_date":
        records = [{"close": 1}]
    client.fetch_recent_stock.return_value = records
    storage.read_optional_dataframe.return_value = None
    if failure == "write":
        storage.write_dataframe.side_effect = OSError("write failed")
    monkeypatch.setattr(stock_prices, "get_or_create_client", lambda *args: client)
    monkeypatch.setattr(
        stock_prices, "settings",
        SimpleNamespace(get_eod_path=lambda *args: "logical-stock"),
    )
    status = await stock_prices.process_stock_price_message(
        raw, publisher, client, storage
    )
    assert status.status == "ERROR"
    publisher.publish.assert_awaited_once()
    if failure != "write":
        storage.write_dataframe.assert_not_awaited()
    if failure in {"future", "too_old"}:
        client.fetch_recent_stock.assert_not_awaited()


@pytest.mark.asyncio
async def test_legacy_empty_stock_output_retains_compatibility(monkeypatch):
    client, storage, publisher = AsyncMock(), AsyncMock(), AsyncMock()
    client.fetch_recent_stock.return_value = []
    storage.read_optional_dataframe.return_value = None
    monkeypatch.setattr(
        stock_prices, "settings",
        SimpleNamespace(get_eod_path=lambda *args: "logical-stock"),
    )
    raw = {
        "jobDefinitionId": "job", "executionId": "execution", "source": "",
        "workType": "SYMBOL", "workKey": "HOSE-HPG", "symbolKey": "HOSE-HPG",
    }
    status = await stock_prices.process_stock_price_message(
        raw, publisher, client, storage
    )
    assert status.status == "SUCCESS"
    assert storage.write_dataframe.await_args.args[1].empty
    assert stock_prices.compute_limit(day := datetime.now().date(), day) == 1
