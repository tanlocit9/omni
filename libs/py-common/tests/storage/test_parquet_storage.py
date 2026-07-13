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

import io
from datetime import date
from unittest.mock import AsyncMock, MagicMock

import pandas as pd
import pytest

from py_common.storage.exceptions import (
    ParquetDecodeError,
    StorageObjectNotFoundError,
    StorageWriteError,
)
from py_common.storage.parquet import ParquetCodec, ParquetStorage
from py_common.storage.ports import ReadableStorage, WritableStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_registry(readable: ReadableStorage, writable: WritableStorage) -> StorageProviderRegistry:
    """Build a registry backed by a fake adapter that exposes both ports."""

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
    registry.get_port.side_effect = lambda provider, port_type: (
        readable if port_type is ReadableStorage else writable
    )
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
        pd.testing.assert_frame_equal(df, decoded)
        assert decoded["date"].dtype == "datetime64[ns]"

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
        registry = MagicMock(spec=StorageProviderRegistry)
        registry.get_port.side_effect = lambda provider, port_type: (
            readable if port_type is ReadableStorage else writable
        )
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
        readable.read_bytes.assert_called_once_with("stock-data", "eod/hose/hpg.parquet")
        pd.testing.assert_frame_equal(result, df)

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
        pd.testing.assert_frame_equal(result, df)


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
        registry = MagicMock(spec=StorageProviderRegistry)
        registry.get_port.side_effect = lambda provider, port_type: (
            readable if port_type is ReadableStorage else writable
        )
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
        await storage.write_dataframe("eod/hose/hpg.parquet", df)

        writable.write_bytes.assert_called_once()
        call_kwargs = writable.write_bytes.call_args
        assert call_kwargs.kwargs["bucket"] == "stock-data"
        assert call_kwargs.kwargs["object_name"] == "eod/hose/hpg.parquet"
        assert call_kwargs.kwargs["content_type"] == "application/vnd.apache.parquet"

    @pytest.mark.asyncio
    async def test_write_dataframe_bytes_are_valid_parquet(
        self, storage: ParquetStorage, writable: AsyncMock
    ):
        df = pd.DataFrame({"close": [100.0, 101.5], "volume": [1000, 2000]})
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

        registry = MagicMock(spec=StorageProviderRegistry)
        registry.get_port.side_effect = lambda provider, port_type: (
            readable if port_type is ReadableStorage else writable
        )
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