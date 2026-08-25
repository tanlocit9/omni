"""Unit tests for dataset metadata manifest operations."""

import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pandas as pd
import pytest

from py_common.storage.exceptions import (
    ManifestInvalidError,
    ManifestNotFoundError,
    ManifestUnsupportedSchemaVersionError,
    ManifestUnsupportedVersionError,
    StorageObjectNotFoundError,
)
from py_common.storage.manifest import (
    OMNI_DATASETS,
    ColumnMetadata,
    DatasetCatalog,
    DatasetDefinition,
    DatasetInput,
    DatasetManifest,
    ManifestReader,
    ManifestWriter,
    bootstrap_catalog,
    calculate_data_version,
    calculate_schema_hash,
    extract_schema_from_dataframe,
    extract_timestamp_range,
    publish_dataset_manifest,
)
from py_common.storage.providers import StorageProvider

# ------------------------------------------------------------------
# Fixtures
# ------------------------------------------------------------------


@pytest.fixture
def sample_dataframe():
    """Sample DataFrame for testing."""
    return pd.DataFrame(
        {
            "date": pd.to_datetime(["2026-08-18", "2026-08-19"]),
            "symbol": ["HPG", "VNM"],
            "close": [100.5, 200.3],
            "volume": [1000, 2000],
        }
    )


@pytest.fixture
def sample_columns():
    """Sample column metadata."""
    return [
        ColumnMetadata("date", "DATE", False),
        ColumnMetadata("symbol", "VARCHAR", False),
        ColumnMetadata("close", "DOUBLE", False),
        ColumnMetadata("volume", "BIGINT", False),
    ]


@pytest.fixture
def sample_manifest(sample_columns):
    """Sample dataset manifest."""
    return DatasetManifest(
        version=1,
        dataset="eod",
        partition={"exchange": "hose"},
        status="READY",
        path="eod/hose/*.parquet",
        dataVersion=f"sha256:{'a' * 64}",
        objectCount=1,
        totalBytes=1000,
        rowCount=100,
        columnCount=4,
        columns=sample_columns,
        schemaVersion=1,
        schemaHash=f"sha256:{'b' * 64}",
        generatedAt="2026-08-18T12:00:00Z",
        minTimestamp="2026-08-01T00:00:00Z",
        maxTimestamp="2026-08-31T23:59:59Z",
        inputs=[
            DatasetInput(
                dataset="raw-prices",
                partition={"exchange": "hose"},
                dataVersion=f"sha256:{'c' * 64}",
            )
        ],
        sourceExecutionId="exec-123",
    )


@pytest.fixture
def mock_registry():
    """Mock storage registry."""
    registry = MagicMock()
    return registry


@pytest.fixture
def mock_writable():
    """Mock writable storage port."""
    writable = AsyncMock()
    writable.write_bytes = AsyncMock()
    return writable


@pytest.fixture
def mock_readable():
    """Mock readable storage port."""
    readable = AsyncMock()
    readable.read_bytes = AsyncMock()
    return readable


@pytest.fixture
def manifest_writer(mock_registry, mock_writable):
    """ManifestWriter with mocked storage."""
    mock_registry.get_port.return_value = mock_writable
    return ManifestWriter(mock_registry, StorageProvider.MINIO, "stock-data")


@pytest.fixture
def manifest_reader(mock_registry, mock_readable):
    """ManifestReader with mocked storage."""
    mock_registry.get_port.return_value = mock_readable
    return ManifestReader(mock_registry, StorageProvider.MINIO, "stock-data")


# ------------------------------------------------------------------
# Schema Extraction Tests
# ------------------------------------------------------------------


def test_extract_schema_from_dataframe(sample_dataframe):
    """Schema should be extracted with correct types."""
    schema = extract_schema_from_dataframe(sample_dataframe)

    assert len(schema) == 4
    assert schema[0].name == "date"
    assert schema[0].type == "DATE"
    assert schema[1].name == "symbol"
    assert schema[1].type == "VARCHAR"
    assert schema[2].name == "close"
    assert schema[2].type == "DOUBLE"
    assert schema[3].name == "volume"
    assert schema[3].type == "BIGINT"


