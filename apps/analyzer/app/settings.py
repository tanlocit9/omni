from pathlib import Path
from typing import Any

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

_WORKSPACE_ROOT = Path(__file__).resolve().parents[3]
_SHARED_TOPICS_YAML = _WORKSPACE_ROOT / "configs/shared/topics.yaml"
_SHARED_S3_PATHS_YAML = _WORKSPACE_ROOT / "configs/shared/s3-paths.yaml"


def _parse_scalar(value: str) -> str:
    return value.strip().strip("'\"")


def _load_shared_yaml(path: Path) -> dict[str, Any]:
    """Load the simple shared YAML config without adding a YAML runtime dependency."""

    if not path.exists():
        return {}

    root: dict[str, Any] = {}
    stack: list[tuple[int, dict[str, Any]]] = [(-1, root)]

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue

        indent = len(raw_line) - len(raw_line.lstrip(" "))
        key, separator, raw_value = raw_line.strip().partition(":")
        if not separator:
            continue

        while stack and indent <= stack[-1][0]:
            stack.pop()

        parent = stack[-1][1]
        if raw_value.strip():
            parent[key] = _parse_scalar(raw_value)
            continue

        nested: dict[str, Any] = {}
        parent[key] = nested
        stack.append((indent, nested))

    return root


_shared = _load_shared_yaml(_SHARED_TOPICS_YAML)
_s3_config = _load_shared_yaml(_SHARED_S3_PATHS_YAML)

_spring = _shared.get("spring", {})
_kafka = _spring.get("kafka", {})
_topics = _kafka.get("topics", {})
_minio = _shared.get("min-io", {})

_stock_data = _s3_config.get("stock-data", {})
_s3_paths = _stock_data.get("paths", {})


class Settings(BaseSettings):
    """Analyzer runtime settings.

    Analyzer must not connect directly to PostgreSQL. Runtime data access should go
    through object storage, and write/sync workflows should be delegated through the
    platform/ingestor event contracts.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    kafka_bootstrap: str = Field(
        default=_kafka.get("bootstrap-servers", "localhost:9092"),
    )
    topic_sync_stock_prices: str = Field(
        default=_topics.get("topic-sync-stock-prices", "topic-sync-stock-prices"),
    )
    topic_sync_symbols: str = Field(
        default=_topics.get("topic-sync-symbols", "topic-sync-symbols"),
    )
    topic_upsert_symbols: str = Field(
        default=_topics.get("topic-upsert-symbols", "topic-upsert-symbols"),
    )
    topic_sync_job_status: str = Field(
        default=_topics.get("topic-sync-job-status", "topic-sync-job-status"),
    )

    minio_endpoint: str = Field(default=_minio.get("endpoint", "localhost:9000"))
    minio_access_key: str = Field(default=_minio.get("access-key", "minioadmin"))
    minio_secret_key: str = Field(default=_minio.get("secret-key", "minioadmin"))
    minio_bucket: str = Field(
        default=_minio.get("bucket") or _stock_data.get("bucket", "stock-data")
    )
    minio_secure: bool = Field(default=False)

    def get_symbols_path(self, exchange: str) -> str:
        symbols_cfg = _s3_paths.get("symbols", {})
        base = symbols_cfg.get("base", "symbols/")
        pattern = symbols_cfg.get("pattern", "{exchange}.parquet")
        return base + pattern.format(exchange=exchange.lower())

    def get_eod_path(self, exchange: str, code: str) -> str:
        eod_cfg = _s3_paths.get("eod", {})
        base = eod_cfg.get("base", "eod/")
        pattern = eod_cfg.get("pattern", "{exchange}/{code}.parquet")
        return base + pattern.format(exchange=exchange.lower(), code=code.lower())


settings = Settings()