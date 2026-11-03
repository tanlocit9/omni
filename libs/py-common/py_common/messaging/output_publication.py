"""Transaction-local publication evidence; deliberately no journal or recovery loop."""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

import pandas as pd

from py_common.messaging.output_registration import (
    LogicalOutput, OutputRegistration, OutputRegistrationEvent, OutputRegistrationPublisher,
    RegisteredColumn, RegisteredInput, RegisteredOutput,
)
from py_common.storage.manifest import extract_schema_from_dataframe
from py_common.storage.manifest import calculate_schema_hash
from py_common.storage.work_rows import scope_for_work, work_version

_active: ContextVar[OutputPublication | None] = ContextVar("output_publication", default=None)


class OutputPublication:
    def __init__(self, raw: dict[str, Any], snapshots_enabled: bool) -> None:
        self.raw = raw
        self.contract = (raw.get("metadata") or {}).get("outputCompletion")
        self.outputs: dict[str, RegisteredOutput] = {}

    def record(self, identity: Any, snapshot: Any, frame: pd.DataFrame, *, before=None, total_bytes=None) -> None:
        if self.contract is None or identity is None:
            return
        logical = LogicalOutput(dataset=identity.dataset, partition=identity.partition)
        required = [LogicalOutput.model_validate(item) for item in self.contract["requiredOutputs"]]
        if logical not in required:
            raise ValueError("Worker wrote an undeclared output")
        scope, work_rows = scope_for_work(self.raw, identity.dataset, frame, before)
        column = "signal_date" if identity.dataset == "signals" else "date"
        dates = sorted(set(pd.to_datetime(work_rows[column], errors="raise").dt.date))
        columns = extract_schema_from_dataframe(work_rows)
        inputs = [RegisteredInput(dataset=i["dataset"], partition=i["partition"], dataVersion=i["dataVersion"])
                  for i in self.raw.get("metadata", {}).get("approvedInputs", [])]
        # Only the direct inputs used by this work, never all versions from file history.
        inputs = list({i.identity(): i for i in inputs}.values())
        schema_hash = calculate_schema_hash(columns)
        version = work_version(identity.dataset, identity.partition, scope, work_rows, schema_hash,
                               [i.model_dump(mode="json", by_alias=True) for i in inputs])
        self.outputs[logical.identity()] = RegisteredOutput(
            dataset=identity.dataset, partition=identity.partition,
            dataVersion=version, coverageDates=dates, workScope=scope,
            rowCount=len(work_rows), totalBytes=total_bytes or getattr(snapshot, "total_bytes", 1), schemaVersion=1,
            schemaHash=schema_hash,
            columns=[RegisteredColumn(name=c.name, type=c.type, nullable=c.nullable) for c in columns],
            inputs=inputs,
        )

    async def publish(self, producer: Any, *, valid_empty_reason: str | None = None) -> None:
        if self.contract is None:
            return
        raw, contract = self.raw, self.contract
        event = OutputRegistrationEvent(
            schemaVersion=1, eventId=contract["eventId"], registrations=[OutputRegistration(
                registrationId=contract["registrationId"], executionId=raw["executionId"],
                atomicAttemptId=contract["atomicAttemptId"], atomicWorkId=raw["atomicWorkId"],
                jobType=raw["jobType"], workType=raw["workType"], workKey=raw["workKey"],
                businessDate=raw["businessDate"], parameters=raw["parameters"],
                requiredOutputs=[] if valid_empty_reason else contract["requiredOutputs"],
                outputs=list(self.outputs.values()), validEmptyReason=valid_empty_reason,
            )],
        )
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
    return publication if publication is not None and publication.contract is not None else None
