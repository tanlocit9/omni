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


def test_shadow_services_share_the_query_parquet_validator():
    from unittest.mock import AsyncMock, Mock

    from py_common.storage.output_readiness import ShadowMetadataReader
    from pydantic import SecretStr

    from app.storage import build_metadata_services

    registry = Mock()
    registry.get_port.return_value = AsyncMock()
    settings = QueryServiceSettings(
        output_readiness_mode="shadow",
        output_readiness_api_url="http://platform/readiness",
        output_readiness_api_token=SecretStr("fixture"),
    )
    resolver, catalog = build_metadata_services(registry, settings)
    assert isinstance(resolver._reader, ShadowMetadataReader)
    assert catalog._reader is resolver._reader
    assert resolver._reader._parquet is resolver._parquet_storage


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


@pytest.mark.asyncio
@pytest.mark.parametrize("aggregate", [False, True])
async def test_scoped_query_filters_ready_rows_and_keeps_the_validated_buffer(
    aggregate,
):
    from dataclasses import replace
    from datetime import date
    from unittest.mock import AsyncMock

    import pandas as pd

    reader = FakeMetadataReader()
    document = await reader.read()
    section = document.datasets[0]
    original = section.partitions[0]
    proof = replace(
        original,
        columns=[GlobalColumnMetadata(name="date", type="DATE", nullable=False)],
        workScope={
            "atomicWorkId": "work:" + "a" * 64,
            "rowKeys": [{"date": "2026-11-02"}],
        },
        coverageDates=["2026-11-02"],
        canonicalPath=original.path,
    )
    document = replace(document, datasets=[replace(section, partitions=[proof])])
    if aggregate:
        proof = replace(
            proof,
            workScope=None,
            workProofs=[
                {
                    "workScope": {
                        "atomicWorkId": "work:" + "a" * 64,
                        "rowKeys": [{"date": "2026-11-02"}],
                    }
                },
                {
                    "workScope": {
                        "atomicWorkId": "work:" + "b" * 64,
                        "rowKeys": [{"date": "2026-11-03"}],
                    }
                },
            ],
        )
        document = replace(document, datasets=[replace(section, partitions=[proof])])
    checked = pd.DataFrame(
        {
            "date": [date(2026, 11, 2), date(2026, 11, 3), date(2026, 11, 4)],
            "close": [10, 20, 30],
        }
    )
    parquet = AsyncMock(read_bound_dataframe=AsyncMock(return_value=checked))
    resolver = DatasetResolver(
        AsyncMock(read=AsyncMock(return_value=document)),
        QueryServiceSettings(query_storage_scheme="s3"),
        parquet,
    )
    resolved = await resolver.resolve_many(
        [DatasetRef(dataset="eod", partition={"exchange": "hose"})]
    )
    assert resolved[0].frame["close"].tolist() == ([10, 20] if aggregate else [10])
    parquet.read_bound_dataframe.assert_awaited_once_with("eod", proof, HASH)


@pytest.mark.asyncio
@pytest.mark.parametrize("case", ["missing", "local-authority", "no-validator"])
async def test_work_readiness_cannot_fall_back_to_unchecked_physical_data(
    case, tmp_path
):
    from dataclasses import replace
    from unittest.mock import AsyncMock

    document = await FakeMetadataReader().read()
    section = document.datasets[0]
    proof = replace(section.partitions[0], canonicalPath=section.partitions[0].path)
    section = replace(section, partitions=[] if case == "missing" else [proof])
    document = replace(document, datasets=[section])
    settings = QueryServiceSettings(
        query_storage_scheme="file" if case == "local-authority" else "s3",
        query_local_data_root=str(tmp_path),
    )
    parquet = None if case == "no-validator" else AsyncMock()
    resolver = DatasetResolver(
        AsyncMock(read=AsyncMock(return_value=document)), settings, parquet
    )
    with pytest.raises(ValueError):
        await resolver.resolve_many(
            [DatasetRef(dataset="eod", partition={"exchange": "hose"})]
        )
    if parquet is not None:
        parquet.read_bound_dataframe.assert_not_awaited()


