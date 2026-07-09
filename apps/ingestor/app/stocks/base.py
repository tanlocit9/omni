from abc import ABC, abstractmethod
from typing import Any


class StockClient(ABC):
    @abstractmethod
    async def fetch_stock(
        self, symbol: str, page: int = 1, size: int = 100
    ) -> dict[str, Any]:
        pass

    @abstractmethod
    async def fetch_all_stock(
        self, symbol: str, size: int = 100
    ) -> list[dict[str, Any]]:
        pass

    @abstractmethod
    async def fetch_recent_stock(self, symbol: str, size: int) -> list[dict[str, Any]]:
        pass

    @abstractmethod
    async def fetch_symbols(
        self, exchange: str | None = None, size: int = 100
    ) -> list[dict[str, Any]]:
        pass

    @abstractmethod
    async def close(self) -> None:
        pass
