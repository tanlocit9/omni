from unittest.mock import AsyncMock

import pandas as pd
import pytest

from py_common.storage.exceptions import ManifestNotFoundError
from py_common.storage.metadata_sync import (
    EodMetadataSynchronizer,
    MetadataSyncEmptyError,
)
from py_common.storage.parquet import ParquetCodec


@pytest.mark.asyncio
async def test_sync_publishes_exact_eod_manifests_then_catalog() -> None:
    parquet_bytes = ParquetCodec.encode(
        pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})
    )
    readable = AsyncMock()
    readable.read_bytes.return_value = parquet_bytes
    listable = AsyncMock()
    listable.list_objects.return_value = [
        "eod/hose/hpg.parquet",
        "eod/hose/_versions/old.parquet",
    ]
    writer = AsyncMock()
    reader = AsyncMock()
    reader.read_manifest.side_effect = ManifestNotFoundError("eod", {})

    result = await EodMetadataSynchronizer(
        readable=readable,
        listable=listable,
        reader=reader,
        writer=writer,
        bucket="stock-data",
    ).sync(execution_id="execution-1")

    assert result.manifests_published == 1
    assert result.objects_skipped == 1
    manifest = writer.write_manifest.await_args.args[0]
    assert manifest.dataset == "eod"
    assert manifest.partition == {"code": "hpg", "exchange": "hose"}
    assert manifest.path == "eod/hose/hpg.parquet"
    assert manifest.inputs == []
    assert manifest.sourceExecutionId == "execution-1"
    writer.write_catalog.assert_awaited_once()
    assert writer.mock_calls[0][0] == "write_manifest"
    assert writer.mock_calls[-1][0] == "write_catalog"


@pytest.mark.asyncio
async def test_sync_does_not_replace_unchanged_immutable_manifest() -> None:
    parquet_bytes = ParquetCodec.encode(
        pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})
    )
    readable = AsyncMock()
    readable.read_bytes.return_value = parquet_bytes
    listable = AsyncMock()
    listable.list_objects.return_value = ["eod/hose/hpg.parquet"]
    reader = AsyncMock()
    reader.read_manifest.side_effect = ManifestNotFoundError("eod", {})
    writer = AsyncMock()
    synchronizer = EodMetadataSynchronizer(
        readable=readable,
        listable=listable,
        reader=reader,
        writer=writer,
        bucket="stock-data",
    )

    await synchronizer.sync()
    current = writer.write_manifest.await_args.args[0]
    writer.reset_mock()
    reader.read_manifest.side_effect = None
    reader.read_manifest.return_value = current

    result = await synchronizer.sync()

    assert result.manifests_published == 0
    assert result.manifests_unchanged == 1
    writer.write_manifest.assert_not_awaited()
    writer.write_catalog.assert_awaited_once()


@pytest.mark.asyncio
async def test_sync_does_not_publish_catalog_without_valid_data() -> None:
    listable = AsyncMock()
    listable.list_objects.return_value = ["eod/hose/_versions/old.parquet"]
    writer = AsyncMock()

    with pytest.raises(MetadataSyncEmptyError):
        await EodMetadataSynchronizer(
            readable=AsyncMock(),
            listable=listable,
            reader=AsyncMock(),
            writer=writer,
            bucket="stock-data",
        ).sync()

    writer.write_manifest.assert_not_awaited()
    writer.write_catalog.assert_not_awaited()


@pytest.mark.asyncio
async def test_sync_reports_corrupt_partition_without_exposing_object_name() -> None:
    readable = AsyncMock()
    readable.read_bytes.side_effect = [
        b"corrupt",
        ParquetCodec.encode(pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})),
    ]
    listable = AsyncMock()
    listable.list_objects.return_value = [
        "eod/hose/bad.parquet",
        "eod/hose/hpg.parquet",
    ]
    writer = AsyncMock()
    reader = AsyncMock()
    reader.read_manifest.side_effect = ManifestNotFoundError("eod", {})

    result = await EodMetadataSynchronizer(
        readable=readable,
        listable=listable,
        reader=reader,
        writer=writer,
        bucket="stock-data",
    ).sync()

    assert result.manifests_published == 1
    assert result.objects_failed == 1
    assert result.is_partial is True