@pytest.mark.asyncio
async def test_signal_ready_scope_exposes_only_the_registered_model():
    from dataclasses import replace
    from unittest.mock import AsyncMock

    import pandas as pd

    document = await FakeMetadataReader().read()
    section = document.datasets[0]
    original = section.partitions[0]
    partition = {
        "strategy": "confirmed_trend_equals",
        "exchange": "hose",
        "timeframe": "1d",
    }
    proof = replace(
        original,
        values=partition,
        canonicalPath=original.path,
        workScope={
            "atomicWorkId": "work:" + "a" * 64,
            "rowKeys": [
                {
                    "symbol_key": "HOSE-HPG",
                    "signal_date": "2026-11-02",
                    "model_version": "V2",
                }
            ],
        },
    )
    document = replace(
        document,
        datasets=[
            replace(
                section,
                name="signals",
                dataPrefix="signals/",
                label="Signals",
                partitionKeys=[
                    PartitionKeyDefinition(name, PartitionValueType.STRING, True, order)
                    for order, name in enumerate(("strategy", "timeframe", "exchange"))
                ],
                partitions=[proof],
            )
        ],
    )
    checked = pd.DataFrame(
        {
            "symbol_key": ["HOSE-HPG"] * 3,
            "signal_date": ["2026-11-02"] * 3,
            "model_version": [None, "V1", "V2"],
            "score": [1, 2, 3],
        }
    )
    resolver = DatasetResolver(
        AsyncMock(read=AsyncMock(return_value=document)),
        QueryServiceSettings(query_storage_scheme="s3"),
        AsyncMock(read_bound_dataframe=AsyncMock(return_value=checked)),
    )
    result = await resolver.resolve_many(
        [DatasetRef(dataset="signals", partition=partition)]
    )
    assert result[0].frame["model_version"].tolist() == ["V2"]
    assert result[0].frame["score"].tolist() == [3]


@pytest.mark.asyncio
async def test_catalog_preserves_discovery_and_hides_physical_storage_coordinates():
    from dataclasses import replace
    from unittest.mock import AsyncMock

    from app.storage import DatasetCatalogService

    document = await FakeMetadataReader().read()
    section = document.datasets[0]
    proof = replace(
        section.partitions[0],
        canonicalPath=section.partitions[0].path,
        storageVersionId="opaque-backend-version",
    )
    document = replace(document, datasets=[replace(section, partitions=[proof])])
    catalog = DatasetCatalogService(AsyncMock(read=AsyncMock(return_value=document)))
    datasets = await catalog.list_datasets()
    assert datasets[0]["name"] == "eod"
    assert datasets[0]["partitionCount"] == 1
    assert datasets[0]["partitionKeys"][0]["name"] == "exchange"
    page = await catalog.list_partitions("eod", offset=0, limit=1)
    assert page["total"] == 1
    assert page["items"][0]["dataVersion"] == HASH
    assert page["items"][0]["sourceExecutionId"] is None
    assert page["items"][0]["columns"] == [
        {"name": "code", "type": "VARCHAR", "nullable": True}
    ]
    assert not {"path", "canonicalPath", "storageVersionId"} & page["items"][0].keys()
    assert (await catalog.list_partitions("eod", offset=1, limit=1))["items"] == []
    with pytest.raises(ValueError, match="Unsupported dataset"):
        await catalog.list_partitions("unknown")


@pytest.mark.asyncio
async def test_catalog_options_filter_sort_deduplicate_and_bound_results():
    from dataclasses import replace
    from unittest.mock import AsyncMock

    from app.storage import DatasetCatalogService

    document = await FakeMetadataReader().read()
    section = document.datasets[0]
    partitions = [
        replace(section.partitions[0], values=values)
        for values in [
            {"exchange": "hose", "code": "BBB"},
            {"exchange": "hose", "code": "AAA"},
            {"exchange": "hnx", "code": "CCC"},
            {"exchange": "hose"},
        ]
    ]
    keys = [
        *section.partitionKeys,
        PartitionKeyDefinition("code", PartitionValueType.STRING, False, 1),
    ]
    document = replace(
        document, datasets=[replace(section, partitionKeys=keys, partitions=partitions)]
    )
    catalog = DatasetCatalogService(AsyncMock(read=AsyncMock(return_value=document)))
    assert await catalog.list_partition_options("eod", "exchange", {}) == [
        "hnx",
        "hose",
    ]
    assert await catalog.list_partition_options(
        "eod", "code", {"exchange": "hose"}, limit=1
    ) == ["AAA"]
    assert (
        await catalog.list_partition_options("eod", "code", {"exchange": "missing"})
        == []
    )
    for dataset, key, filters in [
        ("unknown", "exchange", {}),
        ("eod", "unknown", {}),
        ("eod", "exchange", {"unknown": "value"}),
    ]:
        with pytest.raises(ValueError, match="Unsupported|unsupported"):
            await catalog.list_partition_options(dataset, key, filters)


@pytest.mark.asyncio
@pytest.mark.parametrize("path", ["../outside.parquet", "/absolute.parquet"])
async def test_catalog_paths_cannot_escape_the_query_storage_boundary(path, tmp_path):
    from dataclasses import replace
    from unittest.mock import AsyncMock

    from py_common.storage.exceptions import ManifestInvalidError

    document = await FakeMetadataReader().read()
    section = document.datasets[0]
    with pytest.raises(ManifestInvalidError, match="safe relative object path"):
        document = replace(
            document,
            datasets=[
                replace(section, partitions=[replace(section.partitions[0], path=path)])
            ],
        )
        resolver = DatasetResolver(
            AsyncMock(read=AsyncMock(return_value=document)),
            QueryServiceSettings(
                query_storage_scheme="file", query_local_data_root=str(tmp_path)
            ),
        )
        await resolver.resolve_many(
            [DatasetRef(dataset="eod", partition={"exchange": "hose"})]
        )
