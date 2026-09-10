"""Cursor-based vnstock Quote adapter for VCI intraday trades."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from datetime import date
from typing import Any

import pandas as pd


class VCIIntradayQuoteAdapter:
    """Fetch a complete session using VCI ``last_time``/``truncTime`` cursors."""

    def __init__(
        self,
        quote_factory: Callable[..., Any] | None = None,
        *,
        page_size: int = 1000,
    ) -> None:
        self._quote_factory = quote_factory or _vnstock_quote
        self._page_size = page_size

    async def fetch_session(self, symbol: str, trading_date: date) -> pd.DataFrame:
        pages: list[pd.DataFrame] = []
        cursor: str | None = None
        observed: set[str] = set()
        while True:
            page = await asyncio.to_thread(
                self._fetch_page, symbol, trading_date, cursor
            )
            if page.empty:
                break
            pages.append(page)
            if len(page) < self._page_size:
                break
            next_cursor = _cursor_from(page)
            if not next_cursor or next_cursor == cursor or next_cursor in observed:
                raise ValueError("VCI intraday cursor did not advance")
            observed.add(next_cursor)
            cursor = next_cursor
        if not pages:
            return pd.DataFrame()
        return pd.concat(pages, ignore_index=True)

    def _fetch_page(
        self, symbol: str, trading_date: date, cursor: str | None
    ) -> pd.DataFrame:
        quote = self._quote_factory(symbol=symbol, source="VCI")
        kwargs: dict[str, Any] = {
            "start": trading_date.isoformat(),
            "end": trading_date.isoformat(),
            "page_size": self._page_size,
        }
        if cursor is not None:
            kwargs["last_time"] = cursor
        frame = quote.intraday(**kwargs)
        return frame if isinstance(frame, pd.DataFrame) else pd.DataFrame(frame)


def _cursor_from(page: pd.DataFrame) -> str | None:
    for column in ("truncTime", "time"):
        if column in page.columns and not page[column].empty:
            return str(page[column].iloc[-1])
    return None


def _vnstock_quote(**kwargs: Any) -> Any:
    from vnstock.api.quote import Quote

    return Quote(**kwargs)
