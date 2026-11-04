"""Bounded I10 shadow registration contract; never grants dataset READY."""

from __future__ import annotations

import json
import re
from datetime import date
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

from py_common.messaging.atomic_work import atomic_work_id

REGISTRATION_TOPIC = "topic-register-output-metadata-v1"
MAX_EVENT_BYTES = 262_144
MAX_REGISTRATIONS = 32
MAX_OUTPUTS = 32
Identifier = Annotated[str, Field(pattern=r"^[a-z0-9][a-z0-9._-]{0,127}$")]
Fingerprint = Annotated[str, Field(pattern=r"^sha256:[0-9a-f]{64}$")]


class WireModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    @model_validator(mode="before")
    @classmethod
    def canonical_identity_fields(cls, raw):
        if not isinstance(raw, dict):
            return raw
        for field in (
            "eventId",
            "event_id",
            "registrationId",
            "registration_id",
            "executionId",
            "execution_id",
            "atomicAttemptId",
            "atomic_attempt_id",
        ):
            if field not in raw:
                continue
            value = raw[field]
            if not isinstance(value, UUID) and (
                not isinstance(value, str)
                or re.fullmatch(
                    r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", value
                )
                is None
            ):
                raise ValueError(f"{field} must be a canonical UUID")
        dates = []
        for field in ("businessDate", "business_date"):
            if field in raw:
                dates.append(raw[field])
        for field in ("coverageDates", "coverage_dates"):
            if isinstance(raw.get(field), list):
                dates.extend(raw[field])
        for value in dates:
            if type(value) is not date and (
                not isinstance(value, str)
                or re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is None
            ):
                raise ValueError("Business/coverage dates must be ISO calendar dates")
        return raw


class LogicalOutput(WireModel):
    dataset: Identifier
    partition: dict[Identifier, Identifier]

    def identity(self) -> str:
        return json.dumps(self.model_dump(), sort_keys=True, separators=(",", ":"))


class RegisteredInput(LogicalOutput):
    data_version: Fingerprint = Field(alias="dataVersion")


class RegisteredColumn(WireModel):
    name: str = Field(min_length=1, max_length=128)
    type: str = Field(min_length=1, max_length=128)
    nullable: bool = Field(strict=True)


class WorkRowScope(WireModel):
    atomic_work_id: str = Field(alias="atomicWorkId", pattern=r"^work:[0-9a-f]{64}$")
    row_keys: list[dict[str, str]] = Field(
        alias="rowKeys", min_length=1, max_length=4096
    )


class RegisteredOutput(LogicalOutput):
    data_version: Fingerprint = Field(alias="dataVersion")
    coverage_dates: list[date] = Field(
        alias="coverageDates", min_length=1, max_length=4096
    )
    row_count: int = Field(alias="rowCount", ge=1, le=2**63 - 1, strict=True)
    total_bytes: int = Field(alias="totalBytes", ge=1, le=2**63 - 1, strict=True)
    schema_version: int = Field(alias="schemaVersion", ge=1, le=2**31 - 1, strict=True)
    schema_hash: Fingerprint = Field(alias="schemaHash")
    columns: list[RegisteredColumn] = Field(min_length=1, max_length=256)
    inputs: list[RegisteredInput] = Field(max_length=128)
    work_scope: WorkRowScope | None = Field(default=None, alias="workScope")

    @model_validator(mode="after")
    def validate_sets(self):
        if self.coverage_dates != sorted(set(self.coverage_dates)):
            raise ValueError("coverageDates must be sorted and unique")
        if len({column.name for column in self.columns}) != len(self.columns):
            raise ValueError("Duplicate output columns")
        if self.work_scope is not None:
            from py_common.storage.work_rows import key_columns

            names = key_columns(self.dataset)
            keys = self.work_scope.row_keys
            allowed = [set(names)]
            if self.dataset == "signals":
                allowed.append(set(names) | {"model_version"})
            if len(keys) != self.row_count or any(set(k) not in allowed for k in keys):
                raise ValueError("Work scope differs from output row count/coordinates")
            if any(
                any(not v.strip() for v in k.values())
                or date.fromisoformat(k[names[-1]]).isoformat() != k[names[-1]]
                for k in keys
            ):
                raise ValueError("Invalid work coordinates/date")
            if len({json.dumps(k, sort_keys=True) for k in keys}) != len(keys):
                raise ValueError("Duplicate work-row coordinates")
            if (
                sorted({date.fromisoformat(k[names[-1]]) for k in keys})
                != self.coverage_dates
            ):
                raise ValueError("Work coverage differs from scoped rows")
        return self


