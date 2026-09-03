from __future__ import annotations

from typing import Any

from py_common.messaging import JobMessage
from pydantic import BaseModel, ConfigDict, Field


class MetadataTarget(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dataset: str
    partition: dict[str, Any] | None = None


class SyncMetadataJobMessage(JobMessage):
    target: MetadataTarget | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
