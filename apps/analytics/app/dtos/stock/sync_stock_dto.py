from typing import Any

from pydantic import BaseModel


class StockResponse(BaseModel):
    symbol: str
    total: int
    data: list[Any]
