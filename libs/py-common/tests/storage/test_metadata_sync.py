from unittest.mock import AsyncMock

import pandas as pd
import pytest

from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY, DatasetAdapterRegistry
from py_common.storage.exceptions import (
    ManifestInvalidError,
    ParquetDecodeError,
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
async def test_confirmed_trend_metadata_uses_only_eod_lineage() -> None:
    eod_version = f"sha256:{'a' * 64}"
    parquet = ParquetCodec.encode(
        pd.DataFrame(
            {
                "symbol_key": ["HOSE-HPG"],
                "eod_data_version": [eod_version],
                "indicators_data_version": [None],
            }
        )
    )
    current = GlobalMetadataDocument(
        version=1,
        generatedAt="2026-09-07T13:00:00Z",
        datasets=[],
    )
    sync, writer = synchronizer(
        {"signals/confirmed_trend_equals/1d/hose.parquet": parquet}, current
    )

    result = await sync.sync(target=MetadataSyncTarget(dataset="signals"))

    assert result.partitions_added == 1
    partition = writer.documents[0].resolve(
        "signals",
        {
            "strategy": "confirmed_trend_equals",
            "timeframe": "1d",
            "exchange": "hose",
        },
    )
    assert partition is not None
    assert [(item.dataset, item.dataVersion) for item in partition.inputs] == [
        ("eod", eod_version)
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("dataset,path,partition", [
    ("symbol-features", "features/symbol/1d/hose/hpg.parquet",
     {"timeframe": "1d", "exchange": "hose", "code": "hpg"}),
    ("sector-features", "features/sector/1d/lv1/bank.parquet",
     {"timeframe": "1d", "sector_level": "1", "sector_code": "bank"}),
])
async def test_feature_metadata_preserves_exact_coverage_holes_and_exact_sync(dataset, path, partition):
    parquet = ParquetCodec.encode(pd.DataFrame({
        "date": ["2026-10-29", "2026-11-02"], "feature_value": [1.0, 2.0],
    }))
    sync, writer = synchronizer({path: parquet})
    await sync.sync()
    current = writer.documents[-1]
    proof = current.resolve(dataset, partition)
    assert proof is not None
    assert proof.path == path
    assert proof.coverageDates == ["2026-10-29", "2026-11-02"]
    assert "2026-10-30" not in proof.coverageDates
    scoped, scoped_writer = synchronizer({path: parquet}, current)
    await scoped.sync(target=MetadataSyncTarget(dataset=dataset, partition=partition))
    assert scoped_writer.documents[-1].resolve(dataset, partition).dataVersion == proof.dataVersion


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


def test_registry_rejects_ambiguous_or_unknown_dataset_definitions():
    definition = OMNI_DATASET_REGISTRY.get("symbol-features")
    with pytest.raises(ManifestInvalidError, match="unique"):
        DatasetAdapterRegistry((definition, definition))
    with pytest.raises(ManifestInvalidError, match="Unsupported dataset"):
        OMNI_DATASET_REGISTRY.get("unknown-feature-dataset")


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["FULL", "EXACT"])
@pytest.mark.parametrize("invalid", ["corrupt", "empty", "missing-date"])
async def test_invalid_feature_object_cannot_replace_existing_metadata(mode, invalid):
    path = "features/symbol/1d/hose/hpg.parquet"
    partition = {"timeframe": "1d", "exchange": "hose", "code": "hpg"}
    good = ParquetCodec.encode(pd.DataFrame({"date": ["2026-11-02"], "value": [1.0]}))
    original, original_writer = synchronizer({path: good})
    await original.sync()
    current = original_writer.documents[-1]
    payload = b"invalid-parquet" if invalid == "corrupt" else ParquetCodec.encode(
        pd.DataFrame({"date": pd.Series([], dtype="datetime64[ns]"), "value": []}) if invalid == "empty"
        else pd.DataFrame({"date": [None], "value": [1.0]})
    )
    sync, writer = synchronizer({path: payload}, current)
    target = None if mode == "FULL" else MetadataSyncTarget(
        dataset="symbol-features", partition=partition
    )
    error = RuntimeError if mode == "FULL" else (
        ParquetDecodeError if invalid == "corrupt" else ManifestInvalidError
    )
    with pytest.raises(error):
        await sync.sync(target=target)
    assert writer.documents == []
    assert writer.reader.document is current