def test_extract_schema_handles_nullable():
    """Schema should detect nullable columns."""
    df = pd.DataFrame(
        {
            "required": [1, 2, 3],
            "optional": [1, None, 3],
        }
    )

    schema = extract_schema_from_dataframe(df)

    required_col = next(c for c in schema if c.name == "required")
    optional_col = next(c for c in schema if c.name == "optional")

    assert not required_col.nullable
    assert optional_col.nullable


def test_extract_schema_uses_event_timestamp_contract():
    frame = pd.DataFrame(
        {
            "generated_at": pd.to_datetime(["2026-08-25T00:00:00Z"], utc=True),
            "ma20_calculated_at": pd.to_datetime(["2026-08-25T00:00:00Z"], utc=True),
        }
    )

    schema = extract_schema_from_dataframe(frame)

    assert [column.type for column in schema] == [
        "TIMESTAMP_US_UTC",
        "TIMESTAMP_US_UTC",
    ]


def test_extract_timestamp_range_finds_date_column(sample_dataframe):
    """Timestamp range should be extracted from date column."""
    min_ts, max_ts = extract_timestamp_range(sample_dataframe)

    assert min_ts is not None
    assert max_ts is not None
    assert "2026-08-18" in min_ts
    assert "2026-08-19" in max_ts


def test_extract_timestamp_range_finds_bar_time_column():
    """Timestamp range should be extracted from bar_time column."""
    df = pd.DataFrame(
        {
            "bar_time": pd.to_datetime(["2026-08-18 09:00", "2026-08-18 15:00"]),
            "close": [100.0, 101.0],
        }
    )

    min_ts, max_ts = extract_timestamp_range(df)

    assert min_ts is not None
    assert "09:00" in min_ts


def test_extract_timestamp_range_returns_none_if_no_timestamp():
    """Timestamp range should be None if no timestamp column exists."""
    df = pd.DataFrame(
        {
            "symbol": ["HPG"],
            "close": [100.0],
        }
    )

    min_ts, max_ts = extract_timestamp_range(df)

    assert min_ts is None
    assert max_ts is None


# ------------------------------------------------------------------
# Hash Calculation Tests
# ------------------------------------------------------------------


def test_calculate_schema_hash_is_deterministic(sample_columns):
    """Schema hash should be identical for same columns."""
    hash1 = calculate_schema_hash(sample_columns)
    hash2 = calculate_schema_hash(sample_columns)

    assert hash1 == hash2
    assert hash1.startswith("sha256:")


def test_calculate_schema_hash_ignores_column_order():
    """Schema hash should be same regardless of column order."""
    columns1 = [
        ColumnMetadata("date", "TIMESTAMP", False),
        ColumnMetadata("close", "DOUBLE", False),
    ]

    columns2 = [
        ColumnMetadata("close", "DOUBLE", False),
        ColumnMetadata("date", "TIMESTAMP", False),
    ]

    hash1 = calculate_schema_hash(columns1)
    hash2 = calculate_schema_hash(columns2)

    assert hash1 == hash2


def test_calculate_schema_hash_changes_with_type():
    """Schema hash should change when column type changes."""
    columns1 = [ColumnMetadata("value", "DOUBLE", False)]
    columns2 = [ColumnMetadata("value", "BIGINT", False)]

    hash1 = calculate_schema_hash(columns1)
    hash2 = calculate_schema_hash(columns2)

    assert hash1 != hash2


def test_calculate_data_version_is_deterministic():
    """Data version should be identical for same inputs."""
    version1 = calculate_data_version(
        dataset="eod",
        partition={"exchange": "hose"},
        schema_hash="sha256:abc",
        object_checksums=[("eod/hose/hpg.parquet", '"etag-1"')],
    )

    version2 = calculate_data_version(
        dataset="eod",
        partition={"exchange": "hose"},
        schema_hash="sha256:abc",
        object_checksums=[("eod/hose/hpg.parquet", '"etag-1"')],
    )

    assert version1 == version2
    assert version1.startswith("sha256:")


def test_calculate_data_version_ignores_partition_order():
    """Data version should be same regardless of partition key order."""
    version1 = calculate_data_version(
        dataset="intraday",
        partition={"date": "2026-08-18", "exchange": "hose"},
        schema_hash="sha256:abc",
        object_checksums=[],
    )

    version2 = calculate_data_version(
        dataset="intraday",
        partition={"exchange": "hose", "date": "2026-08-18"},
        schema_hash="sha256:abc",
        object_checksums=[],
    )

    assert version1 == version2


