"""Transaction-local publication evidence; deliberately no journal or recovery loop."""

from __future__ import annotations

import json
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

import pandas as pd

from py_common.messaging.output_registration import (
    MAX_EVENT_BYTES,
    LogicalOutput,
    OutputRegistration,
    OutputRegistrationEvent,
    OutputRegistrationPublisher,
    RegisteredColumn,
    RegisteredInput,
    RegisteredOutput,
    WorkRowScope,
)
from py_common.storage.manifest import (
    calculate_schema_hash,
    extract_schema_from_dataframe,
)
from py_common.storage.work_rows import scope_for_work, work_version

_active: ContextVar[OutputPublication | None] = ContextVar(
    "output_publication", default=None
)


class OutputPublication:
    def __init__(self, raw: dict[str, Any], snapshots_enabled: bool) -> None:
        self.raw = raw
        self.contract = (raw.get("metadata") or {}).get("outputCompletion")
        if self.contract is not None and self.contract.get(
            "storagePolicy", "CANONICAL"
        ) not in {"CANONICAL", "VERSIONED"}:
            raise ValueError("Unsupported output storage policy")
        self.outputs: dict[str, RegisteredOutput] = {}
        self.completion_registration = None
        if self.contract is not None and self.contract.get("protocol") not in {
            None,
            "WORKER_COMPLETION_V1",
        }:
            raise ValueError("Unsupported output completion protocol")

    def prepare(
        self,
        identity: Any,
        snapshot: Any,
        frame: pd.DataFrame,
        *,
        before=None,
        total_bytes=None,
    ):
        """Build bounded evidence without confirming a canonical write."""
        if self.contract is None or identity is None:
            return
        logical = LogicalOutput(dataset=identity.dataset, partition=identity.partition)
        required = [
            LogicalOutput.model_validate(item)
            for item in self.contract["requiredOutputs"]
        ]
        if logical not in required:
            raise ValueError("Worker wrote an undeclared output")
        scope, work_rows = scope_for_work(self.raw, identity.dataset, frame, before)
        WorkRowScope.model_validate(scope)
        column = "signal_date" if identity.dataset == "signals" else "date"
        dates = sorted(set(pd.to_datetime(work_rows[column], errors="raise").dt.date))
        columns = extract_schema_from_dataframe(work_rows)
        inputs = [
            RegisteredInput(
                dataset=i["dataset"],
                partition=i["partition"],
                dataVersion=i["dataVersion"],
            )
            for i in self.raw.get("metadata", {}).get("approvedInputs", [])
        ]
        # Only the direct inputs used by this work, not all file-history versions.
        inputs = list({i.identity(): i for i in inputs}.values())
        schema_hash = calculate_schema_hash(columns)
        version = work_version(
            identity.dataset,
            identity.partition,
            scope,
            work_rows,
            schema_hash,
            [i.model_dump(mode="json", by_alias=True) for i in inputs],
        )
        return RegisteredOutput(
            dataset=identity.dataset,
            partition=identity.partition,
            dataVersion=version,
            coverageDates=dates,
            workScope=scope,
            rowCount=len(work_rows),
            totalBytes=total_bytes or getattr(snapshot, "total_bytes", 1),
            schemaVersion=1,
            schemaHash=schema_hash,
            columns=[
                RegisteredColumn(name=c.name, type=c.type, nullable=c.nullable)
                for c in columns
            ],
            inputs=inputs,
        )

    def confirm(self, output: RegisteredOutput) -> None:
        logical = LogicalOutput(dataset=output.dataset, partition=output.partition)
        self.outputs[logical.identity()] = output

    def record(
        self,
        identity: Any,
        snapshot: Any,
        frame: pd.DataFrame,
        *,
        before=None,
        total_bytes=None,
    ) -> None:
        output = self.prepare(
            identity, snapshot, frame, before=before, total_bytes=total_bytes
        )
        if output is not None:
            self.confirm(output)

    def _event(self, outputs, *, valid_empty_reason=None, required=None):
        raw, contract = self.raw, self.contract
        return OutputRegistrationEvent(
            schemaVersion=1,
            eventId=contract["eventId"],
            registrations=[
                OutputRegistration(
                    registrationId=contract["registrationId"],
                    executionId=raw["executionId"],
                    atomicAttemptId=contract["atomicAttemptId"],
                    atomicWorkId=raw["atomicWorkId"],
                    jobType=raw["jobType"],
                    workType=raw["workType"],
                    workKey=raw["workKey"],
                    businessDate=raw["businessDate"],
                    parameters=raw["parameters"],
                    requiredOutputs=[]
                    if valid_empty_reason
                    else (
                        contract["requiredOutputs"] if required is None else required
                    ),
                    outputs=outputs,
                    validEmptyReason=valid_empty_reason,
                )
            ],
        )

    def preflight(self, candidate: RegisteredOutput) -> None:
        """Reject invalid identities, bounds and wire size before replacing bytes."""
        outputs = dict(self.outputs)
        logical = LogicalOutput(
            dataset=candidate.dataset, partition=candidate.partition
        )
        outputs[logical.identity()] = candidate
        declared = [
            LogicalOutput.model_validate(item)
            for item in self.contract["requiredOutputs"]
        ]
        if (
            len(declared) != len({item.identity() for item in declared})
            or len(declared) > 32
        ):
            raise ValueError("Invalid required output declaration")
        present = [
            LogicalOutput(dataset=o.dataset, partition=o.partition)
            for o in outputs.values()
        ]
        payload = json.loads(
            self._event(list(outputs.values()), required=present).wire_bytes()
        )
        # Reserve the complete declaration even while collecting multiple outputs.
        payload["registrations"][0]["requiredOutputs"] = [
            o.model_dump(mode="json", by_alias=True) for o in declared
        ]
        if (
            len(
                json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode(
                    "utf-8"
                )
            )
            > MAX_EVENT_BYTES
        ):
            raise ValueError("Registration event exceeds 256 KiB")
        # TODO(P12-I4): stage/preflight ALL outputs before the first canonical
        # mutation in a multi-output batch; current server declarations have one.

    async def publish(
        self, producer: Any, *, valid_empty_reason: str | None = None
    ) -> None:
        if self.contract is None:
            return
        event = self._event(
            list(self.outputs.values()), valid_empty_reason=valid_empty_reason
        )
        if self.contract.get("protocol") == "WORKER_COMPLETION_V1":
            self.completion_registration = event
            return
        if producer is None:
            raise RuntimeError("Output registration publisher unavailable")
        await OutputRegistrationPublisher(producer).publish(event)


