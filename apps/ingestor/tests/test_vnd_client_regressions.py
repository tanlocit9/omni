from contextlib import asynccontextmanager

import httpx
import pytest

from app.stocks.clients.vnd_client import VNDirectClient


@asynccontextmanager
async def client_with_transport(handler):
    client = VNDirectClient()
    await client.close()
    client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        yield client
    finally:
        await client.close()


@pytest.mark.anyio
@pytest.mark.parametrize("pages", [1, 3])
async def test_stock_history_collects_and_normalizes_every_provider_page(pages):
    requested = []

    def handle(request):
        assert request.url.path == "/v4/stock_prices"
        assert request.url.params["q"] == "code:HPG"
        assert request.url.params["size"] == "2"
        assert request.url.params["sort"] == "date"
        page = int(request.url.params["page"])
        requested.append(page)
        return httpx.Response(
            200, json={"totalPages": pages, "data": [{"adClose": page}]}
        )

    async with client_with_transport(handle) as client:
        rows = await client.fetch_all_stock("HPG", size=2)
    assert rows == [{"ad_close": page} for page in range(1, pages + 1)]
    assert sorted(requested) == list(range(1, pages + 1))


@pytest.mark.anyio
async def test_stock_history_propagates_later_page_http_failure():
    def handle(request):
        if request.url.params["page"] == "1":
            return httpx.Response(200, json={"totalPages": 2, "data": [{"close": 10}]})
        return httpx.Response(503, text="provider unavailable")

    async with client_with_transport(handle) as client:
        with pytest.raises(httpx.HTTPStatusError) as failure:
            await client.fetch_all_stock("HPG")
    assert failure.value.response.status_code == 503


@pytest.mark.anyio
@pytest.mark.parametrize(
    "payload,expected", [({}, []), ({"data": [{"adClose": 10}]}, [{"ad_close": 10}])]
)
async def test_recent_stock_uses_requested_limit_and_normalizes_rows(payload, expected):
    def handle(request):
        assert request.url.params["page"] == "1"
        assert request.url.params["size"] == "17"
        return httpx.Response(200, json=payload)

    async with client_with_transport(handle) as client:
        assert await client.fetch_recent_stock("HPG", size=17) == expected


@pytest.mark.anyio
@pytest.mark.parametrize("exchange", [None, "HOSE"])
async def test_symbol_page_preserves_filters_without_adding_an_absent_exchange(
    exchange,
):
    def handle(request):
        assert request.url.path == "/v4/stocks"
        expected = "type:STOCK~status:LISTED"
        if exchange:
            expected += "~floor:HOSE"
        assert request.url.params["q"] == expected
        assert request.url.params["page"] == "3"
        return httpx.Response(200, json={"data": [{"code": "HPG"}]})

    async with client_with_transport(handle) as client:
        assert await client.fetch_symbols_page(exchange, page=3) == {
            "data": [{"code": "HPG"}]
        }
