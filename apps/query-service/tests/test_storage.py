from pathlib import Path

import pytest
from py_common.storage.manifest import ColumnMetadata, DatasetManifest

from app.models import DatasetRef
from app.settings import QueryServiceSettings
from app.storage import DatasetResolver

HASH = "sha256:" + "a" * 64
SCHEMA_HASH = "sha256:" + "b" * 64


class FakeManifestReader:
    def __init__(self, manifest: DatasetManifest) -> None:
        self.manifest = manifest

    async def read_manifest(self, dataset: str, partition: dict[str, str]):
        return self.manifest


def _manifest() -> DatasetManifest:
    return DatasetManifest(
        version=1,
        dataset="eod",
        partition={"exchange": "hose"},
        status="READY",
        path="eod/hose/*.parquet",
        dataVersion=HASH,
        objectCount=1,
        totalBytes=100,
        rowCount=1,
        columnCount=1,
        columns=[ColumnMetadata(name="code", type="VARCHAR")],
        schemaVersion=1,
        schemaHash=SCHEMA_HASH,
        generatedAt="2026-08-21T00:00:00+00:00",
    )


@pytest.mark.asyncio
async def test_resolves_logical_ref_to_server_side_path(tmp_path: Path) -> None:
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
    )
    resolver = DatasetResolver(FakeManifestReader(_manifest()), settings)

    resolved = await resolver.resolve_many(
        [DatasetRef(dataset="eod", partition={"exchange": "hose"})]
    )

    assert resolved[0].view_name == "eod"
    assert resolved[0].manifest.dataVersion == HASH
    assert resolved[0].paths == [str(tmp_path / "eod/hose/*.parquet")]


@pytest.mark.asyncio
async def test_rejects_stale_requested_version(tmp_path: Path) -> None:
    settings = QueryServiceSettings(
        query_storage_scheme="file",
        query_local_data_root=str(tmp_path),
    )
    resolver = DatasetResolver(FakeManifestReader(_manifest()), settings)
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
