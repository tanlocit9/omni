from app.services.stock_service import StockService


def get_stock_service() -> StockService:
    return StockService()
