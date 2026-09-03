from unittest.mock import AsyncMock

import pandas as pd
import pytest

from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
)
from py_common.storage.global_metadata import GlobalMetadataDocument
from py_common.storage.metadata_sync import (
    MetadataSyncEmptyError,
    MetadataSynchronizer,
    MetadataSyncTarget,
)
from py_common.storage.parquet import ParquetCodec


class MemoryMetadataReader:
    def __init__(self, document: GlobalMetadataDocument | None = None) -> None:
        self.document = document

    async def read(self) -> GlobalMetadataDocument:
        if self.document is None:
            raise ManifestInvalidError("Global metadata document not found")
        return self.document


class MemoryMetadataWriter:
    def __init__(self, reader: MemoryMetadataReader) -> None:
        self.reader = reader
        self.documents: list[GlobalMetadataDocument] = []

    async def replace(self, document: GlobalMetadataDocument) -> None:
        self.documents.append(document)
        self.reader.document = document


def synchronizer(objects: dict[str, bytes], current=None):
    readable = AsyncMock()

    async def read_bytes(_bucket, name):
        if name not in objects:
            raise StorageObjectNotFoundError("stock-data", name)
        return objects[name]

    readable.read_bytes.side_effect = read_bytes
    listable = AsyncMock()

    async def list_objects(_bucket, prefix):
        return [name for name in objects if name.startswith(prefix)]

    listable.list_objects.side_effect = list_objects
    reader = MemoryMetadataReader(current)
    writer = MemoryMetadataWriter(reader)
    return (
        MetadataSynchronizer(
            readable=readable,
            listable=listable,
            reader=reader,
            writer=writer,
            registry=OMNI_DATASET_REGISTRY,
            bucket="stock-data",
        ),
        writer,
    )


@pytest.mark.asyncio
async def test_full_sync_publishes_one_complete_document() -> None:
    parquet = ParquetCodec.encode(
        pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})
    )
    sync, writer = synchronizer({"eod/hose/hpg.parquet": parquet})

    result = await sync.sync(execution_id="execution-1")

    assert result.mode == "FULL"
    assert result.partitions_added == 1
    assert len(writer.documents) == 1
    partition = writer.documents[0].resolve("eod", {"exchange": "hose", "code": "hpg"})
    assert partition is not None
    assert partition.path == "eod/hose/hpg.parquet"
    assert partition.sourceExecutionId == "execution-1"


@pytest.mark.asyncio
async def test_dataset_sync_removes_stale_partitions_and_preserves_other_datasets() -> (
    None
):
    first = ParquetCodec.encode(
        pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})
    )
    initial_sync, initial_writer = synchronizer(
        {
            "eod/hose/hpg.parquet": first,
            "eod/hose/vnm.parquet": first,
        }
    )
    await initial_sync.sync()
    current = initial_writer.documents[0]

    next_sync, writer = synchronizer({"eod/hose/hpg.parquet": first}, current)
    result = await next_sync.sync(target=MetadataSyncTarget(dataset="eod"))

    assert result.partitions_removed == 1
    assert writer.documents[0].resolve("eod", {"exchange": "hose", "code": "hpg"})
    assert (
        writer.documents[0].resolve("eod", {"exchange": "hose", "code": "vnm"}) is None
    )


@pytest.mark.asyncio
async def test_exact_sync_removes_missing_partition_only() -> None:
    parquet = ParquetCodec.encode(
        pd.DataFrame({"date": ["2026-08-25"], "close": [100.0]})
    )
    initial_sync, initial_writer = synchronizer({"eod/hose/hpg.parquet": parquet})
    await initial_sync.sync()

    next_sync, writer = synchronizer({}, initial_writer.documents[0])
    result = await next_sync.sync(
        target=MetadataSyncTarget(
            dataset="eod", partition={"exchange": "hose", "code": "hpg"}
        )
    )

    assert result.partitions_removed == 1
    assert (
        writer.documents[0].resolve("eod", {"exchange": "hose", "code": "hpg"}) is None
    )


@pytest.mark.asyncio
async def test_full_sync_does_not_publish_when_no_valid_partition_exists() -> None:
    sync, writer = synchronizer({"eod/hose/_versions/old.parquet": b"invalid"})

    with pytest.raises(MetadataSyncEmptyError):
        await sync.sync()

    assert writer.documents == []


@pytest.mark.asyncio
async def test_exact_sync_rejects_partial_partition() -> None:
    empty = GlobalMetadataDocument(
        version=1,
        generatedAt="2026-09-01T13:00:00Z",
        datasets=[],
    )
    sync, writer = synchronizer({}, empty)

    with pytest.raises(ManifestInvalidError, match="requires keys"):
        await sync.sync(
            target=MetadataSyncTarget(dataset="eod", partition={"exchange": "hose"})
        )

    assert writer.documents == []
