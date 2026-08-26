from __future__ import annotations

from typing import Any

from py_common.messaging import JobMessage
from pydantic import Field, field_validator


class SyncMetadataJobMessage(JobMessage):
    metadata_type: str = Field(alias="metadataType")
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("metadata_type")
    @classmethod
    def validate_metadata_type(cls, value: str) -> str:
        normalized = value.strip().upper()
        if normalized not in {"EOD", "UNIVERSAL"}:
            raise ValueError("metadataType must be EOD")
        # Compatibility for definitions seeded before the worker existed.
        return "EOD"
