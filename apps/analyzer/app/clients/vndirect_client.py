import httpx


class VNDirectClient:
    BASE_URL = "https://api-finfo.vndirect.com.vn/v4/stock_prices"
    HEADERS = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer": "https://www.vndirect.com.vn/",
        "Origin": "https://www.vndirect.com.vn",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "en-US,en;q=0.9",
    }

    async def fetch_stock(self, symbol: str, page: int = 1, size: int = 10):
        params = {
            "q": f"code:{symbol}",
            "size": size,
            "page": page,
            "sort": "date",
        }
        async with httpx.AsyncClient(headers=self.HEADERS) as client:
            res = await client.get(self.BASE_URL, params=params)
            res.raise_for_status()
            return res.json()

    async def fetch_all_stock(self, symbol: str, size: int = 100) -> list[dict]:
        """Fetch all pages for a given symbol."""
        first = await self.fetch_stock(symbol, page=1, size=size)
        total_pages = first.get("totalPages", 1)
        records = list(first.get("data", []))

        for page in range(2, total_pages + 1):
            page_data = await self.fetch_stock(symbol, page=page, size=size)
            records.extend(page_data.get("data", []))

        return records

    async def fetch_recent_stock(self, symbol: str, size: int) -> list[dict]:
        """Fetch only the most recent `size` records (single page)."""
        response = await self.fetch_stock(symbol, page=1, size=size)
        return response.get("data", [])