@contextmanager
def capture_output_publication(raw: dict[str, Any], snapshots_enabled: bool):
    publication = OutputPublication(raw, snapshots_enabled)
    token = _active.set(publication)
    try:
        yield publication
    finally:
        _active.reset(token)


def record_output(identity: Any, snapshot: Any, frame: pd.DataFrame) -> None:
    publication = _active.get()
    if publication is not None:
        publication.record(identity, snapshot, frame)


def active_publication():
    publication = _active.get()
    return (
        publication
        if publication is not None and publication.contract is not None
        else None
    )


async def publish_terminal_status(
    producer, legacy_publish, status, raw, publication=None
):
    """Keep legacy wire; new enrolled commands send one completion."""
    from py_common.messaging.messages import JobStatus
    from py_common.messaging.worker_completion import (
        COMPLETION_PROTOCOL,
        WorkerCompletionEvent,
        publish_completion,
    )

    contract = (raw.get("metadata") or {}).get("outputCompletion") or {}
    protocol = contract.get("protocol")
    if protocol is None:
        await legacy_publish(status)
        return
    if protocol != COMPLETION_PROTOCOL:
        raise ValueError("Unsupported output completion protocol")
    registration = (
        publication.completion_registration
        if publication is not None and status.status == JobStatus.SUCCESS
        else None
    )
    await publish_completion(
        producer,
        WorkerCompletionEvent(
            schemaVersion=1,
            eventId=contract["eventId"],
            atomicAttemptId=contract["atomicAttemptId"],
            status=status,
            registration=registration,
        ),
    )
