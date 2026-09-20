import asyncio
from pathlib import Path

import pytest
from py_common.storage.manifest import ColumnMetadata, DatasetManifest

from app.executor import DuckDBExecutor, QueryPayload
from app.manager import QueryManager
from app.models import DatasetRef, QueryRequest, QueryState
from app.query_store import QueryStore
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


class SuccessfulExecutor(DuckDBExecutor):
    async def execute(self, *args, **kwargs) -> QueryPayload:
        return QueryPayload(["code"], [{"code": "VCB"}], b"arrow", 1, False)


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


def _settings(tmp_path: Path, **overrides) -> QueryServiceSettings:
    return QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
        query_db_path=str(tmp_path / "queries.sqlite3"),
        query_poll_interval_seconds=0.01,
        **overrides,
    )


async def _wait_for_state(
    manager: QueryManager, query_id: str, expected: QueryState
) -> None:
    for _ in range(100):
        if manager.get(query_id).state == expected:
            return
        await asyncio.sleep(0.01)
    raise AssertionError(f"Query did not reach {expected}")


@pytest.mark.asyncio
async def test_submit_is_durably_queued_before_worker_starts(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)),
        SuccessfulExecutor(settings),
        settings,
        CollectingAuditSink(),
    )

    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )

    assert record.state == QueryState.QUEUED
    assert (
        QueryStore(settings.query_db_path).get(record.query_id).state
        == QueryState.QUEUED
    )


@pytest.mark.asyncio
async def test_result_survives_manager_restart(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    first = QueryManager(
        FakeResolver(_dataset(tmp_path)),
        SuccessfulExecutor(settings),
        settings,
        CollectingAuditSink(),
    )
    await first.start()
    record = await first.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    await _wait_for_state(first, record.query_id, QueryState.SUCCEEDED)
    await first.stop()

    restarted = QueryManager(
        FakeResolver(_dataset(tmp_path)),
        SuccessfulExecutor(settings),
        settings,
        CollectingAuditSink(),
    )
    persisted, payload = restarted.result(record.query_id)

    assert persisted.state == QueryState.SUCCEEDED
    assert payload.rows == [{"code": "VCB"}]
    assert payload.arrow == b"arrow"


@pytest.mark.asyncio
async def test_scan_limit_fails_before_execution(tmp_path: Path) -> None:
    settings = _settings(tmp_path, query_max_scan_bytes=10)
    audit = CollectingAuditSink()
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path, total_bytes=11)),
        DuckDBExecutor(settings),
        settings,
        audit,
    )
    await manager.start()
    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    await _wait_for_state(manager, record.query_id, QueryState.FAILED)
    await manager.stop()

    failed = manager.get(record.query_id)
    assert failed.error == "Query exceeds the configured scan limit"
    assert audit.events[-1].actor == "tester"


@pytest.mark.asyncio
async def test_timeout_interrupts_executor(tmp_path: Path) -> None:
    settings = _settings(tmp_path, query_timeout_seconds=0.01)
    executor = SlowExecutor(settings)
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)), executor, settings, CollectingAuditSink()
    )
    await manager.start()
    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    await _wait_for_state(manager, record.query_id, QueryState.TIMED_OUT)
    await manager.stop()

    assert executor.cancelled is True


@pytest.mark.asyncio
async def test_cancel_fences_running_worker_completion(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    executor = SlowExecutor(settings)
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)), executor, settings, CollectingAuditSink()
    )
    await manager.start()
    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    await _wait_for_state(manager, record.query_id, QueryState.RUNNING)

    cancelled = await manager.cancel(record.query_id)
    await manager.stop()

    assert cancelled.state == QueryState.CANCELLED
    assert manager.get(record.query_id).state == QueryState.CANCELLED
    assert executor.cancelled is True
