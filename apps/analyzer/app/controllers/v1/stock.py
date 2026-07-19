from fastapi import APIRouter, Depends

from app.providers.stock_provider import get_stock_service
from app.services.stock_service import StockService

router = APIRouter(prefix="/stocks")


@router.get("/")
async def get_stock(
        symbol: str,
        service: StockService = Depends(get_stock_service),
):
    return await service.get_stock(symbol)


@router.post("/sync")
async def sync_stock(
        symbol: str,
        service: StockService = Depends(get_stock_service),
):
    return await service.sync_stock(symbol)
