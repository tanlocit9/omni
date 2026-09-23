"""Cursor-based vnstock Quote adapter for VCI intraday trades."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from datetime import date
from typing import Any

import pandas as pd

logger = logging.getLogger(__name__)
_MAX_SESSION_ROWS = 30_000


class VCIIntradayQuoteAdapter:
    """Fetch one complete VCI intraday session through the provider API.

    The public vnstock ``Quote.intraday`` facade injects page-based arguments
    that the VCI provider does not accept. This adapter deliberately invokes
    the provider's cursor-based API and requests its documented maximum rows.
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
        """Synchronous fetch for execution in a thread pool."""
        quote = self._quote_factory(symbol=symbol, source="VCI")
        provider = getattr(quote, "_provider", None)
        if provider is None or not callable(getattr(provider, "intraday", None)):
            raise RuntimeError("vnstock VCI provider intraday API is unavailable")

        logger.info(
            "Fetching VCI intraday session symbol=%s trading_date=%s row_limit=%d",
            symbol,
            trading_date.isoformat(),
            _MAX_SESSION_ROWS,
        )
        frame = provider.intraday(page_size=_MAX_SESSION_ROWS)
        result = frame if isinstance(frame, pd.DataFrame) else pd.DataFrame(frame)
        logger.info(
            "Fetched VCI intraday session symbol=%s trading_date=%s rows=%d",
            symbol,
            trading_date.isoformat(),
            len(result),
        )
        return result


def _vnstock_quote(**kwargs: Any) -> Any:
    from vnstock.api.quote import Quote

    return Quote(**kwargs)
