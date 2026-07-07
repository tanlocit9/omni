import app.stocks.clients.vci_client  # noqa: F401
import app.stocks.clients.vnd_client  # noqa: F401
from app.stocks.base import StockClient
from app.stocks.registry import StockClientRegistry

_client_cache: dict[str, StockClient] = {}


def get_or_create_client(source: str) -> StockClient:
    if source not in _client_cache:
        _client_cache[source] = StockClientRegistry.create(source)
    return _client_cache[source]


async def close_cached_clients() -> None:
    for client in _client_cache.values():
        await client.close()