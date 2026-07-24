from datetime import UTC, datetime
from typing import Any

_sector_cache: dict[str, Any] | None = None
_sector_cache_time: datetime | None = None
_SECTOR_CACHE_TTL_SECONDS = 300


async def get_cached_sectors(vci_client: Any) -> dict[str, dict[str, Any]]:
    global _sector_cache, _sector_cache_time

    now = datetime.now(UTC)
    if (
        _sector_cache is not None
        and _sector_cache_time is not None
        and (now - _sector_cache_time).total_seconds() < _SECTOR_CACHE_TTL_SECONDS
    ):
        return _sector_cache

    _sector_cache = await vci_client.fetch_sectors_bilingual()
    _sector_cache_time = now
    return _sector_cache
