"""Transaction-local publication evidence; deliberately no journal or recovery loop."""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from datetime import date
from typing import Any

import pandas as pd

from py_common.messaging.output_registration import (
    LogicalOutput, OutputRegistration, OutputRegistrationEvent, OutputRegistrationPublisher,
    RegisteredColumn, RegisteredInput, RegisteredOutput,
)
from py_common.storage.manifest import extract_schema_from_dataframe

_active: ContextVar[OutputPublication | None] = ContextVar("output_publication", default=None)


class OutputPublication:
    def __init__(self, raw: dict[str, Any], snapshots_enabled: bool) -> None:
        self.raw = raw
        self.contract = (raw.get("metadata") or {}).get("outputCompletion")
        self.outputs: dict[str, RegisteredOutput] = {}
        if self.contract is not None and not snapshots_enabled:
            raise ValueError("Enrolled output requires immutable snapshots")

    def record(self, identity: Any, snapshot: Any, frame: pd.DataFrame) -> None:
        if self.contract is None or identity is None or snapshot is None:
            return
        logical = LogicalOutput(dataset=identity.dataset, partition=identity.partition)
        required = [LogicalOutput.model_validate(item) for item in self.contract["requiredOutputs"]]
        if logical not in required:
            raise ValueError("Worker wrote an undeclared snapshot output")
        column = "signal_date" if identity.dataset == "signals" else "date"
        if column not in frame or frame[column].isna().any():
            raise ValueError("Output has no complete calendar-date coverage")
        dates = sorted(set(pd.to_datetime(frame[column], errors="raise").dt.date))
        business_date = date.fromisoformat(str(self.raw["businessDate"]))
        work_rows = frame
        if identity.dataset == "signals":
            work_rows = frame.loc[frame["symbol_key"].astype(str) == self.raw["workKey"]]
        if self.raw["jobType"] != "EVALUATE_SIGNALS" and (
            work_rows.empty or not pd.to_datetime(work_rows[column]).dt.date.eq(business_date).any()
        ):
            raise ValueError(f"Output does not cover work/date {self.raw['workKey']}/{business_date}")
        columns = extract_schema_from_dataframe(frame)
        self.outputs[logical.identity()] = RegisteredOutput(
            dataset=identity.dataset, partition=identity.partition,
            dataVersion=snapshot.data_version, coverageDates=dates,
            rowCount=len(frame), totalBytes=snapshot.total_bytes, schemaVersion=1,
            schemaHash=snapshot.schema_hash,
            columns=[RegisteredColumn(name=c.name, type=c.type, nullable=c.nullable) for c in columns],
            inputs=[RegisteredInput(dataset=i.dataset, partition=i.partition, dataVersion=i.dataVersion)
                    for i in snapshot.inputs],
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
