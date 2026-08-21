from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class QueryState(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    TIMED_OUT = "TIMED_OUT"


class DatasetRef(BaseModel):
    dataset: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]*$")
    partition: dict[str, str] = Field(default_factory=dict)
    alias: str | None = Field(default=None, pattern=r"^[a-z_][a-z0-9_]*$")
    data_version: str | None = Field(
        default=None,
        alias="dataVersion",
        pattern=r"^sha256:[0-9a-f]{64}$",
    )

    @field_validator("partition")
    @classmethod
    def validate_partition(cls, value: dict[str, str]) -> dict[str, str]:
        for key, item in value.items():
            if not key or not item or "/" in key or "/" in item or ".." in (key, item):
                raise ValueError("partition keys and values must be path-safe")
        return dict(sorted(value.items()))

    @property
    def view_name(self) -> str:
        return self.alias or self.dataset.replace("-", "_").replace(".", "_")


class QueryRequest(BaseModel):
    sql: str = Field(min_length=1, max_length=100_000)
    datasets: list[DatasetRef] = Field(min_length=1, max_length=20)
    parameters: dict[str, Any] = Field(default_factory=dict)
    row_limit: int | None = Field(default=None, alias="rowLimit", ge=1, le=5000)

    @model_validator(mode="after")
    def aliases_are_unique(self) -> QueryRequest:
        aliases = [item.view_name for item in self.datasets]
        if len(aliases) != len(set(aliases)):
            raise ValueError("dataset aliases must be unique")
        return self


class QueryAccepted(BaseModel):
    query_id: str = Field(alias="queryId")
    state: QueryState


class QueryStatusResponse(BaseModel):
    query_id: str = Field(alias="queryId")
    state: QueryState
    created_at: datetime = Field(alias="createdAt")
    started_at: datetime | None = Field(default=None, alias="startedAt")
    completed_at: datetime | None = Field(default=None, alias="completedAt")
    duration_ms: int | None = Field(default=None, alias="durationMs")
    row_count: int | None = Field(default=None, alias="rowCount")
    truncated: bool = False
    data_versions: dict[str, str] = Field(default_factory=dict, alias="dataVersions")
    error: str | None = None


class JsonQueryResult(BaseModel):
    query_id: str = Field(alias="queryId")
    columns: list[str]
    rows: list[dict[str, Any]]
    row_count: int = Field(alias="rowCount")
    truncated: bool
    data_versions: dict[str, str] = Field(alias="dataVersions")


ResultFormat = Literal["json", "arrow"]
