"""All pages must reach durable Parquet before any symbol/sector batch is emitted."""

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pandas as pd
import pytest
from py_common.messaging import JobStatus
from py_common.storage.parquet import ParquetCodec

from app.handlers import symbols
from app.stocks.clients.vnd_client import VNDirectClient


class ParquetMemory:
    def __init__(self, frame, events):
        self.data = None if frame is None else ParquetCodec.encode(frame, index=False)
        self.events = events

    async def read_optional_dataframe(self, path):
        return None if self.data is None else ParquetCodec.decode(self.data)

    async def write_dataframe(self, path, frame):
        self.events.append("persist")
        self.data = ParquetCodec.encode(frame, index=False)

    async def read_dataframe(self, path):
        self.events.append("read-back")
        return ParquetCodec.decode(self.data)


@pytest.mark.anyio
@pytest.mark.parametrize("fail_page", [None, 2])
@pytest.mark.parametrize("previous_exists", [False, True])
async def test_all_provider_pages_are_upserted_then_reloaded_before_publication(
    monkeypatch, fail_page, previous_exists
):
    events = []

    def respond(request):
        page = int(request.url.params["page"])
        events.append(f"page-{page}")
        if page == fail_page:
            return httpx.Response(503)
        code = {1: "MBB", 2: "BVB", 3: "TCB"}[page]
        return httpx.Response(
            200,
            json={
                "totalPages": 3,
                "currentPage": page,
                "data": [
                    {
                        "code": code,
                        "floor": "HOSE",
                        "status": "LISTED",
                        "organName": "Updated " + code,
                        "providerField": f"page-{page}-value",
                    }
                ],
            },
        )

    previous = pd.DataFrame(
        [
            {
                "code": "HDB",
                "exchange": "HOSE",
                "organName": "Retained HDB",
                "oldAttribute": "keep",
            },
            {
                "code": "MBB",
                "exchange": "HOSE",
                "organName": "Old MBB",
                "oldAttribute": "keep too",
            },
        ]
    )
    if not previous_exists:
        previous = None
    storage = ParquetMemory(previous, events)
    producer, publisher = AsyncMock(), AsyncMock()

    async def publish(topic, **kwargs):
        events.append("publish")
        return SimpleNamespace(topic=topic, partition=0, offset=1)

    producer.send_and_wait.side_effect = publish
    client = VNDirectClient()
    await client.client.aclose()
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
        client.client = http
        result = await symbols.process_sync_symbols_message(
            {
                "jobDefinitionId": "job",
                "executionId": "execution",
                "exchange": "HOSE",
                "workType": "EXCHANGE",
                "workKey": "HOSE",
                "metadata": {"includeSectorClassification": False},
            },
            producer,
            publisher,
            client,
            storage,
        )
    if fail_page:
        assert result.status == JobStatus.ERROR
        assert events == ["page-1", "page-2"]
        if previous_exists:
            pd.testing.assert_frame_equal(ParquetCodec.decode(storage.data), previous)
        else:
            assert storage.data is None
        producer.send_and_wait.assert_not_awaited()
        return
    assert result.status == JobStatus.SUCCESS
    assert result.meta_json["fetchedSymbols"] == 3
    expected_codes = {"MBB", "BVB", "TCB"} | ({"HDB"} if previous_exists else set())
    assert result.meta_json["persistedSymbols"] == len(expected_codes)
    assert events == ["page-1", "page-2", "page-3", "persist", "read-back", "publish"]
    stored = ParquetCodec.decode(storage.data).set_index("code")
    assert set(stored.index) == expected_codes
    assert stored.loc["MBB", "organName"] == "Updated MBB"
    if previous_exists:
        assert stored.loc["MBB", "oldAttribute"] == "keep too"
    batch = json.loads(producer.send_and_wait.await_args.kwargs["value"])
    assert batch["actualCount"] == len(expected_codes)
    assert {r["code"] for r in batch["symbols"]} == set(stored.index)
    by_code = {r["code"]: r for r in batch["symbols"]}
    if previous_exists:
        assert by_code["HDB"]["meta"]["oldAttribute"] == "keep"
    assert by_code["TCB"]["meta"]["providerField"] == "page-3-value"


@pytest.mark.anyio
@pytest.mark.parametrize(
    "second",
    [
        {"totalPages": 3, "data": [{"code": "BVB"}]},
        {"totalPages": 2, "currentPage": 1, "data": [{"code": "BVB"}]},
        {"totalPages": 2, "data": []},
        {"totalPages": 2, "data": {}},
    ],
)
async def test_incomplete_or_unstable_provider_pages_are_rejected(second):
    client = VNDirectClient()
    client.fetch_symbols_page = AsyncMock(
        side_effect=[{"totalPages": 2, "data": [{"code": "MBB"}]}, second]
    )
    try:
        with pytest.raises(ValueError):
            await client.fetch_symbols(exchange="HOSE")
    finally:
        await client.close()


def test_upsert_preserves_missing_fields_and_rejects_cross_exchange_data():
    previous = pd.DataFrame(
        [{"code": "MBB", "floor": "HOSE", "companyName": "Known name"}]
    )
    incoming = pd.DataFrame(
        [{"code": "mbb", "exchange": "hose", "companyName": None, "extra": 7}]
    )
    merged = symbols.upsert_symbol_snapshot(previous, incoming, "HOSE")
    assert merged.loc[0, "companyName"] == "Known name"
    assert merged.loc[0, "extra"] == 7
    for field, value in [("exchange", "HNX"), ("code", ""), ("code", None)]:
        invalid = incoming.copy()
        invalid[field] = value
        with pytest.raises(ValueError):
            symbols.upsert_symbol_snapshot(previous, invalid, "HOSE")


@pytest.mark.anyio
@pytest.mark.parametrize("total_pages", [True, -1, "2", 10001])
async def test_invalid_page_bounds_fail_before_later_requests(total_pages):
    client = VNDirectClient()
    client.fetch_symbols_page = AsyncMock(
        return_value={"totalPages": total_pages, "data": [{"code": "MBB"}]}
    )
    try:
        with pytest.raises(ValueError, match="totalPages"):
            await client.fetch_symbols(exchange="HOSE")
        client.fetch_symbols_page.assert_awaited_once()
    finally:
        await client.close()


def test_missing_provider_identity_or_empty_result_cannot_replace_parquet():
    previous = pd.DataFrame([{"code": "MBB", "exchange": "HOSE"}])
    for incoming in [pd.DataFrame(), pd.DataFrame([{"companyName": "Missing key"}])]:
        with pytest.raises(ValueError):
            symbols.upsert_symbol_snapshot(previous, incoming, "HOSE")
