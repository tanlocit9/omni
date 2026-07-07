from .base import StockClient


class StockClientRegistry:
    _registry: dict[str, type[StockClient]] = {}

    @classmethod
    def register(cls, name: str):
        def decorator(client_cls: type[StockClient]):
            cls._registry[name] = client_cls
            return client_cls

        return decorator

    @classmethod
    def create(cls, name: str, **kwargs) -> StockClient:
        client_cls = cls._registry.get(name)
        if client_cls is None:
            available = list(cls._registry.keys())
            raise ValueError(f"Unknown client: '{name}'. Available: {available}")
        return client_cls(**kwargs)

    @classmethod
    def available(cls) -> list[str]:
        return list(cls._registry.keys())
