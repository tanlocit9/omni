from ..base import StockClient
from ..registry import StockClientRegistry


@StockClientRegistry.register("mock")
class MockStockClient(StockClient):
    def __init__(self, fixture: list[dict] | None = None):
        self._fixture = fixture or []

    async def fetch_stock(self, symbol, page=1, size=100):
        return {"data": self._fixture, "totalPages": 1}

    async def fetch_all_stock(self, symbol, size=100):
        return self._fixture

    async def fetch_recent_stock(self, symbol, size):
        return self._fixture[:size]

    async def close(self):
        pass