class OutputRegistration(WireModel):
    registration_id: UUID = Field(alias="registrationId")
    execution_id: UUID = Field(alias="executionId")
    atomic_attempt_id: UUID = Field(alias="atomicAttemptId")
    atomic_work_id: str = Field(alias="atomicWorkId", pattern=r"^work:[0-9a-f]{64}$")
    job_type: str = Field(alias="jobType", min_length=1, max_length=100)
    work_type: Literal["SYMBOL", "EXCHANGE", "SECTOR", "GLOBAL"] = Field(
        alias="workType"
    )
    work_key: str = Field(alias="workKey", min_length=1, max_length=256)
    business_date: date = Field(alias="businessDate")
    parameters: dict[str, Any]
    required_outputs: list[LogicalOutput] = Field(
        alias="requiredOutputs", max_length=MAX_OUTPUTS
    )
    outputs: list[RegisteredOutput] = Field(max_length=MAX_OUTPUTS)
    valid_empty_reason: str | None = Field(
        default=None, alias="validEmptyReason", min_length=1, max_length=500
    )

    @model_validator(mode="after")
    def validate_identity_and_completeness(self):
        expected = atomic_work_id(
            self.job_type,
            self.work_type,
            self.work_key,
            self.business_date,
            self.parameters,
        )
        if self.atomic_work_id != expected:
            raise ValueError("atomicWorkId does not match the immutable work tuple")
        if any(
            o.work_scope is not None
            and o.work_scope.atomic_work_id != self.atomic_work_id
            for o in self.outputs
        ):
            raise ValueError("Output scope belongs to another work")
        required = [item.identity() for item in self.required_outputs]
        actual = [
            LogicalOutput(dataset=item.dataset, partition=item.partition).identity()
            for item in self.outputs
        ]
        if len(set(required)) != len(required) or len(set(actual)) != len(actual):
            raise ValueError("Duplicate logical outputs")
        if self.valid_empty_reason is not None:
            if self.outputs or self.required_outputs:
                raise ValueError("Valid-empty cannot claim output coverage")
        elif not required or set(actual) != set(required):
            raise ValueError("Registration must declare every required output")
        return self


class OutputRegistrationEvent(WireModel):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    event_id: UUID = Field(alias="eventId")
    registrations: list[OutputRegistration] = Field(
        min_length=1, max_length=MAX_REGISTRATIONS
    )

    @model_validator(mode="before")
    @classmethod
    def reject_coerced_envelope(cls, raw):
        if isinstance(raw, dict):
            version = raw.get("schemaVersion", raw.get("schema_version"))
            if type(version) is not int:
                raise ValueError("schemaVersion must be an integer")
        return raw

    @model_validator(mode="after")
    def validate_unique_members(self):
        for field in ("registration_id", "execution_id"):
            values = [getattr(item, field) for item in self.registrations]
            if len(set(values)) != len(values):
                raise ValueError(f"Duplicate {field} in registration event")
        return self

    def wire_bytes(self) -> bytes:
        # Revalidate mutable nested lists/dicts before crossing the wire boundary.
        validated = type(self).model_validate(
            self.model_dump(mode="json", by_alias=True)
        )
        raw = validated.model_dump(mode="json", by_alias=True)
        for item in raw["registrations"]:
            for output in item["outputs"]:
                if output.get("workScope") is None:
                    output.pop("workScope", None)
        payload = json.dumps(raw, separators=(",", ":"), ensure_ascii=True).encode(
            "utf-8"
        )
        if len(payload) > MAX_EVENT_BYTES:
            raise ValueError("Registration event exceeds 256 KiB")
        return payload


class OutputRegistrationPublisher:
    """Publish immutable caller-owned IDs; retries must reuse the entire event.

    One attempt is the ordering key for single-member records; multi-member batches
    use the event ID. No output ordering across different Kafka keys is assumed.
    Platform persistence must serialize conflicting logical outputs independently.
    """

    def __init__(self, producer: Any) -> None:
        self._producer = producer

    async def publish(self, event: OutputRegistrationEvent) -> None:
        payload = event.wire_bytes()
        key = (
            str(event.registrations[0].atomic_attempt_id)
            if len(event.registrations) == 1
            else str(event.event_id)
        )
        await self._producer.send_and_wait(
            REGISTRATION_TOPIC, payload, key=key.encode("ascii")
        )

    async def publish_before_success(
        self, event: OutputRegistrationEvent, status: Any, status_publisher: Any
    ) -> None:
        """Single-work producer seam: registration ACK precedes terminal status ACK.

        The caller must have validated durable bytes and must persist/reuse event
        identities on replay. This seam is not enabled by existing worker handlers.
        """
        if len(event.registrations) != 1:
            raise ValueError("A terminal status needs exactly one registration")
        item = event.registrations[0]
        if str(status.status) != "SUCCESS" or status.execution_id != str(
            item.execution_id
        ):
            raise ValueError("Registration/status execution or terminal state mismatch")
        for alias in ("atomicWorkId", "workIdentity"):
            value = status.meta_json.get(alias)
            if value is not None and value != item.atomic_work_id:
                raise ValueError("Registration/status work identity mismatch")
        await self.publish(event)
        await status_publisher.publish(status)
