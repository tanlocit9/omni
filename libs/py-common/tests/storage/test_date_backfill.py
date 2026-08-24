from __future__ import annotations

from unittest.mock import AsyncMock

import pandas as pd
import pytest

from py_common.storage.date_backfill import ParquetDateBackfill
from py_common.storage.manifest import ColumnMetadata, DatasetManifest
from py_common.storage.parquet import ParquetWriteResult


def _manifest(*, path: str = "eod/hose/hpg.parquet") -> DatasetManifest:
    return DatasetManifest(
        version=1,
        dataset="eod",
        partition={"exchange": "hose", "symbol": "hpg"},
        status="READY",
        path=path,
        dataVersion="sha256:" + "a" * 64,
        objectCount=1,
        totalBytes=100,
        rowCount=1,
        columnCount=2,
        columns=[
            ColumnMetadata("date", "TIMESTAMP", False),
            ColumnMetadata("close", "DOUBLE", False),
        ],
        schemaVersion=1,
        schemaHash="sha256:" + "b" * 64,
        generatedAt="2026-08-25T00:00:00+00:00",
    )


@pytest.mark.asyncio
async def test_backfill_writes_versioned_candidate_then_ready_manifest() -> None:
    current = _manifest()
    frame = pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})
    parquet = AsyncMock()
    parquet.read_dataframe.side_effect = [frame, frame]
    parquet.write_dataframe.return_value = ParquetWriteResult(
        object_name="candidate", checksum="sha256:" + "c" * 64, total_bytes=120
    )
    reader = AsyncMock()
    reader.read_manifest.return_value = current
    writer = AsyncMock()

    result = await ParquetDateBackfill(parquet, reader, writer).rewrite(
        "eod", current.partition, execution_id="backfill-1"
    )

    candidate = parquet.write_dataframe.await_args.args[0]
    assert candidate.startswith("eod/hose/_versions/date-contract-v1/")
    assert candidate != current.path
    assert result.rewritten is True
    assert result.manifest.path == candidate
    writer.write_manifest.assert_awaited_once()


@pytest.mark.asyncio
async def test_backfill_is_idempotent_after_contract_ready() -> None:
    current = _manifest(
        path="eod/hose/_versions/date-contract-v1/hpg-aaaaaaaaaaaaaaaa.parquet"
    )
    current = DatasetManifest(
        **{
            **current.__dict__,
            "columns": [
                ColumnMetadata("date", "DATE", False),
                ColumnMetadata("close", "DOUBLE", False),
            ],
        }
    )
    parquet = AsyncMock()
    reader = AsyncMock()
    reader.read_manifest.return_value = current
    writer = AsyncMock()

    result = await ParquetDateBackfill(parquet, reader, writer).rewrite(
        "eod", current.partition
    )

    assert result.rewritten is False
    parquet.write_dataframe.assert_not_awaited()
    writer.write_manifest.assert_not_awaited()


@pytest.mark.asyncio
async def test_backfill_failure_does_not_replace_source_object() -> None:
    current = _manifest()
    frame = pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})
    parquet = AsyncMock()
    parquet.read_dataframe.side_effect = [frame, frame]
    parquet.write_dataframe.return_value = ParquetWriteResult(
        object_name="candidate", checksum="sha256:" + "c" * 64, total_bytes=120
    )
    reader = AsyncMock()
    reader.read_manifest.return_value = current
    writer = AsyncMock()
    writer.write_manifest.side_effect = RuntimeError("READY publication failed")

    with pytest.raises(RuntimeError, match="READY publication failed"):
        await ParquetDateBackfill(parquet, reader, writer).rewrite(
            "eod", current.partition
        )

    written_path = parquet.write_dataframe.await_args.args[0]
    assert written_path != current.path
