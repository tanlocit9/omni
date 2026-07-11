import asyncio
import secrets
from typing import Any

import httpx

from ..base import StockClient
from ..registry import StockClientRegistry


@StockClientRegistry.register("VCI")
class VCIClient(StockClient):
    SEARCH_BAR_URL = (
        "https://iq.vietcap.com.vn/api/iq-insight-service/v2/company/search-bar"
    )

    def __init__(self, timeout: int = 30):
        self.headers = {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Safari/537.36"
            ),
            "Referer": "https://trading.vietcap.com.vn/",
            "Origin": "https://trading.vietcap.com.vn/",
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "en-US,en;q=0.9,vi-VN;q=0.8,vi;q=0.7",
            "Content-Type": "application/json",
            "Device-Id": secrets.token_hex(8),
        }
        self.client = httpx.AsyncClient(headers=self.headers, timeout=timeout)

    async def fetch_sectors(self, lang: str = "vi") -> list[dict[str, Any]]:
        if lang not in ("vi", "en"):
            raise ValueError("lang must be 'vi' or 'en'")

        lang_code = "1" if lang == "vi" else "2"

        response = await self.client.get(
            self.SEARCH_BAR_URL,
            params={"language": lang_code},
        )
        response.raise_for_status()
        json_data = response.json()

        if not json_data or "data" not in json_data or json_data["data"] is None:
            raise ValueError(
                "No data received from VCI. API structure may have changed."
            )

        parsed_data: list[dict[str, Any]] = []
        for company in json_data["data"]:
            symbol = company.get("code")
            organ_name = company.get("name")
            com_type_code = company.get("comTypeCode")
            for level in range(1, 5):
                icb_key = f"icbLv{level}"
                icb = company.get(icb_key)
                if icb:
                    parsed_data.append(
                        {
                            "symbol": symbol,
                            "organ_name": organ_name,
                            "com_type_code": com_type_code,
                            "icb_level": level,
                            "icb_code": icb.get("code"),
                            "icb_name": icb.get("name"),
                        }
                    )

        return [row for row in parsed_data if row.get("icb_code")]

    async def fetch_sectors_bilingual(self) -> dict[str, dict[str, Any]]:
        vi_rows, en_rows = await asyncio.gather(
            self.fetch_sectors(lang="vi"),
            self.fetch_sectors(lang="en"),
        )

        merged: dict[str, dict[str, Any]] = {}

        for row in vi_rows:
            symbol = row["symbol"]
            level = row["icb_level"]
            entry = merged.setdefault(
                symbol, {"symbol": symbol, "organ_name": row["organ_name"]}
            )
            entry[f"icb_lv{level}_code"] = row["icb_code"]
            entry[f"icb_lv{level}_name_vi"] = row["icb_name"]

        for row in en_rows:
            symbol = row["symbol"]
            level = row["icb_level"]
            entry = merged.setdefault(
                symbol, {"symbol": symbol, "organ_name": row["organ_name"]}
            )
            entry[f"icb_lv{level}_name_en"] = row["icb_name"]

        return merged

    async def fetch_symbols(
        self, symbol: str, *args: Any, **kwargs: Any
    ) -> pd.DataFrame:
        raise NotImplementedError("VCIClient does not support stock symbols fetching")

    async def fetch_stock(self, symbol: str, *args: Any, **kwargs: Any) -> pd.DataFrame:
        raise NotImplementedError("VCIClient does not support stock price fetching")

    async def fetch_recent_stock(
        self, symbol: str, *args: Any, **kwargs: Any
    ) -> pd.DataFrame:
        raise NotImplementedError("VCIClient does not support stock price fetching")

    async def fetch_all_stock(
        self, symbol: str, *args: Any, **kwargs: Any
    ) -> pd.DataFrame:
        raise NotImplementedError("VCIClient does not support stock price fetching")

    async def close(self) -> None:
        await self.client.aclose()
