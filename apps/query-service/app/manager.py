from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import uuid
from collections import OrderedDict
from contextlib import suppress

from app.audit import AuditSink, QueryAuditEvent
from app.executor import DuckDBExecutor, QueryPayload
from app.models import QueryRequest, QueryState, QueryStatusResponse
from app.query_store import QueryStore, StoredQuery
from app.security import SqlRejectedError, validate_read_only_sql
from app.settings import QueryServiceSettings
from app.storage import DatasetResolver

logger = logging.getLogger(__name__)


class QueryNotFoundError(LookupError):
    pass


class QueryNotReadyError(RuntimeError):
    pass


class QueryManager:
    def __init__(
        self,
        resolver: DatasetResolver,
        executor: DuckDBExecutor,
        settings: QueryServiceSettings,
        audit_sink: AuditSink,
    ) -> None:
        self._resolver = resolver
        self._executor = executor
        self._settings = settings
        self._audit_sink = audit_sink
        self._store = QueryStore(settings.query_db_path)
        self._store.initialize()
        self._worker_id = str(uuid.uuid4())
        self._workers: list[asyncio.Task[None]] = []
        self._stopping = asyncio.Event()
        self._cache: OrderedDict[str, QueryPayload] = OrderedDict()

    async def start(self) -> None:
        if self._workers:
            return
        self._stopping.clear()
        self._workers = [
            asyncio.create_task(self._worker_loop(), name=f"query-worker-{index}")
            for index in range(self._settings.query_max_concurrency)
        ]

    async def stop(self) -> None:
        self._stopping.set()
        for worker in self._workers:
            worker.cancel()
        if self._workers:
            await asyncio.gather(*self._workers, return_exceptions=True)
        self._workers.clear()

    async def submit(self, request: QueryRequest, actor: str) -> StoredQuery:
        validated = validate_read_only_sql(
            request.sql,
            {item.view_name for item in request.datasets},
        )
        return await asyncio.to_thread(
            self._store.enqueue,
            str(uuid.uuid4()),
            actor,
            request,
            validated,
        )

    def get(self, query_id: str) -> StoredQuery:
        record = self._store.get(query_id)
        if record is None:
            raise QueryNotFoundError(query_id)
        return record

    def status(self, query_id: str) -> QueryStatusResponse:
        record = self.get(query_id)
        return QueryStatusResponse(
            queryId=record.query_id,
            state=record.state,
            createdAt=record.created_at,
            startedAt=record.started_at,
            completedAt=record.completed_at,
            durationMs=record.duration_ms,
            rowCount=record.payload.row_count if record.payload else None,
            truncated=record.payload.truncated if record.payload else False,
            dataVersions=record.data_versions,
            error=record.error,
        )

    def result(self, query_id: str) -> tuple[StoredQuery, QueryPayload]:
        record = self.get(query_id)
        if record.state != QueryState.SUCCEEDED or record.payload is None:
            raise QueryNotReadyError(record.state)
        return record, record.payload

    async def cancel(self, query_id: str) -> StoredQuery:
        current = self.get(query_id)
        if current.state in {
            QueryState.SUCCEEDED,
            QueryState.FAILED,
            QueryState.CANCELLED,
            QueryState.TIMED_OUT,
        }:
            return current
        record = await asyncio.to_thread(self._store.cancel, query_id)
        if record is None:
            raise QueryNotFoundError(query_id)
        self._executor.cancel(query_id)
        self._write_audit(record)
        return record

    async def _worker_loop(self) -> None:
        while not self._stopping.is_set():
            record = await asyncio.to_thread(
                self._store.claim,
                self._worker_id,
                self._settings.query_claim_lease_seconds,
            )
            if record is None:
                with suppress(TimeoutError):
                    await asyncio.wait_for(
                        self._stopping.wait(),
                        timeout=self._settings.query_poll_interval_seconds,
                    )
                continue
            await self._run_claim(record)

    async def _run_claim(self, record: StoredQuery) -> None:
        state = QueryState.FAILED
        payload = None
        data_versions: dict[str, str] = {}
        error: str | None = None
        try:
            datasets = await self._resolver.resolve_many(record.request.datasets)
            scan_bytes = sum(item.manifest.totalBytes for item in datasets)
            if scan_bytes > self._settings.query_max_scan_bytes:
                raise ValueError("Query exceeds the configured scan limit")
            data_versions = {
                item.view_name: item.manifest.dataVersion for item in datasets
            }
            row_limit = min(
                record.request.row_limit or self._settings.query_default_row_limit,
                self._settings.query_max_row_limit,
            )
            cache_key = self._cache_key(record, row_limit, data_versions)
            payload = self._cache.get(cache_key)
            if payload is None:
                try:
                    payload = await asyncio.wait_for(
                        self._executor.execute(
                            record.query_id,
                            record.validated_sql,
                            datasets,
                            record.request.parameters,
                            row_limit,
                        ),
                        timeout=self._settings.query_timeout_seconds,
                    )
                except TimeoutError:
                    self._executor.cancel(record.query_id)
                    state = QueryState.TIMED_OUT
                    error = "Query exceeded the configured timeout"
                else:
                    state = QueryState.SUCCEEDED
                    self._store_cache(cache_key, payload)
            else:
                self._cache.move_to_end(cache_key)
                state = QueryState.SUCCEEDED
        except asyncio.CancelledError:
            self._executor.cancel(record.query_id)
            raise
        except (SqlRejectedError, ValueError) as exc:
            error = str(exc)
        except Exception:
            logger.exception("Query %s failed", record.query_id)
            error = "Query execution failed"
        if record.claim_token is None:
            return
        completed = await asyncio.to_thread(
            self._store.complete,
            record.query_id,
            record.claim_token,
            state,
            payload=payload,
            data_versions=data_versions,
            error=error,
            max_payload_bytes=self._settings.query_max_result_bytes,
        )
        if completed:
            self._write_audit(self.get(record.query_id))

    def _write_audit(self, record: StoredQuery) -> None:
        self._audit_sink.write(
            QueryAuditEvent(
                query_id=record.query_id,
                actor=record.actor,
                sql_hash="sha256:"
                + hashlib.sha256(record.request.sql.encode()).hexdigest(),
                state=record.state,
                created_at=record.created_at,
                completed_at=record.completed_at,
                duration_ms=record.duration_ms,
                row_count=record.payload.row_count if record.payload else None,
                data_versions=record.data_versions,
            )
        )

    @staticmethod
    def _cache_key(
        record: StoredQuery, row_limit: int, data_versions: dict[str, str]
    ) -> str:
        identity = {
            "sql": record.validated_sql.sql,
            "parameters": record.request.parameters,
            "rowLimit": row_limit,
            "dataVersions": data_versions,
        }
        return hashlib.sha256(
            json.dumps(identity, sort_keys=True, default=str).encode()
        ).hexdigest()

    def _store_cache(self, key: str, payload: QueryPayload) -> None:
        if self._settings.query_cache_max_entries == 0:
            return
        self._cache[key] = payload
        self._cache.move_to_end(key)
        while len(self._cache) > self._settings.query_cache_max_entries:
            self._cache.popitem(last=False)
