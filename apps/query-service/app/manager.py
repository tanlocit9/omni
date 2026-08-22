from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import UTC, datetime

from app.audit import AuditSink, QueryAuditEvent
from app.executor import DuckDBExecutor, QueryPayload
from app.models import QueryRequest, QueryState, QueryStatusResponse
from app.security import SqlRejectedError, ValidatedSql, validate_read_only_sql
from app.settings import QueryServiceSettings
from app.storage import DatasetResolver

logger = logging.getLogger(__name__)


class QueryNotFoundError(LookupError):
    pass


class QueryNotReadyError(RuntimeError):
    pass


@dataclass
class QueryRecord:
    query_id: str
    actor: str
    request: QueryRequest
    validated_sql: ValidatedSql
    state: QueryState = QueryState.QUEUED
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    started_at: datetime | None = None
    completed_at: datetime | None = None
    payload: QueryPayload | None = None
    data_versions: dict[str, str] = field(default_factory=dict)
    error: str | None = None
    task: asyncio.Task[None] | None = None

    @property
    def duration_ms(self) -> int | None:
        if self.started_at is None or self.completed_at is None:
            return None
        return int((self.completed_at - self.started_at).total_seconds() * 1000)


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
        self._records: dict[str, QueryRecord] = {}
        self._semaphore = asyncio.Semaphore(settings.query_max_concurrency)
        self._cache: OrderedDict[str, QueryPayload] = OrderedDict()

    async def submit(self, request: QueryRequest, actor: str) -> QueryRecord:
        validated = validate_read_only_sql(
            request.sql,
            {item.view_name for item in request.datasets},
        )
        query_id = str(uuid.uuid4())
        record = QueryRecord(
            query_id=query_id,
            actor=actor,
            request=request,
            validated_sql=validated,
        )
        self._records[query_id] = record
        record.task = asyncio.create_task(self._run(record))
        return record

    def get(self, query_id: str) -> QueryRecord:
        try:
            return self._records[query_id]
        except KeyError as exc:
            raise QueryNotFoundError(query_id) from exc

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

    def result(self, query_id: str) -> tuple[QueryRecord, QueryPayload]:
        record = self.get(query_id)
        if record.state != QueryState.SUCCEEDED or record.payload is None:
            raise QueryNotReadyError(record.state)
        return record, record.payload

    async def cancel(self, query_id: str) -> QueryRecord:
        record = self.get(query_id)
        if record.state in {
            QueryState.SUCCEEDED,
            QueryState.FAILED,
            QueryState.CANCELLED,
            QueryState.TIMED_OUT,
        }:
            return record
        self._executor.cancel(query_id)
        if record.task:
            record.task.cancel()
        record.state = QueryState.CANCELLED
        record.completed_at = datetime.now(UTC)
        self._write_audit(record)
        return record

    async def _run(self, record: QueryRecord) -> None:
        try:
            async with self._semaphore:
                if record.state == QueryState.CANCELLED:
                    return
                record.state = QueryState.RUNNING
                record.started_at = datetime.now(UTC)
                datasets = await self._resolver.resolve_many(record.request.datasets)
                scan_bytes = sum(item.manifest.totalBytes for item in datasets)
                if scan_bytes > self._settings.query_max_scan_bytes:
                    raise ValueError("Query exceeds the configured scan limit")
                record.data_versions = {
                    item.view_name: item.manifest.dataVersion for item in datasets
                }
                row_limit = min(
                    record.request.row_limit or self._settings.query_default_row_limit,
                    self._settings.query_max_row_limit,
                )
                cache_key = self._cache_key(record, row_limit)
                cached = self._cache.get(cache_key)
                if cached is not None:
                    self._cache.move_to_end(cache_key)
                    record.payload = cached
                    record.state = QueryState.SUCCEEDED
                    return
                try:
                    record.payload = await asyncio.wait_for(
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
                    record.state = QueryState.TIMED_OUT
                    record.error = "Query exceeded the configured timeout"
                    return
                record.state = QueryState.SUCCEEDED
                self._store_cache(cache_key, record.payload)
        except asyncio.CancelledError:
            record.state = QueryState.CANCELLED
        except (SqlRejectedError, ValueError) as exc:
            record.state = QueryState.FAILED
            record.error = str(exc)
        except Exception:
            logger.exception("Query %s failed", record.query_id)
            record.state = QueryState.FAILED
            record.error = "Query execution failed"
        finally:
            if record.completed_at is None:
                record.completed_at = datetime.now(UTC)
                self._write_audit(record)

    def _write_audit(self, record: QueryRecord) -> None:
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

    def _cache_key(self, record: QueryRecord, row_limit: int) -> str:
        identity = {
            "sql": record.validated_sql.sql,
            "parameters": record.request.parameters,
            "rowLimit": row_limit,
            "dataVersions": record.data_versions,
        }
        return hashlib.sha256(
            json.dumps(identity, sort_keys=True, default=str).encode()
        ).hexdigest()

    def _store_cache(self, key: str, payload: QueryPayload | None) -> None:
        if payload is None or self._settings.query_cache_max_entries == 0:
            return
        self._cache[key] = payload
        self._cache.move_to_end(key)
        while len(self._cache) > self._settings.query_cache_max_entries:
            self._cache.popitem(last=False)
