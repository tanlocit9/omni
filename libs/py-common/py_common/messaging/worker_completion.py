"""One terminal result and its output evidence, acknowledged as one Kafka record."""

from __future__ import annotations

import json
from typing import Literal
from uuid import UUID

from pydantic import Field, model_validator

from py_common.messaging.messages import JobStatus, JobStatusMessage
from py_common.messaging.output_registration import (
    MAX_EVENT_BYTES,
    REGISTRATION_TOPIC,
    OutputRegistrationEvent,
    WireModel,
)

COMPLETION_PROTOCOL = "WORKER_COMPLETION_V1"


class WorkerCompletionEvent(WireModel):
    kind: Literal["WORKER_COMPLETION_V1"] = COMPLETION_PROTOCOL
    schema_version: Literal[1] = Field(alias="schemaVersion")
    event_id: UUID = Field(alias="eventId")
    atomic_attempt_id: UUID = Field(alias="atomicAttemptId")
    status: JobStatusMessage
    registration: OutputRegistrationEvent | None = None

    @model_validator(mode="before")
    @classmethod
    def validate_schema_type(cls, raw):
        if (
            isinstance(raw, dict)
            and type(raw.get("schemaVersion", raw.get("schema_version"))) is not int
        ):
            raise ValueError("schemaVersion must be an integer")
        return raw

    @model_validator(mode="after")
    def validate_terminal_result(self):
        status = self.status
        if status.status not in {JobStatus.SUCCESS, JobStatus.ERROR}:
            raise ValueError("Completion requires SUCCESS or ERROR")
        for value in (status.execution_id, status.job_definition_id):
            UUID(value or "")
        if status.parent_execution_id:
            UUID(status.parent_execution_id)
        if (
            status.started_at.tzinfo is None
            or status.finished_at.tzinfo is None
            or status.finished_at < status.started_at
            or status.duration_ms < 0
            or status.records_processed < 0
        ):
            raise ValueError("Invalid completion timestamps or counters")
        for name in ("recordsProcessed", "recordsInserted"):
            value = status.meta_json.get(name)
            if isinstance(value, bool):
                raise ValueError("Invalid completion metadata counter")
            if (
                value is not None
                and not isinstance(value, (int, float))
                and (
                    not isinstance(value, str)
                    or value != value.strip()
                    or not -(2**31) <= int(value) < 2**31
                )
            ):
                raise ValueError("Invalid completion metadata counter")
        if status.status == JobStatus.ERROR:
            if self.registration is not None:
                raise ValueError("Failed completion cannot register outputs")
            return self
        if self.registration is None or len(self.registration.registrations) != 1:
            raise ValueError("Successful completion requires one registration")
        item = self.registration.registrations[0]
        if (
            self.event_id != self.registration.event_id
            or self.atomic_attempt_id != item.atomic_attempt_id
            or status.execution_id != str(item.execution_id)
            or status.work_type != item.work_type
            or status.work_key != item.work_key
        ):
            raise ValueError("Completion/registration identity mismatch")
        return self

    def wire_bytes(self) -> bytes:
        event = type(self).model_validate(self.model_dump(mode="json", by_alias=True))
        payload = event.model_dump(mode="json", by_alias=True)
        if event.registration is not None:
            payload["registration"] = json.loads(event.registration.wire_bytes())
        encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode(
            "utf-8"
        )
        if len(encoded) > MAX_EVENT_BYTES:
            raise ValueError("Completion event exceeds 256 KiB")
        return encoded


async def publish_completion(producer, event: WorkerCompletionEvent) -> None:
    if producer is None:
        raise RuntimeError("Completion publisher unavailable")
    await producer.send_and_wait(
        REGISTRATION_TOPIC,
        event.wire_bytes(),
        key=str(event.atomic_attempt_id).encode("ascii"),
    )
