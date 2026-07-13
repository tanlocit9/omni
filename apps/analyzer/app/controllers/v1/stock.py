from typing import Annotated

from fastapi import APIRouter, Depends, Request, status
from fastapi.responses import JSONResponse
from py_common.config.paths import StockDataPaths
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.dtos.stock.sync_stock_dto import SyncStockRequestDto
from app.services.stock_service import StockService


router = APIRouter()


def get_parquet_storage(request: Request) -> ParquetStorage:
    """Returns a ParquetStorage instance."""
    registry: StorageProviderRegistry = request.app.state.storage_registry
    settings = request.app.state.settings  # Assuming settings are also in app.state
    return ParquetStorage(
        registry=registry,
        provider=StorageProvider.MINIO,  # Or from settings.storage.provider
        bucket=settings.minio.bucket,
    )


def get_stock_data_paths(request: Request) -> StockDataPaths:
    """Returns stock data paths."""
    return request.app.state.settings.stock_data_paths


@router.get("/stocks/")
async def get_stock_prices(
    symbol: str,
    parquet_storage: Annotated[ParquetStorage, Depends(get_parquet_storage)],
    paths: Annotated[StockDataPaths, Depends(get_stock_data_paths)],
) -> JSONResponse:
    """
    Retrieve historical stock prices for a given symbol.
    """
    try:
        df = await parquet_storage.read_dataframe(
            paths.eod(exchange="hose", code=symbol)
        )
        return JSONResponse(content=df.to_dict(orient="records"))
    except Exception as e:
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"message": f"Failed to retrieve stock prices: {e}"},
        )


@router.post("/stocks/sync")
async def sync_stock_prices(
    request_dto: SyncStockRequestDto,
    stock_service: Annotated[StockService, Depends(StockService)],
) -> dict:
    """
    Trigger an on-demand historical price retrieval from VNDirect API.
    """
    return await stock_service.sync_stock(request_dto.symbol)