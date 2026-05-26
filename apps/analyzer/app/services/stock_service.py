from datetime import date

from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.vndirect_client import VNDirectClient
from app.models import StockPrices
from app.repositories import StockPricesRepository


class StockService:
    def __init__(self, client: VNDirectClient, db: AsyncSession, stock_price_repository: StockPricesRepository):
        self.client = client
        self.db = db
        self.stock_price_repository = stock_price_repository

    async def get_stock(self, symbol: str):
        return await self.stock_price_repository.get_one_stock_by_symbol(symbol)

    async def sync_stock(self, symbol: str) -> dict:
        stock = await self.get_stock(symbol)

        records = []
        if stock is not None:
            missing_records = (date.today() - stock.date).days
            if missing_records == 0:
                return {"symbol": symbol, "inserted": 0}
            records = await self.client.fetch_recent_stock(symbol, missing_records)
            records = [r for r in records if date.fromisoformat(r["date"]) > stock.date]
        else:
            records = await self.client.fetch_all_stock(symbol)

        if not records:
            return {"symbol": symbol, "inserted": 0}

        data_to_insert = [
            {
                "code": symbol,
                "date": date.fromisoformat(r["date"]),
                "open": r.get("open"),
                "high": r.get("high"),
                "low": r.get("low"),
                "close": r.get("close"),
                "volume": r.get("nmVolume"),
                "change": r.get("change"),
                "pct_change": r.get("pctChange"),
            }
            for r in records
        ]

        stmt = insert(StockPrices).values(data_to_insert)

        stmt = stmt.on_conflict_do_nothing()

        result = await self.db.execute(stmt)
        await self.db.commit()

        return {
            "symbol": symbol,
            "fetched": len(records),
            "inserted": result.rowcount,
        }
