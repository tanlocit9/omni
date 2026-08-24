"""Tests for ParquetCodec and ParquetStorage.

Covers:
- Round-trip DataFrame serialization (standard, empty, datetime columns)
- Column type preservation
- Missing-object semantics (read_dataframe raises, read_optional returns None)
- Corrupt-Parquet semantics (both methods raise ParquetDecodeError)
- Write failure propagation (StorageWriteError)
- ParquetDecodeError carries bucket/object_name context
"""

from __future__ import annotations

import hashlib
import io
from datetime import date
from unittest.mock import AsyncMock, MagicMock

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from py_common.storage.date_contracts import DateContractError
from py_common.storage.exceptions import (
    ParquetDecodeError,
    StorageObjectNotFoundError,
    StorageWriteError,
)
from py_common.storage.parquet import ParquetCodec, ParquetStorage
from py_common.storage.ports import (
    CopyableStorage,
    DeletableStorage,
    ReadableStorage,
    WritableStorage,
)
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_registry(
    readable: ReadableStorage,
    writable: WritableStorage,
    copyable: CopyableStorage | None = None,
    deletable: DeletableStorage | None = None,
) -> StorageProviderRegistry:
    """Build a registry backed by fake storage ports."""

    from py_common.storage.adapters.minio import MinioStorageAdapter

    adapter = MagicMock(spec=MinioStorageAdapter)
    adapter.provider = StorageProvider.MINIO
    adapter.is_active = True
    # Make isinstance checks work for port detection
    adapter.__class__ = type(
        "_FakeAdapter",
        (MinioStorageAdapter,),
        {},
    )

    registry = MagicMock(spec=StorageProviderRegistry)

    def _get_port(provider, port_type):
        if port_type is ReadableStorage:
            return readable
        if port_type is WritableStorage:
            return writable
        if port_type is CopyableStorage:
            return copyable or AsyncMock(spec=CopyableStorage)
        if port_type is DeletableStorage:
            return deletable or AsyncMock(spec=DeletableStorage)
        raise AssertionError(f"Unexpected port type: {port_type}")

    registry.get_port.side_effect = _get_port
    return registry


def _encode(df: pd.DataFrame) -> bytes:
    buf = io.BytesIO()
    df.to_parquet(buf, index=False)
    return buf.getvalue()


# ---------------------------------------------------------------------------
# ParquetCodec — unit tests (no I/O)
# ---------------------------------------------------------------------------


