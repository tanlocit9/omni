from __future__ import annotations

import base64
import json
import sqlite3
import threading
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from app.executor import QueryPayload
from app.models import QueryRequest, QueryState
from app.security import ValidatedSql

_TERMINAL_STATES = {
    QueryState.SUCCEEDED,
    QueryState.FAILED,
    QueryState.CANCELLED,
    QueryState.TIMED_OUT,
}


@dataclass(frozen=True)
class StoredQuery:
    query_id: str
    actor: str
    request: QueryRequest
    validated_sql: ValidatedSql
    state: QueryState
    created_at: datetime
    started_at: datetime | None
    completed_at: datetime | None
    payload: QueryPayload | None
    data_versions: dict[str, str]
    error: str | None
    claim_token: str | None
    claimed_by: str | None
    claim_until: datetime | None

    @property
    def duration_ms(self) -> int | None:
        if self.started_at is None or self.completed_at is None:
            return None
        return int((self.completed_at - self.started_at).total_seconds() * 1000)


class QueryStore:
    """SQLite-backed durable query queue with token-fenced claims."""

    def __init__(self, path: str) -> None:
        self._path = path
        self._lock = threading.Lock()

    def initialize(self) -> None:
        if self._path != ":memory:":
            Path(self._path).expanduser().resolve().parent.mkdir(
                parents=True, exist_ok=True
            )
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS queries (
                    query_id TEXT PRIMARY KEY,
                    actor TEXT NOT NULL,
                    request_json TEXT NOT NULL,
                    validated_sql_json TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    started_at TEXT,
                    completed_at TEXT,
                    payload_json TEXT,
                    data_versions_json TEXT NOT NULL DEFAULT '{}',
                    error TEXT,
                    claim_token TEXT,
                    claimed_by TEXT,
                    claim_until TEXT
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS ix_queries_claim "
                "ON queries(state, claim_until, created_at)"
            )

    def enqueue(
        self,
        query_id: str,
        actor: str,
        request: QueryRequest,
        validated_sql: ValidatedSql,
    ) -> StoredQuery:
        created_at = datetime.now(UTC)
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO queries (
                    query_id, actor, request_json, validated_sql_json,
                    state, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    query_id,
                    actor,
                    request.model_dump_json(by_alias=True),
                    json.dumps(
                        {"sql": validated_sql.sql, "root_kind": validated_sql.root_kind}
                    ),
                    QueryState.QUEUED.value,
                    created_at.isoformat(),
                ),
            )
        return self.get(query_id)

    def get(self, query_id: str) -> StoredQuery | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM queries WHERE query_id = ?", (query_id,)
            ).fetchone()
        return self._to_query(row) if row else None

    def claim(self, claimed_by: str, lease_seconds: float) -> StoredQuery | None:
        now = datetime.now(UTC)
        claim_until = now + timedelta(seconds=lease_seconds)
        token = str(uuid.uuid4())
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT query_id FROM queries
                WHERE state = ? OR (state = ? AND claim_until < ?)
                ORDER BY created_at
                LIMIT 1
                """,
                (QueryState.QUEUED.value, QueryState.RUNNING.value, now.isoformat()),
            ).fetchone()
            if row is None:
                connection.commit()
                return None
            connection.execute(
                """
                UPDATE queries
                SET state = ?, started_at = COALESCE(started_at, ?),
                    claim_token = ?, claimed_by = ?, claim_until = ?
                WHERE query_id = ?
                """,
                (
                    QueryState.RUNNING.value,
                    now.isoformat(),
                    token,
                    claimed_by,
                    claim_until.isoformat(),
                    row["query_id"],
                ),
            )
            connection.commit()
        return self.get(row["query_id"])

    def complete(
        self,
        query_id: str,
        claim_token: str,
        state: QueryState,
        *,
        payload: QueryPayload | None = None,
        data_versions: dict[str, str] | None = None,
        error: str | None = None,
        max_payload_bytes: int,
    ) -> bool:
        if state not in _TERMINAL_STATES or state == QueryState.CANCELLED:
            raise ValueError("Worker completion requires a non-cancel terminal state")
        payload_json = self._encode_payload(payload)
        if payload_json is not None and len(payload_json.encode()) > max_payload_bytes:
            state = QueryState.FAILED
            payload_json = None
            error = "Query result exceeds the configured durable payload limit"
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE queries
                SET state = ?, completed_at = ?, payload_json = ?,
                    data_versions_json = ?, error = ?, claim_token = NULL,
                    claimed_by = NULL, claim_until = NULL
                WHERE query_id = ? AND state = ? AND claim_token = ?
                """,
                (
                    state.value,
                    datetime.now(UTC).isoformat(),
                    payload_json,
                    json.dumps(data_versions or {}, sort_keys=True),
                    error,
                    query_id,
                    QueryState.RUNNING.value,
                    claim_token,
                ),
            )
            return cursor.rowcount == 1

    def cancel(self, query_id: str) -> StoredQuery | None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE queries
                SET state = ?, completed_at = ?, claim_token = NULL,
                    claimed_by = NULL, claim_until = NULL
                WHERE query_id = ? AND state IN (?, ?)
                """,
                (
                    QueryState.CANCELLED.value,
                    datetime.now(UTC).isoformat(),
                    query_id,
                    QueryState.QUEUED.value,
                    QueryState.RUNNING.value,
                ),
            )
        return self.get(query_id)

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._path, timeout=30)
        connection.row_factory = sqlite3.Row
        return connection

    @staticmethod
    def _encode_payload(payload: QueryPayload | None) -> str | None:
        if payload is None:
            return None
        return json.dumps(
            {
                "columns": payload.columns,
                "rows": payload.rows,
                "arrow": base64.b64encode(payload.arrow).decode("ascii"),
                "row_count": payload.row_count,
                "truncated": payload.truncated,
            },
            separators=(",", ":"),
            default=str,
        )

    @staticmethod
    def _to_query(row: sqlite3.Row) -> StoredQuery:
        validated = json.loads(row["validated_sql_json"])
        payload_data: dict[str, Any] | None = (
            json.loads(row["payload_json"]) if row["payload_json"] else None
        )
        payload = None
        if payload_data is not None:
            payload = QueryPayload(
                columns=payload_data["columns"],
                rows=payload_data["rows"],
                arrow=base64.b64decode(payload_data["arrow"]),
                row_count=payload_data["row_count"],
                truncated=payload_data["truncated"],
            )
        return StoredQuery(
            query_id=row["query_id"],
            actor=row["actor"],
            request=QueryRequest.model_validate_json(row["request_json"]),
            validated_sql=ValidatedSql(**validated),
            state=QueryState(row["state"]),
            created_at=datetime.fromisoformat(row["created_at"]),
            started_at=(
                datetime.fromisoformat(row["started_at"]) if row["started_at"] else None
            ),
            completed_at=(
                datetime.fromisoformat(row["completed_at"])
                if row["completed_at"]
                else None
            ),
            payload=payload,
            data_versions=json.loads(row["data_versions_json"]),
            error=row["error"],
            claim_token=row["claim_token"],
            claimed_by=row["claimed_by"],
            claim_until=(
                datetime.fromisoformat(row["claim_until"])
                if row["claim_until"]
                else None
            ),
        )