def test_calculate_data_version_changes_with_content():
    """Data version should change when object checksums change."""
    version1 = calculate_data_version(
        dataset="eod",
        partition={"exchange": "hose"},
        schema_hash="sha256:abc",
        object_checksums=[("eod/hose/hpg.parquet", '"etag-1"')],
    )

    version2 = calculate_data_version(
        dataset="eod",
        partition={"exchange": "hose"},
        schema_hash="sha256:abc",
        object_checksums=[("eod/hose/hpg.parquet", '"etag-2"')],
    )

    assert version1 != version2


def test_calculate_data_version_changes_with_schema():
    """Data version should change when schema changes."""
    version1 = calculate_data_version(
        dataset="eod",
        partition={"exchange": "hose"},
        schema_hash="sha256:abc",
        object_checksums=[],
    )

    version2 = calculate_data_version(
        dataset="eod",
        partition={"exchange": "hose"},
        schema_hash="sha256:xyz",
        object_checksums=[],
    )

    assert version1 != version2


def test_calculate_data_version_ignores_lineage_order():
    """Equivalent lineage sets should produce the same identity."""
    first = DatasetInput("eod", {"exchange": "hose"}, f"sha256:{'a' * 64}")
    second = DatasetInput("symbols", {"exchange": "hose"}, f"sha256:{'b' * 64}")
    common = {
        "dataset": "indicators",
        "partition": {"exchange": "hose", "timeframe": "1d"},
        "schema_hash": f"sha256:{'c' * 64}",
        "object_checksums": [("indicators/hose/1d.parquet", f"sha256:{'d' * 64}")],
    }

    assert calculate_data_version(**common, inputs=[first, second]) == (
        calculate_data_version(**common, inputs=[second, first])
    )


def test_calculate_data_version_changes_with_lineage_version():
    """Changing an upstream dataVersion must invalidate downstream identity."""
    common = {
        "dataset": "indicators",
        "partition": {"exchange": "hose"},
        "schema_hash": f"sha256:{'c' * 64}",
        "object_checksums": [("indicators/hose.parquet", f"sha256:{'d' * 64}")],
    }
    old_input = DatasetInput("eod", {"exchange": "hose"}, f"sha256:{'a' * 64}")
    new_input = DatasetInput("eod", {"exchange": "hose"}, f"sha256:{'b' * 64}")

    assert calculate_data_version(**common, inputs=[old_input]) != (
        calculate_data_version(**common, inputs=[new_input])
    )


def test_generated_at_does_not_change_data_version(sample_columns):
    """Publication time is envelope metadata, not dataset content identity."""
    version = calculate_data_version(
        dataset="eod",
        partition={"exchange": "hose"},
        schema_hash=f"sha256:{'b' * 64}",
        object_checksums=[("eod/hose.parquet", f"sha256:{'a' * 64}")],
    )
    common = {
        "version": 1,
        "dataset": "eod",
        "partition": {"exchange": "hose"},
        "status": "READY",
        "path": "eod/hose.parquet",
        "dataVersion": version,
        "objectCount": 1,
        "totalBytes": 100,
        "rowCount": 1,
        "columnCount": len(sample_columns),
        "columns": sample_columns,
        "schemaVersion": 1,
        "schemaHash": f"sha256:{'b' * 64}",
    }

    earlier = DatasetManifest(**common, generatedAt="2026-08-20T00:00:00Z")
    later = DatasetManifest(**common, generatedAt="2026-08-21T00:00:00Z")

    assert earlier.dataVersion == later.dataVersion == version


# ------------------------------------------------------------------
# ManifestWriter Tests
# ------------------------------------------------------------------


@pytest.mark.asyncio
async def test_manifest_writer_builds_correct_path(manifest_writer):
    """Manifest path should identify the partition READY pointer."""
    path = manifest_writer._build_manifest_path("eod", {"exchange": "hose"})

    assert path == "_metadata/datasets/eod/exchange=hose/READY.json"


@pytest.mark.asyncio
async def test_manifest_writer_builds_path_with_multiple_partitions(
    manifest_writer,
):
    """Manifest path should sort partition keys."""
    path = manifest_writer._build_manifest_path(
        "intraday-bars",
        {"exchange": "hose", "date": "2026-08-18", "timeframe": "1m"},
    )

    assert path == (
        "_metadata/datasets/intraday-bars/date=2026-08-18/"
        "exchange=hose/timeframe=1m/READY.json"
    )