class TestParquetCodec:
    def test_round_trip_simple(self):
        df = pd.DataFrame({"a": [1, 2, 3], "b": ["x", "y", "z"]})
        encoded = ParquetCodec.encode(df)
        decoded = ParquetCodec.decode(encoded)
        pd.testing.assert_frame_equal(df, decoded)

    def test_empty_dataframe(self):
        df = pd.DataFrame({"a": pd.Series([], dtype="int64")})
        encoded = ParquetCodec.encode(df)
        decoded = ParquetCodec.decode(encoded)
        assert decoded.shape == (0, 1)
        assert list(decoded.columns) == ["a"]

    def test_datetime_column(self):
        df = pd.DataFrame(
            {
                "date": pd.to_datetime(["2024-01-01", "2024-01-02"]),
                "close": [100.0, 101.5],
            }
        )
        encoded = ParquetCodec.encode(df)
        decoded = ParquetCodec.decode(encoded)
        assert list(decoded["date"]) == [date(2024, 1, 1), date(2024, 1, 2)]
        assert pq.read_schema(io.BytesIO(encoded)).field("date").type == pa.date32()

    def test_date_object_column(self):
        df = pd.DataFrame(
            {
                "date": [date(2024, 1, 1), date(2024, 1, 2)],
                "value": [1, 2],
            }
        )
        encoded = ParquetCodec.encode(df)
        decoded = ParquetCodec.decode(encoded)
        # pyarrow converts date32 → object (Python date) on decode
        assert list(decoded["value"]) == [1, 2]

    def test_column_types_preserved(self):
        df = pd.DataFrame(
            {
                "int_col": pd.array([1, 2], dtype="int32"),
                "float_col": pd.array([1.1, 2.2], dtype="float64"),
                "str_col": ["a", "b"],
                "bool_col": [True, False],
            }
        )
        encoded = ParquetCodec.encode(df)
        decoded = ParquetCodec.decode(encoded)
        assert decoded["int_col"].dtype == "int32"
        assert decoded["float_col"].dtype == "float64"
        assert decoded["bool_col"].dtype == "bool"

    def test_index_excluded_by_default(self):
        df = pd.DataFrame({"v": [10, 20]}, index=[5, 6])
        encoded = ParquetCodec.encode(df, index=False)
        decoded = ParquetCodec.decode(encoded)
        # Default RangeIndex, not 5/6
        assert list(decoded.index) == [0, 1]

    def test_index_included_when_requested(self):
        df = pd.DataFrame({"v": [10, 20]}, index=[5, 6])
        encoded = ParquetCodec.encode(df, index=True)
        decoded = ParquetCodec.decode(encoded)
        assert list(decoded.index) == [5, 6]

    def test_encode_accepts_explicit_nested_schema(self):
        df = pd.DataFrame(
            {
                "date": pd.to_datetime(["2026-01-01"]),
                "contributors": [
                    [
                        {
                            "symbol": "MBB",
                            "weight": 0.5,
                            "return": 0.01,
                            "contribution": 0.005,
                            "contribution_share": 1.0,
                            "above_ma20": True,
                        }
                    ]
                ],
            }
        )
        schema = pa.schema(
            [
                pa.field("date", pa.timestamp("ns")),
                pa.field(
                    "contributors",
                    pa.list_(
                        pa.struct(
                            [
                                pa.field("symbol", pa.string()),
                                pa.field("weight", pa.float64()),
                                pa.field("return", pa.float64()),
                                pa.field("contribution", pa.float64()),
                                pa.field("contribution_share", pa.float64()),
                                pa.field("above_ma20", pa.bool_()),
                            ]
                        )
                    ),
                ),
            ]
        )

        encoded = ParquetCodec.encode(df, schema=schema)
        parquet_schema = pq.read_schema(io.BytesIO(encoded))
        contributor_type = parquet_schema.field("contributors").type.value_type

        assert [field.name for field in contributor_type] == [
            "symbol",
            "weight",
            "return",
            "contribution",
            "contribution_share",
            "above_ma20",
        ]
        assert parquet_schema.field("date").type == pa.date32()

    def test_event_timestamps_are_microsecond_utc(self):
        df = pd.DataFrame(
            {
                "generated_at": ["2026-08-25T03:00:00+07:00"],
                "actual_updated_at": [pd.Timestamp("2026-08-24T20:00:00Z")],
            }
        )

        encoded = ParquetCodec.encode(df)
        schema = pq.read_schema(io.BytesIO(encoded))
        decoded = ParquetCodec.decode(encoded)

        assert schema.field("generated_at").type == pa.timestamp("us", tz="UTC")
        assert schema.field("actual_updated_at").type == pa.timestamp(
            "us", tz="UTC"
        )
        assert decoded.loc[0, "generated_at"] == pd.Timestamp("2026-08-24T20:00:00Z")

    def test_decode_normalizes_legacy_timestamp_business_date(self):
        legacy = pa.table(
            {
                "date": pa.array(
                    [pd.Timestamp("2026-08-25T12:30:00")],
                    type=pa.timestamp("ns"),
                )
            }
        )
        buffer = io.BytesIO()
        pq.write_table(legacy, buffer)

        decoded = ParquetCodec.decode(buffer.getvalue())

        assert decoded.loc[0, "date"] == date(2026, 8, 25)

    def test_invalid_semantic_date_is_rejected(self):
        with pytest.raises(DateContractError, match="business date"):
            ParquetCodec.encode(pd.DataFrame({"signal_date": ["not-a-date"]}))

    def test_corrupt_bytes_raises_decode_error(self):
        with pytest.raises(ParquetDecodeError) as exc_info:
            ParquetCodec.decode(b"not parquet bytes")
        assert exc_info.value.object_name is None  # no context at codec level

    def test_decode_error_carries_cause(self):
        with pytest.raises(ParquetDecodeError) as exc_info:
            ParquetCodec.decode(b"\x00\x01\x02")
        assert exc_info.value.cause is not None


