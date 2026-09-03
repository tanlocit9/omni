from pathlib import Path

import pytest
from py_common.storage.global_metadata import (
    GlobalColumnMetadata,
    GlobalDatasetMetadata,
    GlobalMetadataDocument,
    GlobalPartitionMetadata,
    PartitionKeyDefinition,
    PartitionValueType,
)

from app.models import DatasetRef
from app.settings import QueryServiceSettings
from app.storage import DatasetResolver

HASH = "sha256:" + "a" * 64
SCHEMA_HASH = "sha256:" + "b" * 64


class FakeMetadataReader:
    async def read(self) -> GlobalMetadataDocument:
        partition = GlobalPartitionMetadata(
            values={"exchange": "hose"},
            status="READY",
            path="eod/hose/data.parquet",
            dataVersion=HASH,
            objectCount=1,
            totalBytes=100,
            rowCount=1,
            columnCount=1,
            columns=[GlobalColumnMetadata(name="code", type="VARCHAR", nullable=True)],
            schemaVersion=1,
            schemaHash=SCHEMA_HASH,
            generatedAt="2026-08-21T00:00:00+00:00",
        )
        return GlobalMetadataDocument(
            version=1,
            generatedAt="2026-08-21T00:00:00+00:00",
            datasets=[
                GlobalDatasetMetadata(
                    name="eod",
                    label="End-of-Day Prices",
                    dataPrefix="eod/",
                    partitionKeys=[
                        PartitionKeyDefinition(
                            "exchange", PartitionValueType.STRING, True, 0
                        )
                    ],
                    partitions=[partition],
                )
            ],
        )


@pytest.mark.asyncio
async def test_resolves_logical_ref_to_server_side_path(tmp_path: Path) -> None:
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
    )
    resolver = DatasetResolver(FakeMetadataReader(), settings)

    resolved = await resolver.resolve_many(
        [DatasetRef(dataset="eod", partition={"exchange": "hose"})]
    )

    assert resolved[0].view_name == "eod"
    assert resolved[0].manifest.dataVersion == HASH
    assert resolved[0].paths == [str(tmp_path / "eod/hose/data.parquet")]


@pytest.mark.asyncio
async def test_rejects_stale_requested_version(tmp_path: Path) -> None:
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
    )
    resolver = DatasetResolver(FakeMetadataReader(), settings)
    stale = "sha256:" + "c" * 64

    with pytest.raises(ValueError, match="no longer matches"):
        await resolver.resolve_many(
            [
                DatasetRef(
                    dataset="eod",
                    partition={"exchange": "hose"},
                    dataVersion=stale,
                )
            ]
        )
