"""Analyzer service behavior tests."""

import pytest

from app.services.stock_service import StockService


@pytest.mark.anyio
async def test_get_stock_reports_database_access_removed():
    """Analyzer should not read stock prices from PostgreSQL."""
    result = await StockService().get_stock("hpg")

    assert result["symbol"] == "HPG"
    assert "no longer reads stock prices" in result["message"]


@pytest.mark.anyio
async def test_sync_stock_reports_database_write_removed():
    """Analyzer should not write stock prices to PostgreSQL."""
    result = await StockService().sync_stock("hpg")

    assert result["symbol"] == "HPG"
    assert result["accepted"] is False
    assert "platform scheduler API" in result["message"]
