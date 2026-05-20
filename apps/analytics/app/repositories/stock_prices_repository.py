from sqlalchemy import desc
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import StockPrices


class StockPricesRepository:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def get_one_stock_by_symbol(self, symbol: str, limit=1):
        stmt = select(StockPrices).where(StockPrices.code == symbol).order_by(desc(StockPrices.date)).limit(limit)
        result = await self.db.execute(stmt)

        stock_price = result.scalar_one_or_none()

        return stock_price
