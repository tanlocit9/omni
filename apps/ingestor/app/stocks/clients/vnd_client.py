import asyncio
from typing import Any

import httpx

from ..base import StockClient
from ..normalization import normalize_record_list_keys
from ..registry import StockClientRegistry


@StockClientRegistry.register("VND")
class VNDirectClient(StockClient):
    STOCK_PRICES_URL = "https://api-finfo.vndirect.com.vn/v4/stock_prices"
    STOCKS_URL = "https://api-finfo.vndirect.com.vn/v4/stocks"

    HEADERS = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/124.0.0.0 Safari/537.36"
        ),
        "Referer": "https://www.vndirect.com.vn/",
        "Origin": "https://www.vndirect.com.vn",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "en-US,en;q=0.9",
    }

    def __init__(self, timeout: int = 30):
        self.client = httpx.AsyncClient(
            headers=self.HEADERS,
            timeout=timeout,
        )

    async def fetch_stock(
        self,
        symbol: str,
        page: int = 1,
        size: int = 100,
    ) -> dict[str, Any]:
        params = {
            "q": f"code:{symbol}",
            "size": size,
            "page": page,
            "sort": "date",
        }

        response = await self.client.get(
            self.STOCK_PRICES_URL,
            params=params,
        )

        response.raise_for_status()

        return response.json()

    async def fetch_all_stock(
        self,
        symbol: str,
        size: int = 100,
    ) -> list[dict[str, Any]]:
        first_page = await self.fetch_stock(
            symbol=symbol,
            page=1,
            size=size,
        )

        total_pages = first_page.get("totalPages", 1)

        records = normalize_record_list_keys(first_page.get("data", []))

        if total_pages > 1:
            tasks = [
                self.fetch_stock(
                    symbol=symbol,
                    page=page,
                    size=size,
                )
                for page in range(2, total_pages + 1)
            ]

            results = await asyncio.gather(*tasks)

            for result in results:
                records.extend(normalize_record_list_keys(result.get("data", [])))

        return records

    async def fetch_recent_stock(
        self,
        symbol: str,
        size: int,
    ) -> list[dict[str, Any]]:
        response = await self.fetch_stock(
            symbol=symbol,
            page=1,
            size=size,
        )

        return normalize_record_list_keys(response.get("data", []))

    async def fetch_symbols_page(
        self,
        exchange: str | None = None,
        page: int = 1,
        size: int = 100,
    ) -> dict[str, Any]:
        query = "type:STOCK~status:LISTED"
        if exchange:
            query = f"{query}~floor:{exchange}"

        params = {
            "q": query,
            "size": size,
            "page": page,
        }

        response = await self.client.get(
            self.STOCKS_URL,
            params=params,
        )

        response.raise_for_status()

        return response.json()

    async def fetch_symbols(
        self,
        exchange: str | None = None,
        size: int = 100,
    ) -> list[dict[str, Any]]:
        first_page = await self.fetch_symbols_page(
            exchange=exchange,
            page=1,
            size=size,
        )

        total_pages = first_page.get("totalPages", 1)

        records = list(first_page.get("data", []))

        if total_pages > 1:
            tasks = [
                self.fetch_symbols_page(
                    exchange=exchange,
                    page=page,
                    size=size,
                )
                for page in range(2, total_pages + 1)
            ]

            results = await asyncio.gather(*tasks)

            for result in results:
                records.extend(result.get("data", []))

        return records

    async def close(self) -> None:
        await self.client.aclose()
