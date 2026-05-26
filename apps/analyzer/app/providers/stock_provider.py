from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.vndirect_client import VNDirectClient
from app.core.database import get_db
from app.repositories import StockPricesRepository
from app.services.stock_service import StockService


def get_vndirect_client() -> VNDirectClient:
    return VNDirectClient()


def get_stock_prices_repository(db: AsyncSession = Depends(get_db)) -> StockPricesRepository:
    return StockPricesRepository(db)


def get_stock_service(
        client: VNDirectClient = Depends(get_vndirect_client),
        db: AsyncSession = Depends(get_db),
        stock_prices_repository=Depends(get_stock_prices_repository)
) -> StockService:
    return StockService(client, db, stock_prices_repository)