@pytest.mark.asyncio
async def test_manifest_writer_builds_path_for_no_partition(manifest_writer):
    """Manifest path should use the canonical empty-partition token."""
    path = manifest_writer._build_manifest_path("global-stats", {})

    assert path == "_metadata/datasets/global-stats/_default/READY.json"


@pytest.mark.asyncio
async def test_manifest_writer_serializes_correctly(manifest_writer, sample_manifest):
    """Manifest should serialize to valid JSON."""
    json_str = manifest_writer._serialize_manifest(sample_manifest)
    data = json.loads(json_str)

    assert data["version"] == 1
    assert data["dataset"] == "eod"
    assert data["partition"] == {"exchange": "hose"}
    assert data["status"] == "READY"
    assert data["dataVersion"] == f"sha256:{'a' * 64}"
    assert data["rowCount"] == 100
    assert len(data["columns"]) == 4
    assert len(data["inputs"]) == 1


@pytest.mark.asyncio
async def test_manifest_writer_serializes_nullable_fields_as_null(manifest_writer):
    """Persisted JSON should retain a stable shape for nullable fields."""
    manifest = DatasetManifest(
        version=1,
        dataset="test",
        partition={},
        status="READY",
        path="test/data.parquet",
        dataVersion=f"sha256:{'d' * 64}",
        objectCount=1,
        totalBytes=1000,
        rowCount=100,
        columnCount=0,
        columns=[],
        schemaVersion=1,
        schemaHash=f"sha256:{'e' * 64}",
        generatedAt="2026-08-18T12:00:00Z",
    )

    data = json.loads(manifest_writer._serialize_manifest(manifest))

    assert data["minTimestamp"] is None
    assert data["maxTimestamp"] is None
    assert data["sourceExecutionId"] is None
    assert data["inputs"] == []


@pytest.mark.asyncio
async def test_manifest_writer_publishes_immutable_then_ready(
    manifest_writer, mock_writable, sample_manifest
):
    """READY pointer must be the final object written."""
    await manifest_writer.write_manifest(sample_manifest)

    assert mock_writable.write_bytes.await_count == 2
    first, second = mock_writable.write_bytes.await_args_list
    assert first.kwargs["object_name"].endswith(f"/versions/{'a' * 64}.json")
    assert second.kwargs["object_name"].endswith("/READY.json")
    assert first.kwargs["data"] == second.kwargs["data"]


@pytest.mark.asyncio
async def test_manifest_writer_does_not_attempt_ready_when_immutable_write_fails(
    manifest_writer, mock_writable, sample_manifest
):
    """An immutable publication failure must stop before touching READY."""
    failure = RuntimeError("immutable write failed")
    mock_writable.write_bytes.side_effect = failure

    with pytest.raises(RuntimeError, match="immutable write failed"):
        await manifest_writer.write_manifest(sample_manifest)

    assert mock_writable.write_bytes.await_count == 1
    attempted_path = mock_writable.write_bytes.await_args.kwargs["object_name"]
    assert "/versions/" in attempted_path
    assert not attempted_path.endswith("/READY.json")


@pytest.mark.asyncio
async def test_manifest_writer_ready_failure_has_no_compensating_pointer_write(
    manifest_writer, mock_writable, sample_manifest
):
    """A failed READY replacement leaves the prior pointer untouched."""
    failure = RuntimeError("READY write failed")
    mock_writable.write_bytes.side_effect = [None, failure]

    with pytest.raises(RuntimeError, match="READY write failed"):
        await manifest_writer.write_manifest(sample_manifest)

    assert mock_writable.write_bytes.await_count == 2
    first, second = mock_writable.write_bytes.await_args_list
    assert "/versions/" in first.kwargs["object_name"]
    assert second.kwargs["object_name"].endswith("/READY.json")


