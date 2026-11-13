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
async def test_shutdown_after_terminal_commit_waits_for_its_audit(
    tmp_path, monkeypatch
):
    import threading

    settings = _settings(tmp_path, query_max_scan_bytes=10)
    audit = CollectingAuditSink()
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path, total_bytes=11)),
        SuccessfulExecutor(settings),
        settings,
        audit,
    )
    committed = threading.Event()
    release = threading.Event()
    complete = manager._store.complete

    def complete_then_wait(*args, **kwargs):
        result = complete(*args, **kwargs)
        committed.set()
        assert release.wait(5), "Test must release the committed DB operation"
        return result

    monkeypatch.setattr(manager._store, "complete", complete_then_wait)
    await manager.start()
    stopping = None
    try:
        record = await manager.submit(
            QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
            "tester",
        )
        assert await asyncio.to_thread(committed.wait, 5)
        assert manager.get(record.query_id).state == QueryState.FAILED
        assert audit.events == []
        stopping = asyncio.create_task(manager.stop())
        await asyncio.sleep(0)
    finally:
        release.set()
        if stopping is None:
            await manager.stop()
        else:
            await asyncio.wait_for(stopping, timeout=5)
    assert len(audit.events) == 1
    assert audit.events[0].query_id == record.query_id
    assert audit.events[0].state == QueryState.FAILED


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


@pytest.mark.asyncio
async def test_cancelled_claim_cannot_finish_or_emit_a_second_audit(tmp_path):
    settings = _settings(tmp_path)
    audit = CollectingAuditSink()
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)), SuccessfulExecutor(settings), settings, audit
    )
    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    claimed = manager._store.claim("test-worker", 60)
    await manager.cancel(record.query_id)
    await manager._run_claim(claimed)
    assert manager.get(record.query_id).state == QueryState.CANCELLED
    assert [event.state for event in audit.events] == [QueryState.CANCELLED]
    await manager.stop()


@pytest.mark.asyncio
@pytest.mark.parametrize("capacity,expected_calls", [(0, 4), (1, 3), (2, 2)])
async def test_query_cache_is_optional_and_evicts_without_reusing_another_sql(
    tmp_path, capacity, expected_calls
):
    class CountingExecutor(SuccessfulExecutor):
        calls = 0

        async def execute(self, *args, **kwargs):
            self.calls += 1
            return await super().execute(*args, **kwargs)

    settings = _settings(
        tmp_path, query_cache_max_entries=capacity, query_max_concurrency=1
    )
    executor = CountingExecutor(settings)
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)), executor, settings, CollectingAuditSink()
    )
    await manager.start()
    await manager.start()  # Repeated startup must not create extra worker pools.
    try:
        for sql in [
            "SELECT * FROM eod",
            "SELECT * FROM eod",
            "SELECT code FROM eod",
            "SELECT * FROM eod",
        ]:
            record = await manager.submit(
                QueryRequest(sql=sql, datasets=[DatasetRef(dataset="eod")]), "tester"
            )
            await _wait_for_state(manager, record.query_id, QueryState.SUCCEEDED)
            assert manager.result(record.query_id)[1].rows == [{"code": "VCB"}]
        assert executor.calls == expected_calls
    finally:
        await manager.stop()


@pytest.mark.asyncio
async def test_query_lifecycle_rejects_pending_results_and_cancel_is_idempotent(
    tmp_path,
):
    from app.manager import QueryNotFoundError, QueryNotReadyError

    settings = _settings(tmp_path)
    audit = CollectingAuditSink()
    manager = QueryManager(
        FakeResolver(_dataset(tmp_path)), SuccessfulExecutor(settings), settings, audit
    )
    with pytest.raises(QueryNotFoundError):
        manager.get("missing-query")
    record = await manager.submit(
        QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
        "tester",
    )
    assert manager.status(record.query_id).state == QueryState.QUEUED
    with pytest.raises(QueryNotReadyError):
        manager.result(record.query_id)
    assert (await manager.cancel(record.query_id)).state == QueryState.CANCELLED
    assert (await manager.cancel(record.query_id)).state == QueryState.CANCELLED
    assert len(audit.events) == 1
    await manager.stop()


@pytest.mark.asyncio
async def test_reader_failure_finishes_without_exposing_internal_error_or_result(
    tmp_path,
):
    from unittest.mock import AsyncMock

    settings = _settings(tmp_path)
    audit = CollectingAuditSink()
    resolver = AsyncMock()
    resolver.resolve_many.side_effect = RuntimeError("internal reader detail")
    manager = QueryManager(resolver, SuccessfulExecutor(settings), settings, audit)
    await manager.start()
    try:
        record = await manager.submit(
            QueryRequest(sql="SELECT * FROM eod", datasets=[DatasetRef(dataset="eod")]),
            "tester",
        )
        await _wait_for_state(manager, record.query_id, QueryState.FAILED)
    finally:
        await manager.stop()
    failed = manager.get(record.query_id)
    assert failed.error == "Query execution failed"
    assert failed.payload is None
    assert audit.events[-1].state == QueryState.FAILED
