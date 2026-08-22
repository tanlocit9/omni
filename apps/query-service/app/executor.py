from __future__ import annotations

import asyncio
import io
import threading
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import duckdb
import pyarrow as pa

from app.security import ValidatedSql
from app.settings import QueryServiceSettings
from app.storage import ResolvedDataset


@dataclass(frozen=True)
class QueryPayload:
    columns: list[str]
    rows: list[dict[str, Any]]
    arrow: bytes
    row_count: int
    truncated: bool


class DuckDBExecutor:
    """Execute one bounded query per isolated DuckDB connection."""

    def __init__(self, settings: QueryServiceSettings) -> None:
        self._settings = settings
        self._connections: dict[str, duckdb.DuckDBPyConnection] = {}
        self._lock = threading.Lock()

    async def execute(
        self,
        query_id: str,
        sql: ValidatedSql,
        datasets: list[ResolvedDataset],
        parameters: dict[str, Any],
        row_limit: int,
    ) -> QueryPayload:
        return await asyncio.to_thread(
            self._execute_sync,
            query_id,
            sql,
            datasets,
            parameters,
            row_limit,
        )

    def cancel(self, query_id: str) -> bool:
        with self._lock:
            connection = self._connections.get(query_id)
        if connection is None:
            return False
        connection.interrupt()
        return True

    def _execute_sync(
        self,
        query_id: str,
        sql: ValidatedSql,
        datasets: list[ResolvedDataset],
        parameters: dict[str, Any],
        row_limit: int,
    ) -> QueryPayload:
        connection = duckdb.connect(database=":memory:")
        with self._lock:
            self._connections[query_id] = connection
        try:
            connection.execute(
                f"SET memory_limit = '{self._settings.query_memory_limit}'"
            )
            connection.execute(f"SET threads = {self._settings.query_threads}")
            if self._settings.query_storage_scheme == "s3":
                self._configure_s3(connection)
            for dataset in datasets:
                connection.from_parquet(dataset.paths).create_view(dataset.view_name)

            executable_sql = sql.sql
            if sql.root_kind in {"select", "union"}:
                executable_sql = (
                    "SELECT * FROM ("
                    + executable_sql
                    + f") AS _omni_result LIMIT {row_limit + 1}"
                )
            table = connection.execute(executable_sql, parameters).to_arrow_table()
            truncated = table.num_rows > row_limit
            if truncated:
                table = table.slice(0, row_limit)
            sink = io.BytesIO()
            with pa.ipc.new_stream(sink, table.schema) as writer:
                writer.write_table(table)
            return QueryPayload(
                columns=table.column_names,
                rows=table.to_pylist(),
                arrow=sink.getvalue(),
                row_count=table.num_rows,
                truncated=truncated,
            )
        finally:
            with self._lock:
                self._connections.pop(query_id, None)
            connection.close()

    def _configure_s3(self, connection: duckdb.DuckDBPyConnection) -> None:
        endpoint = self._settings.minio.endpoint.strip()
        parsed = urlparse(endpoint)
        endpoint_host = parsed.netloc if parsed.scheme else endpoint
        use_ssl = self._settings.minio.secure or parsed.scheme == "https"
        secret = self._quote(self._settings.minio.secret_key)
        key_id = self._quote(self._settings.minio.access_key)
        endpoint_value = self._quote(endpoint_host)
        connection.execute(
            "CREATE OR REPLACE SECRET omni_storage ("
            "TYPE S3, "
            f"KEY_ID {key_id}, SECRET {secret}, ENDPOINT {endpoint_value}, "
            f"USE_SSL {'true' if use_ssl else 'false'}, URL_STYLE 'path')"
        )

    @staticmethod
    def _quote(value: str) -> str:
        return "'" + value.replace("'", "''") + "'"
