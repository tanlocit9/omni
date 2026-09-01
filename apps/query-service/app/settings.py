from typing import Literal

from py_common.config import BaseAppSettings
from pydantic import Field


class QueryServiceSettings(BaseAppSettings):
    """Bounded execution settings for the private query service."""

    query_memory_limit: str = Field(default="512MB")
    query_timeout_seconds: float = Field(default=30.0, gt=0)
    query_default_row_limit: int = Field(default=200, ge=1, le=5000)
    query_max_row_limit: int = Field(default=5000, ge=1, le=5000)
    query_max_scan_bytes: int = Field(default=512 * 1024 * 1024, ge=1)
    query_max_concurrency: int = Field(default=2, ge=1, le=16)
    query_threads: int = Field(default=2, ge=1, le=16)
    query_cache_max_entries: int = Field(default=100, ge=0, le=1000)
    dashboard_max_datasets: int = Field(default=100, ge=1, le=500)
    dashboard_max_partitions: int = Field(default=1000, ge=1, le=5000)
    dashboard_max_movers: int = Field(default=20, ge=1, le=100)
    query_storage_scheme: Literal["s3", "file"] = Field(default="s3")
    query_local_data_root: str | None = Field(default=None)
    query_cors_origins: list[str] = Field(
        default_factory=lambda: ["http://localhost:5173"]
    )


settings = QueryServiceSettings()