@pytest.mark.asyncio
async def test_manifest_writer_writes_catalog(manifest_writer, mock_writable):
    """Catalog should be written to storage."""
    catalog = DatasetCatalog(
        version=1,
        datasets=[
            DatasetDefinition(
                name="eod",
                metadataPrefix="_metadata/datasets/eod/",
                dataPrefix="eod/",
            )
        ],
        lastUpdated="2026-08-18T12:00:00Z",
    )

    await manifest_writer.write_catalog(catalog)

    mock_writable.write_bytes.assert_called_once()
    call_args = mock_writable.write_bytes.call_args

    assert call_args.kwargs["object_name"] == "_metadata/catalog.json"


# ------------------------------------------------------------------
# ManifestReader Tests
# ------------------------------------------------------------------


def _canonical_manifest_payload() -> dict:
    return {
        "version": 1,
        "dataset": "eod",
        "partition": {"exchange": "hose"},
        "status": "READY",
        "path": "eod/hose/data.parquet",
        "dataVersion": f"sha256:{'a' * 64}",
        "objectCount": 1,
        "totalBytes": 100,
        "rowCount": 1,
        "columnCount": 1,
        "columns": [{"name": "date", "type": "TIMESTAMP", "nullable": False}],
        "schemaVersion": 1,
        "schemaHash": f"sha256:{'b' * 64}",
        "minTimestamp": None,
        "maxTimestamp": None,
        "inputs": [],
        "sourceExecutionId": None,
        "generatedAt": "2026-08-18T12:00:00Z",
    }


@pytest.mark.asyncio
async def test_manifest_reader_reads_manifest(
    manifest_reader, mock_readable, sample_manifest
):
    """Manifest should be read and deserialized correctly."""
    # Mock storage response
    manifest_json = json.dumps(
        {
            "version": 1,
            "dataset": "eod",
            "partition": {"exchange": "hose"},
            "status": "READY",
            "path": "eod/hose/*.parquet",
            "dataVersion": f"sha256:{'a' * 64}",
            "objectCount": 1,
            "totalBytes": 1000,
            "rowCount": 100,
            "columnCount": 2,
            "columns": [
                {"name": "date", "type": "TIMESTAMP", "nullable": False},
                {"name": "close", "type": "DOUBLE", "nullable": False},
            ],
            "schemaVersion": 1,
            "schemaHash": f"sha256:{'b' * 64}",
            "minTimestamp": None,
            "maxTimestamp": None,
            "inputs": [],
            "sourceExecutionId": None,
            "generatedAt": "2026-08-18T12:00:00Z",
            "futureField": {"ignored": True},
        }
    )
    mock_readable.read_bytes.return_value = manifest_json.encode("utf-8")

    manifest = await manifest_reader.read_manifest("eod", {"exchange": "hose"})

    assert manifest is not None
    assert manifest.dataset == "eod"
    assert manifest.partition == {"exchange": "hose"}
    assert manifest.status == "READY"
    assert manifest.rowCount == 100
    assert len(manifest.columns) == 2


@pytest.mark.asyncio
async def test_manifest_reader_reads_shared_canonical_fixture(
    manifest_reader, mock_readable
):
    """Python and Java readers should accept the same canonical V1 fixture."""
    fixture_path = Path(__file__).parent / "fixtures" / "sample-manifest-eod.json"
    mock_readable.read_bytes.return_value = fixture_path.read_bytes()

    manifest = await manifest_reader.read_manifest("eod", {"exchange": "hose"})

    assert manifest.dataset == "eod"
    assert manifest.objectCount == 3
    assert manifest.totalBytes == 1_048_576
    assert len(manifest.columns) == 15
    assert manifest.inputs == []


@pytest.mark.asyncio
async def test_manifest_reader_reads_shared_lineage_fixture(
    manifest_reader, mock_readable
):
    """Canonical derived-dataset fixture should preserve exact lineage."""
    fixture_path = (
        Path(__file__).parent / "fixtures" / "sample-manifest-indicators.json"
    )
    mock_readable.read_bytes.return_value = fixture_path.read_bytes()

    manifest = await manifest_reader.read_manifest(
        "indicators",
        {"source": "ad_close", "timeframe": "1d", "exchange": "hose", "code": "hpg"},
    )

    assert manifest.objectCount == 1
    assert manifest.totalBytes == 65_536
    assert len(manifest.inputs) == 1
    assert manifest.inputs[0].dataset == "eod"
    assert manifest.inputs[0].dataVersion == f"sha256:{'a' * 64}"


