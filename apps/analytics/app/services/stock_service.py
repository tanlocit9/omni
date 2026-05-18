from datetime import date

from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.vndirect_client import VNDirectClient
from app.models import StockPrices


class StockService:
    def __init__(self, client: VNDirectClient, db: AsyncSession):
        self.client = client
        self.db = db

    async def get_stock(self, symbol: str):
        data = await self.client.fetch_stock(symbol)
        return {
            "symbol": symbol,
            "total": data.get("totalElements", 0),
            "data": data.get("data", []),
        }

    async def sync_stock(self, symbol: str) -> dict:
        records = await self.client.fetch_all_stock(symbol)

        if not records:
            return {"symbol": symbol, "inserted": 0}

        # Chuẩn bị list dict để insert hàng loạt
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
            "inserted": result.rowcount,  # Số dòng thực tế được thêm mới
        }
