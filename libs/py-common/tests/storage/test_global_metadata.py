import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest

from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.global_metadata import (
    GLOBAL_METADATA_PATH,
    GlobalColumnMetadata,
    GlobalDatasetMetadata,
    GlobalMetadataDocument,
    GlobalMetadataWriter,
    GlobalPartitionMetadata,
)


def _partition(values: dict[str, str], *, digest: str = "a") -> GlobalPartitionMetadata:
    return GlobalPartitionMetadata(
        values=values,
        status="READY",
        path=f"eod/{values['exchange']}/{values['code']}.parquet",
        dataVersion=f"sha256:{digest * 64}",
        schemaVersion=1,
        schemaHash=f"sha256:{'b' * 64}",
        objectCount=1,
        totalBytes=100,
        rowCount=2,
        columnCount=1,
        columns=[GlobalColumnMetadata(name="date", type="DATE", nullable=False)],
        generatedAt="2026-09-01T13:00:00Z",
    )


def _document() -> GlobalMetadataDocument:
    definition = OMNI_DATASET_REGISTRY.get("eod")
    return GlobalMetadataDocument(
        version=1,
        generatedAt="2026-09-01T13:00:00Z",
        sourceExecutionId="execution-1",
        datasets=[
            GlobalDatasetMetadata(
                name=definition.name,
                label=definition.label,
                dataPrefix=definition.data_prefix,
                partitionKeys=list(definition.partition_keys),
                partitions=[
                    _partition({"code": "vnm", "exchange": "hose"}, digest="c"),
                    _partition({"exchange": "hose", "code": "hpg"}),
                ],
            )
        ],
    )


def test_global_document_round_trip_is_deterministic_and_indexed() -> None:
    document = _document()

    encoded = document.to_json()
    decoded = GlobalMetadataDocument.from_json(encoded)

    assert decoded.to_json() == encoded
    assert [item.values["code"] for item in decoded.datasets[0].partitions] == [
        "hpg",
        "vnm",
    ]
    assert decoded.resolve("eod", {"exchange": "hose", "code": "hpg"}) is not None
    assert json.loads(encoded)["datasets"][0]["partitionKeys"][0]["name"] == "exchange"


@pytest.mark.parametrize(
    "version_id", ["", "null", " leading", "tab\t", "vnđ", "a" * 1025, 12, False, []]
)
def test_invalid_physical_version_is_rejected_by_model_and_discovery_json(version_id):
    document = _document()
    with pytest.raises(ManifestInvalidError, match="Invalid immutable storage version"):
        replace(document.datasets[0].partitions[0], storageVersionId=version_id)

    payload = json.loads(document.to_json())
    payload["datasets"][0]["partitions"][0]["storageVersionId"] = version_id
    with pytest.raises(ManifestInvalidError, match="Invalid immutable storage version"):
        GlobalMetadataDocument.from_json(json.dumps(payload))


@pytest.mark.parametrize("version_id", [None, "!", "~" * 1024])
def test_physical_version_round_trip_preserves_logical_identity_and_legacy_shape(
    version_id,
):
    original = _document()
    partition = replace(original.datasets[0].partitions[0], storageVersionId=version_id)
    document = replace(
        original, datasets=[replace(original.datasets[0], partitions=[partition])]
    )
    payload = json.loads(document.to_json())["datasets"][0]["partitions"][0]
    assert ("storageVersionId" in payload) is (version_id is not None)
    decoded = GlobalMetadataDocument.from_json(document.to_json()).resolve(
        "eod", partition.values
    )
    assert decoded.storageVersionId == version_id
    assert decoded.dataVersion == partition.dataVersion
    assert decoded.path == partition.path
    assert decoded.values == partition.values


@pytest.mark.parametrize(
    "values",
    [
        {"exchange": "hose"},
        {"exchange": "hose", "code": "hpg", "path": "forbidden"},
    ],
)
def test_registry_rejects_partial_and_unknown_exact_partition_keys(values) -> None:
    with pytest.raises(ManifestInvalidError):
        OMNI_DATASET_REGISTRY.get("eod").normalize_partition(values)


def test_global_document_rejects_duplicate_partition_identity() -> None:
    definition = OMNI_DATASET_REGISTRY.get("eod")

    with pytest.raises(ManifestInvalidError, match="duplicate partition"):
        GlobalDatasetMetadata(
            name=definition.name,
            label=definition.label,
            dataPrefix=definition.data_prefix,
            partitionKeys=list(definition.partition_keys),
            partitions=[
                _partition({"exchange": "hose", "code": "hpg"}),
                _partition({"code": "hpg", "exchange": "hose"}, digest="c"),
            ],
        )


@pytest.mark.asyncio
async def test_writer_replaces_one_object_and_validates_read_back() -> None:
    document = _document()
    writable = AsyncMock()
    readable = AsyncMock()
    readable.read_bytes.return_value = document.to_json().encode()

    await GlobalMetadataWriter(writable, readable, "stock-data").replace(document)

    writable.write_bytes.assert_awaited_once()
    assert writable.write_bytes.await_args.kwargs["object_name"] == GLOBAL_METADATA_PATH
    readable.read_bytes.assert_awaited_once_with("stock-data", GLOBAL_METADATA_PATH)


@pytest.mark.asyncio
async def test_writer_reports_mismatched_read_back() -> None:
    document = _document()
    mismatched = GlobalMetadataDocument(
        version=1,
        generatedAt="2026-09-01T14:00:00Z",
        datasets=document.datasets,
    )
    readable = AsyncMock()
    readable.read_bytes.return_value = mismatched.to_json().encode()

    with pytest.raises(ManifestInvalidError, match="read-back"):
        await GlobalMetadataWriter(AsyncMock(), readable, "stock-data").replace(
            document
        )