@pytest.mark.asyncio
async def test_manifest_reader_rejects_malformed_json(manifest_reader, mock_readable):
    mock_readable.read_bytes.return_value = b"{not-json"

    with pytest.raises(ManifestInvalidError, match="Invalid dataset manifest JSON"):
        await manifest_reader.read_manifest("eod", {"exchange": "hose"})


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("field", "value", "error_type"),
    [
        ("version", 2, ManifestUnsupportedVersionError),
        ("schemaVersion", 2, ManifestUnsupportedSchemaVersionError),
    ],
)
async def test_manifest_reader_rejects_unsupported_versions(
    manifest_reader, mock_readable, field, value, error_type
):
    payload = _canonical_manifest_payload()
    payload[field] = value
    mock_readable.read_bytes.return_value = json.dumps(payload).encode()

    with pytest.raises(error_type):
        await manifest_reader.read_manifest("eod", {"exchange": "hose"})


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("status", "UNKNOWN"),
        ("dataVersion", "sha256:invalid"),
        ("schemaHash", "sha256:invalid"),
        ("objectCount", 0),
        ("objectCount", "1"),
        ("dataset", "../eod"),
        ("partition", {"exchange": "../hose"}),
        ("path", "../eod/data.parquet"),
        ("columns", [{"name": "date", "type": "TIMESTAMP", "nullable": "no"}]),
        (
            "inputs",
            [
                {
                    "dataset": "eod",
                    "partition": {"exchange": "hose"},
                    "dataVersion": "sha256:invalid",
                }
            ],
        ),
    ],
)
async def test_manifest_reader_rejects_invalid_contract_fields(
    manifest_reader, mock_readable, field, value
):
    payload = _canonical_manifest_payload()
    payload[field] = value
    mock_readable.read_bytes.return_value = json.dumps(payload).encode()

    with pytest.raises(ManifestInvalidError):
        await manifest_reader.read_manifest("eod", {"exchange": "hose"})


@pytest.mark.asyncio
async def test_manifest_reader_raises_typed_not_found(manifest_reader, mock_readable):
    """Missing READY pointers should be distinct from storage failures."""
    mock_readable.read_bytes.side_effect = StorageObjectNotFoundError(
        bucket="stock-data",
        object_name="_metadata/datasets/eod/exchange=hose/READY.json",
    )

    with pytest.raises(ManifestNotFoundError):
        await manifest_reader.read_manifest("eod", {"exchange": "hose"})


@pytest.mark.asyncio
async def test_manifest_reader_reads_catalog(manifest_reader, mock_readable):
    """Catalog should be read correctly."""
    catalog_json = json.dumps(
        {
            "version": 1,
            "datasets": [
                {
                    "name": "eod",
                    "metadataPrefix": "_metadata/datasets/eod/",
                    "dataPrefix": "eod/",
                    "description": "End-of-day prices",
                }
            ],
            "lastUpdated": "2026-08-18T12:00:00Z",
        }
    )
    mock_readable.read_bytes.return_value = catalog_json.encode("utf-8")

    catalog = await manifest_reader.read_catalog()

    assert catalog.version == 1
    assert len(catalog.datasets) == 1
    assert catalog.datasets[0].name == "eod"


@pytest.mark.asyncio
async def test_manifest_reader_reads_shared_catalog_fixture(
    manifest_reader, mock_readable
):
    """Canonical catalog fixture should contain validated logical prefixes."""
    fixture_path = Path(__file__).parent / "fixtures" / "sample-catalog.json"
    mock_readable.read_bytes.return_value = fixture_path.read_bytes()

    catalog = await manifest_reader.read_catalog()

    assert len(catalog.datasets) == 11
    assert catalog.datasets[0].metadataPrefix == "_metadata/datasets/symbols/"
    assert catalog.datasets[0].dataPrefix == "symbols/"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "payload",
    [
        b"{not-json",
        json.dumps(
            {"version": 1, "datasets": "invalid", "lastUpdated": "now"}
        ).encode(),
        json.dumps(
            {
                "version": 1,
                "datasets": [
                    {
                        "name": "eod",
                        "metadataPrefix": "../metadata/",
                        "dataPrefix": "eod/",
                    }
                ],
                "lastUpdated": "now",
            }
        ).encode(),
    ],
)
async def test_manifest_reader_rejects_invalid_catalog(
    manifest_reader, mock_readable, payload
):
    mock_readable.read_bytes.return_value = payload

    with pytest.raises(ManifestInvalidError):
        await manifest_reader.read_catalog()