# ---------------------------------------------------------------------------
# ParquetStorage — async tests
# ---------------------------------------------------------------------------


class TestParquetStorageRead:
    """Tests for read_dataframe and read_optional_dataframe."""

    @pytest.fixture()
    def df(self) -> pd.DataFrame:
        return pd.DataFrame({"date": ["2024-01-01"], "close": [100.0]})

    @pytest.fixture()
    def readable(self, df: pd.DataFrame) -> AsyncMock:
        mock = AsyncMock(spec=ReadableStorage)
        mock.read_bytes.return_value = _encode(df)
        return mock

    @pytest.fixture()
    def writable(self) -> AsyncMock:
        return AsyncMock(spec=WritableStorage)

    @pytest.fixture()
    def storage(self, readable: AsyncMock, writable: AsyncMock) -> ParquetStorage:
        registry = _make_registry(readable, writable)
        return ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket="stock-data",
        )

    @pytest.mark.asyncio
    async def test_read_dataframe_success(
        self, storage: ParquetStorage, readable: AsyncMock, df: pd.DataFrame
    ):
        result = await storage.read_dataframe("eod/hose/hpg.parquet")
        readable.read_bytes.assert_called_once_with(
            "stock-data", "eod/hose/hpg.parquet"
        )
        assert result.loc[0, "date"] == date(2024, 1, 1)
        assert result.loc[0, "close"] == df.loc[0, "close"]

    @pytest.mark.asyncio
    async def test_read_dataframe_not_found_raises(
        self, storage: ParquetStorage, readable: AsyncMock
    ):
        readable.read_bytes.side_effect = StorageObjectNotFoundError(
            "stock-data", "eod/hose/missing.parquet"
        )
        with pytest.raises(StorageObjectNotFoundError):
            await storage.read_dataframe("eod/hose/missing.parquet")

    @pytest.mark.asyncio
    async def test_read_dataframe_corrupt_raises_with_context(
        self, storage: ParquetStorage, readable: AsyncMock
    ):
        readable.read_bytes.return_value = b"not-parquet"
        with pytest.raises(ParquetDecodeError) as exc_info:
            await storage.read_dataframe("eod/hose/bad.parquet")
        # object_name context should be added by ParquetStorage
        assert "stock-data/eod/hose/bad.parquet" in str(exc_info.value.object_name)

    @pytest.mark.asyncio
    async def test_read_optional_returns_none_when_missing(
        self, storage: ParquetStorage, readable: AsyncMock
    ):
        readable.read_bytes.side_effect = StorageObjectNotFoundError(
            "stock-data", "eod/hose/new.parquet"
        )
        result = await storage.read_optional_dataframe("eod/hose/new.parquet")
        assert result is None

    @pytest.mark.asyncio
    async def test_read_optional_corrupt_still_raises(
        self, storage: ParquetStorage, readable: AsyncMock
    ):
        readable.read_bytes.return_value = b"garbage"
        with pytest.raises(ParquetDecodeError):
            await storage.read_optional_dataframe("eod/hose/bad.parquet")

    @pytest.mark.asyncio
    async def test_read_optional_success(
        self, storage: ParquetStorage, df: pd.DataFrame
    ):
        result = await storage.read_optional_dataframe("eod/hose/hpg.parquet")
        assert result is not None
        assert result.loc[0, "date"] == date(2024, 1, 1)
        assert result.loc[0, "close"] == df.loc[0, "close"]


