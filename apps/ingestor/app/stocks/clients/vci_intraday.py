"""Cursor-based vnstock Quote adapter for VCI intraday trades."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from datetime import date
from typing import Any

import pandas as pd


class VCIIntradayQuoteAdapter:
    """
    Fetch a complete session from VCI intraday data.

    Note: vnstock 4.x removed pagination support. This adapter fetches
    the complete trading day in a single call, which is acceptable since
    a single day's trades are manageable in memory.
    """

    def __init__(
        self,
        quote_factory: Callable[..., Any] | None = None,
    ) -> None:
        self._quote_factory = quote_factory or _vnstock_quote

    async def fetch_session(self, symbol: str, trading_date: date) -> pd.DataFrame:
        """
        Fetch complete intraday trading session for a symbol on a specific date.

        Returns all trades for the trading day without pagination.
        """
        frame = await asyncio.to_thread(self._fetch_session_sync, symbol, trading_date)
        return frame

    def _fetch_session_sync(self, symbol: str, trading_date: date) -> pd.DataFrame:
        """Synchronous fetch for execution in thread pool."""
        quote = self._quote_factory(symbol=symbol, source="VCI")
        kwargs: dict[str, Any] = {
            "start": trading_date.isoformat(),
            "end": trading_date.isoformat(),
        }
        frame = quote.intraday(**kwargs)
        return frame if isinstance(frame, pd.DataFrame) else pd.DataFrame(frame)


def _vnstock_quote(**kwargs: Any) -> Any:
    from vnstock.api.quote import Quote

    return Quote(**kwargs)
