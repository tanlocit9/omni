class StockService:
    async def get_stock(self, symbol: str) -> dict:
        return {
            "symbol": symbol.upper(),
            "message": (
                "Analyzer no longer reads stock prices directly from "
                "PostgreSQL."
            ),
        }

    async def sync_stock(self, symbol: str) -> dict:
        return {
            "symbol": symbol.upper(),
            "accepted": False,
            "message": (
                "Analyzer no longer writes stock prices directly to PostgreSQL. "
                "Use the platform scheduler API to trigger stock sync jobs."
            ),
        }