class TestParquetStorageWrite:
    """Tests for write_dataframe."""

    @pytest.fixture()
    def readable(self) -> AsyncMock:
        return AsyncMock(spec=ReadableStorage)

    @pytest.fixture()
    def writable(self) -> AsyncMock:
        return AsyncMock(spec=WritableStorage)

    @pytest.fixture()
    def storage(self, readable: AsyncMock, writable: AsyncMock) -> ParquetStorage:
        registry = _make_registry(readable, writable)
        return ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket="stock-data",
        )

    @pytest.mark.asyncio
    async def test_write_dataframe_calls_write_bytes(
        self, storage: ParquetStorage, writable: AsyncMock
    ):
        df = pd.DataFrame({"a": [1, 2], "b": [3.0, 4.0]})
        result = await storage.write_dataframe("eod/hose/hpg.parquet", df)

        writable.write_bytes.assert_called_once()
        call_kwargs = writable.write_bytes.call_args
        written_bytes = call_kwargs.kwargs["data"]
        assert call_kwargs.kwargs["bucket"] == "stock-data"
        assert call_kwargs.kwargs["object_name"] == "eod/hose/hpg.parquet"
        assert call_kwargs.kwargs["content_type"] == "application/vnd.apache.parquet"
        assert result.object_name == "eod/hose/hpg.parquet"
        assert result.checksum == f"sha256:{hashlib.sha256(written_bytes).hexdigest()}"
        assert result.total_bytes == len(written_bytes)
        assert result.temporary_object_name is None

    @pytest.mark.asyncio
    async def test_write_dataframe_bytes_are_valid_parquet(
        self, storage: ParquetStorage, writable: AsyncMock
    ):
        df = pd.DataFrame({"close": [100.0, 101.5], "nm_volume": [1000, 2000]})
        await storage.write_dataframe("test/out.parquet", df)

        written_bytes = writable.write_bytes.call_args.kwargs["data"]
        # Bytes should decode back to equivalent DataFrame
        decoded = pd.read_parquet(io.BytesIO(written_bytes))
        pd.testing.assert_frame_equal(df, decoded)

    @pytest.mark.asyncio
    async def test_write_dataframe_write_failure_propagates(
        self, storage: ParquetStorage, writable: AsyncMock
    ):
        writable.write_bytes.side_effect = StorageWriteError(
            "stock-data", "eod/fail.parquet", RuntimeError("network error")
        )
        df = pd.DataFrame({"x": [1]})
        with pytest.raises(StorageWriteError):
            await storage.write_dataframe("eod/fail.parquet", df)

    @pytest.mark.asyncio
    async def test_write_empty_dataframe(
        self, storage: ParquetStorage, writable: AsyncMock
    ):
        df = pd.DataFrame({"a": pd.Series([], dtype="float64")})
        await storage.write_dataframe("empty.parquet", df)
        writable.write_bytes.assert_called_once()

    @pytest.mark.asyncio
    async def test_write_respects_index_flag(
        self, storage: ParquetStorage, writable: AsyncMock
    ):
        df = pd.DataFrame({"v": [10, 20]}, index=[5, 6])
        await storage.write_dataframe("test/indexed.parquet", df, index=True)

        written_bytes = writable.write_bytes.call_args.kwargs["data"]
        decoded = pd.read_parquet(io.BytesIO(written_bytes))
        assert list(decoded.index) == [5, 6]

    @pytest.mark.asyncio
    async def test_write_default_excludes_index(
        self, storage: ParquetStorage, writable: AsyncMock
    ):
        df = pd.DataFrame({"v": [10, 20]}, index=[5, 6])
        await storage.write_dataframe("test/no_index.parquet", df)

        written_bytes = writable.write_bytes.call_args.kwargs["data"]
        decoded = pd.read_parquet(io.BytesIO(written_bytes))
        # Default RangeIndex should be restored
        assert list(decoded.index) == [0, 1]