@pytest.mark.asyncio
async def test_manifest_reader_returns_empty_catalog_if_not_found(
    manifest_reader, mock_readable
):
    """Catalog should return empty catalog if not found."""
    mock_readable.read_bytes.side_effect = StorageObjectNotFoundError(
        bucket="stock-data",
        object_name="_metadata/catalog.json",
    )

    catalog = await manifest_reader.read_catalog()

    assert catalog.version == 1
    assert len(catalog.datasets) == 0


# ------------------------------------------------------------------
# High-Level Publishing Tests
# ------------------------------------------------------------------


@pytest.mark.asyncio
async def test_publish_dataset_manifest(
    manifest_writer, mock_writable, sample_dataframe
):
    """Publishing should create and write manifest with correct fields."""
    manifest = await publish_dataset_manifest(
        writer=manifest_writer,
        dataset="eod",
        partition={"exchange": "hose"},
        data_path="eod/hose/*.parquet",
        dataframe=sample_dataframe,
        object_checksums=[("eod/hose/data.parquet", "etag-1")],
        inputs=[],
        execution_id="exec-123",
        object_count=1,
        total_bytes=5000,
    )

    assert manifest.dataset == "eod"
    assert manifest.status == "READY"
    assert manifest.rowCount == 2
    assert manifest.columnCount == 4
    assert manifest.objectCount == 1
    assert manifest.totalBytes == 5000
    assert manifest.sourceExecutionId == "exec-123"
    assert len(manifest.columns) == 4

    assert mock_writable.write_bytes.await_count == 2


@pytest.mark.asyncio
async def test_publish_dataset_manifest_extracts_timestamps(
    manifest_writer, mock_writable, sample_dataframe
):
    """Publishing should extract timestamp range from DataFrame."""
    manifest = await publish_dataset_manifest(
        writer=manifest_writer,
        dataset="eod",
        partition={"exchange": "hose"},
        data_path="eod/hose/*.parquet",
        dataframe=sample_dataframe,
        object_checksums=[("eod/hose/data.parquet", "etag-1")],
    )

    assert manifest.minTimestamp is not None
    assert manifest.maxTimestamp is not None
    assert "2026-08-18" in manifest.minTimestamp
    assert "2026-08-19" in manifest.maxTimestamp


@pytest.mark.asyncio
async def test_publish_dataset_manifest_includes_lineage(
    manifest_writer, mock_writable, sample_dataframe
):
    """Publishing should include upstream inputs for lineage."""
    upstream_input = DatasetInput(
        dataset="raw-prices",
        partition={"exchange": "hose"},
        dataVersion=f"sha256:{'c' * 64}",
    )

    manifest = await publish_dataset_manifest(
        writer=manifest_writer,
        dataset="eod",
        partition={"exchange": "hose"},
        data_path="eod/hose/*.parquet",
        dataframe=sample_dataframe,
        object_checksums=[("eod/hose/data.parquet", "etag-1")],
        inputs=[upstream_input],
    )

    assert len(manifest.inputs) == 1
    assert manifest.inputs[0].dataset == "raw-prices"
    assert manifest.inputs[0].dataVersion == f"sha256:{'c' * 64}"


# ------------------------------------------------------------------
# Catalog Bootstrap Tests
# ------------------------------------------------------------------


@pytest.mark.asyncio
async def test_bootstrap_catalog(manifest_writer, mock_writable):
    """Bootstrap should create catalog with known Omni datasets."""
    catalog = await bootstrap_catalog(manifest_writer)

    assert catalog.version == 1
    assert len(catalog.datasets) == len(OMNI_DATASETS)

    dataset_names = [ds.name for ds in catalog.datasets]
    assert "eod" in dataset_names
    assert "indicators" in dataset_names
    assert "signals" in dataset_names

    # Should have written to storage
    mock_writable.write_bytes.assert_called_once()


@pytest.mark.asyncio
async def test_omni_datasets_have_required_fields():
    """OMNI_DATASETS should have all required fields."""
    for dataset in OMNI_DATASETS:
        assert dataset.name
        assert dataset.metadataPrefix
        assert dataset.dataPrefix
        assert dataset.metadataPrefix.startswith("_metadata/datasets/")
