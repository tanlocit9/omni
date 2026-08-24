from datetime import date
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from py_common.storage.manifest import ColumnMetadata, DatasetManifest

from app.executor import DuckDBExecutor
from app.security import validate_read_only_sql
from app.settings import QueryServiceSettings
from app.storage import ResolvedDataset

HASH = "sha256:" + "a" * 64
SCHEMA_HASH = "sha256:" + "b" * 64


def _manifest(path: str, total_bytes: int) -> DatasetManifest:
    return DatasetManifest(
        version=1,
        dataset="eod",
        partition={"exchange": "hose"},
        status="READY",
        path=path,
        dataVersion=HASH,
        objectCount=1,
        totalBytes=total_bytes,
        rowCount=3,
        columnCount=2,
        columns=[
            ColumnMetadata(name="code", type="VARCHAR", nullable=False),
            ColumnMetadata(name="close", type="DOUBLE", nullable=False),
        ],
        schemaVersion=1,
        schemaHash=SCHEMA_HASH,
        generatedAt="2026-08-21T00:00:00+00:00",
    )


def _dated_manifest(path: str, total_bytes: int) -> DatasetManifest:
    manifest = _manifest(path, total_bytes)
    return DatasetManifest(
        **{
            **manifest.__dict__,
            "columnCount": 3,
            "columns": [
                ColumnMetadata(name="date", type="DATE", nullable=False),
                ColumnMetadata(
                    name="generated_at", type="TIMESTAMP_US_UTC", nullable=False
                ),
                ColumnMetadata(name="close", type="DOUBLE", nullable=False),
            ],
        }
    )


@pytest.mark.asyncio
async def test_executes_bounded_query_and_returns_arrow(tmp_path: Path) -> None:
    parquet_path = tmp_path / "eod.parquet"
    pq.write_table(
        pa.table({"code": ["ACB", "FRT", "TCB"], "close": [25.0, 142.0, 38.0]}),
        parquet_path,
    )
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
        query_default_row_limit=2,
    )
    executor = DuckDBExecutor(settings)
    dataset = ResolvedDataset(
        view_name="eod",
        manifest=_manifest("eod.parquet", parquet_path.stat().st_size),
        paths=[str(parquet_path)],
    )
    sql = validate_read_only_sql(
        "SELECT code, close FROM eod ORDER BY close DESC", {"eod"}
    )

    result = await executor.execute("query-1", sql, [dataset], {}, row_limit=2)

    assert result.row_count == 2
    assert result.truncated is True
    assert result.rows[0]["code"] == "FRT"
    assert result.arrow


@pytest.mark.asyncio
async def test_normalizes_legacy_parquet_date_types_for_duckdb(tmp_path: Path) -> None:
    parquet_path = tmp_path / "legacy.parquet"
    pq.write_table(
        pa.table(
            {
                "date": pa.array(
                    ["2026-08-25T12:30:00"], type=pa.string()
                ).cast(pa.timestamp("ns")),
                "generated_at": pa.array(
                    ["2026-08-25T03:00:00"], type=pa.string()
                ).cast(pa.timestamp("ns")),
                "close": [100.0],
            }
        ),
        parquet_path,
    )
    settings = QueryServiceSettings(
        query_storage_scheme="file", query_local_data_root=str(tmp_path)
    )
    dataset = ResolvedDataset(
        view_name="legacy_eod",
        manifest=_dated_manifest("legacy.parquet", parquet_path.stat().st_size),
        paths=[str(parquet_path)],
    )
    sql = validate_read_only_sql(
        "SELECT date, generated_at FROM legacy_eod", {"legacy_eod"}
    )

    result = await DuckDBExecutor(settings).execute(
        "legacy-date", sql, [dataset], {}, row_limit=10
    )

    assert result.rows[0]["date"] == date(2026, 8, 25)
    assert result.rows[0]["generated_at"].tzinfo is not None