class TestParquetStorageReplace:
    """Tests for best-effort Parquet replacement."""

    @pytest.fixture()
    def df(self) -> pd.DataFrame:
        return pd.DataFrame({"date": ["2024-01-01"], "close": [100.0]})

    @pytest.fixture()
    def readable(self, df: pd.DataFrame) -> AsyncMock:
        mock = AsyncMock(spec=ReadableStorage)
        mock.read_bytes.return_value = _encode(df)
        return mock

    @pytest.fixture()
    def writable(self) -> AsyncMock:
        return AsyncMock(spec=WritableStorage)

    @pytest.fixture()
    def copyable(self) -> AsyncMock:
        return AsyncMock(spec=CopyableStorage)

    @pytest.fixture()
    def deletable(self) -> AsyncMock:
        return AsyncMock(spec=DeletableStorage)

    @pytest.fixture()
    def storage(
        self,
        readable: AsyncMock,
        writable: AsyncMock,
        copyable: AsyncMock,
        deletable: AsyncMock,
    ) -> ParquetStorage:
        registry = _make_registry(readable, writable, copyable, deletable)
        return ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket="stock-data",
        )

    @pytest.mark.asyncio
    async def test_replace_dataframe_writes_validates_copies_and_deletes(
        self,
        storage: ParquetStorage,
        writable: AsyncMock,
        copyable: AsyncMock,
        deletable: AsyncMock,
        df: pd.DataFrame,
    ):
        validator = MagicMock()

        result = await storage.replace_dataframe(
            "indicators/1d/hose/hpg.parquet",
            df,
            temp_object_name="indicators/1d/hose/.tmp/hpg.tmp",
            validate=validator,
        )

        written_bytes = writable.write_bytes.call_args.kwargs["data"]
        assert result.object_name == "indicators/1d/hose/hpg.parquet"
        assert result.checksum == f"sha256:{hashlib.sha256(written_bytes).hexdigest()}"
        assert result.total_bytes == len(written_bytes)
        assert result.temporary_object_name == "indicators/1d/hose/.tmp/hpg.tmp"
        writable.write_bytes.assert_called_once()
        copyable.copy_object.assert_awaited_once_with(
            bucket="stock-data",
            source_object_name="indicators/1d/hose/.tmp/hpg.tmp",
            target_object_name="indicators/1d/hose/hpg.parquet",
            content_type="application/vnd.apache.parquet",
        )
        deletable.delete.assert_awaited_once_with(
            "stock-data", "indicators/1d/hose/.tmp/hpg.tmp"
        )
        validator.assert_called_once()

    @pytest.mark.asyncio
    async def test_replace_dataframe_cleans_temp_when_validation_fails(
        self,
        storage: ParquetStorage,
        copyable: AsyncMock,
        deletable: AsyncMock,
        df: pd.DataFrame,
    ):
        def fail_validation(_: pd.DataFrame) -> None:
            raise ValueError("bad schema")

        with pytest.raises(ValueError, match="bad schema"):
            await storage.replace_dataframe(
                "indicators/1d/hose/hpg.parquet",
                df,
                temp_object_name="indicators/1d/hose/.tmp/hpg.tmp",
                validate=fail_validation,
            )

        copyable.copy_object.assert_not_called()
        deletable.delete.assert_awaited_once_with(
            "stock-data", "indicators/1d/hose/.tmp/hpg.tmp"
        )

    @pytest.mark.asyncio
    async def test_replace_dataframe_swallows_cleanup_failure_after_copy(
        self,
        storage: ParquetStorage,
        copyable: AsyncMock,
        deletable: AsyncMock,
        df: pd.DataFrame,
    ):
        deletable.delete.side_effect = RuntimeError("denied")

        result = await storage.replace_dataframe(
            "indicators/1d/hose/hpg.parquet",
            df,
            temp_object_name="indicators/1d/hose/.tmp/hpg.tmp",
        )

        assert result.temporary_object_name == "indicators/1d/hose/.tmp/hpg.tmp"
        copyable.copy_object.assert_awaited_once()
        deletable.delete.assert_awaited_once_with(
            "stock-data", "indicators/1d/hose/.tmp/hpg.tmp"
        )


class TestParquetDecodeErrorContext:
    """Verify ParquetDecodeError carries correct context at each raise site."""

    def test_codec_raises_without_object_name(self):
        with pytest.raises(ParquetDecodeError) as exc_info:
            ParquetCodec.decode(b"bad")
        assert exc_info.value.object_name is None

    @pytest.mark.asyncio
    async def test_storage_adds_bucket_and_path(self):
        readable = AsyncMock(spec=ReadableStorage)
        readable.read_bytes.return_value = b"bad"
        writable = AsyncMock(spec=WritableStorage)

        registry = _make_registry(readable, writable)
        storage = ParquetStorage(
            registry=registry,
            provider=StorageProvider.MINIO,
            bucket="my-bucket",
        )

        with pytest.raises(ParquetDecodeError) as exc_info:
            await storage.read_dataframe("folder/file.parquet")

        assert exc_info.value.object_name == "my-bucket/folder/file.parquet"
        # cause should be the original exception from the codec
        assert exc_info.value.cause is not None
