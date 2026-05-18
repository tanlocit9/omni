from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.vndirect_client import VNDirectClient
from app.core.database import get_db
from app.services.stock_service import StockService


def get_vndirect_client() -> VNDirectClient:
    return VNDirectClient()


def get_stock_service(
    client: VNDirectClient = Depends(get_vndirect_client),
    db: AsyncSession = Depends(get_db),
) -> StockService:
    return StockService(client, db)
