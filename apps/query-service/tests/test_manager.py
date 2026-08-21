import asyncio
from pathlib import Path

import pytest
from py_common.storage.manifest import ColumnMetadata, DatasetManifest

from app.executor import DuckDBExecutor
from app.manager import QueryManager
from app.models import DatasetRef, QueryRequest, QueryState
from app.settings import QueryServiceSettings
from app.storage import ResolvedDataset

HASH = "sha256:" + "a" * 64
SCHEMA_HASH = "sha256:" + "b" * 64


class FakeResolver:
    def __init__(self, dataset: ResolvedDataset) -> None:
        self.dataset = dataset

    async def resolve_many(self, refs: list[DatasetRef]) -> list[ResolvedDataset]:
        return [self.dataset]


class CollectingAuditSink:
    def __init__(self) -> None:
        self.events = []

    def write(self, event) -> None:
        self.events.append(event)


class SlowExecutor(DuckDBExecutor):
    def __init__(self, settings: QueryServiceSettings) -> None:
        super().__init__(settings)
        self.cancelled = False

    async def execute(self, *args, **kwargs):
        await asyncio.sleep(10)

    def cancel(self, query_id: str) -> bool:
        self.cancelled = True
        return True


def _dataset(tmp_path: Path, total_bytes: int = 1) -> ResolvedDataset:
    manifest = DatasetManifest(
        version=1,
        dataset="eod",
        partition={"exchange": "hose"},
        status="READY",
        path="eod.parquet",
        dataVersion=HASH,
        objectCount=1,
        totalBytes=total_bytes,
        rowCount=1,
        columnCount=1,
        columns=[ColumnMetadata(name="code", type="VARCHAR")],
        schemaVersion=1,
        schemaHash=SCHEMA_HASH,
        generatedAt="2026-08-21T00:00:00+00:00",
    )
    return ResolvedDataset("eod", manifest, [str(tmp_path / "eod.parquet")])


@pytest.mark.asyncio
async def test_scan_limit_fails_before_execution(tmp_path: Path) -> None:
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
        query_max_scan_bytes=10,
    )
    audit = CollectingAuditSink()
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path, total_bytes=11)),
        DuckDBExecutor(settings),
        settings,
        audit,
    )

    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    await record.task

    assert record.state == QueryState.FAILED
    assert record.error == "Query exceeds the configured scan limit"
    assert audit.events[-1].actor == "tester"


@pytest.mark.asyncio
async def test_timeout_interrupts_executor(tmp_path: Path) -> None:
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
        query_timeout_seconds=0.01,
    )
    executor = SlowExecutor(settings)
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)),
        executor,
        settings,
        CollectingAuditSink(),
    )

    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    await record.task

    assert record.state == QueryState.TIMED_OUT
    assert executor.cancelled is True


@pytest.mark.asyncio
async def test_cancel_marks_running_query(tmp_path: Path) -> None:
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
    )
    executor = SlowExecutor(settings)
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)),
        executor,
        settings,
        CollectingAuditSink(),
    )
    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    await asyncio.sleep(0)

    await manager.cancel(record.query_id)

    assert record.state == QueryState.CANCELLED
    assert executor.cancelled is True
