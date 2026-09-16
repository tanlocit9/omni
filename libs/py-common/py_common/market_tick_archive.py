"""Deterministic provider-neutral archive processing for canonical market ticks."""

from __future__ import annotations

import hashlib
import io
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

from py_common.intraday_reconciliation import (
    CLOSE_THRESHOLD,
    VOLUME_VALUE_THRESHOLD,
    ReconciliationDifference,
    ReconciliationStatus,
    ReconciliationThreshold,
    compare_reconciliation_value,
)
from py_common.market_ticks import MarketTick, replay_market_ticks
from py_common.storage.immutable_publication import (
    ImmutableDatasetPublisher,
    ImmutablePublicationResult,
)

_TICK_COLUMNS = (
    "schema_version",
    "event_id",
    "source",
    "exchange",
    "symbol",
    "trading_date",
    "market_timestamp",
    "received_at",
    "price",
    "volume",
    "trade_id",
    "sequence",
)
_TICK_SCHEMA = pa.schema(
    [
        pa.field("schema_version", pa.int8(), nullable=False),
        pa.field("event_id", pa.string(), nullable=False),
        pa.field("source", pa.string(), nullable=False),
        pa.field("exchange", pa.string(), nullable=False),
        pa.field("symbol", pa.string(), nullable=False),
        pa.field("trading_date", pa.date32(), nullable=False),
        pa.field("market_timestamp", pa.timestamp("us", tz="UTC"), nullable=False),
        pa.field("received_at", pa.timestamp("us", tz="UTC"), nullable=False),
        pa.field("price", pa.string(), nullable=False),
        pa.field("volume", pa.string(), nullable=False),
        pa.field("trade_id", pa.string()),
        pa.field("sequence", pa.int64()),
    ]
)
_COUNT_THRESHOLD = ReconciliationThreshold(
    warning_relative=VOLUME_VALUE_THRESHOLD.warning_relative,
    rejection_relative=VOLUME_VALUE_THRESHOLD.rejection_relative,
    absolute_tick=Decimal("0"),
)


@dataclass(frozen=True, order=True)
class TickPartition:
    """Exact identity of one source/exchange/symbol UTC trading-date partition."""

    source: str
    exchange: str
    symbol: str
    trading_date: date

    @classmethod
    def from_tick(cls, tick: MarketTick) -> TickPartition:
        return cls(
            tick.source,
            tick.exchange,
            tick.symbol,
            tick.market_timestamp.date(),
        )


@dataclass(frozen=True)
class TickArchiveBatch:
    """One bounded, content-addressed immutable archive publication candidate."""

    partition: TickPartition
    ticks: tuple[MarketTick, ...]
    parquet: bytes
    part_id: str


@dataclass(frozen=True)
class CompletedSessionReconciliation:
    """Comparison of a rebuilt tick session with one P9 normalized-trades partition."""

    tick_count: ReconciliationDifference
    trade_count: ReconciliationDifference
    total_volume: ReconciliationDifference
    total_trade_value: ReconciliationDifference
    open: ReconciliationDifference
    close: ReconciliationDifference

    @property
    def status(self) -> ReconciliationStatus:
        statuses = {
            self.tick_count.status,
            self.trade_count.status,
            self.total_volume.status,
            self.total_trade_value.status,
            self.open.status,
            self.close.status,
        }
        if ReconciliationStatus.REJECTED in statuses:
            return ReconciliationStatus.REJECTED
        if ReconciliationStatus.WARNING in statuses:
            return ReconciliationStatus.WARNING
        return ReconciliationStatus.READY


def ticks_to_frame(ticks: Iterable[MarketTick]) -> pd.DataFrame:
    """Return canonical replay rows with exact decimals represented as strings."""
    ordered = replay_market_ticks(ticks)
    rows = [
        {
            "schema_version": tick.schema_version,
            "event_id": tick.event_id,
            "source": tick.source,
            "exchange": tick.exchange,
            "symbol": tick.symbol,
            "trading_date": tick.market_timestamp.date(),
            "market_timestamp": tick.market_timestamp,
            "received_at": tick.received_at,
            "price": _decimal_text(tick.price),
            "volume": _decimal_text(tick.volume),
            "trade_id": tick.trade_id,
            "sequence": tick.sequence,
        }
        for tick in ordered
    ]
    if not rows:
        return _empty_tick_frame()
    frame = pd.DataFrame(rows, columns=_TICK_COLUMNS)
    frame["schema_version"] = frame["schema_version"].astype("int8")
    frame["market_timestamp"] = pd.to_datetime(frame["market_timestamp"], utc=True)
    frame["received_at"] = pd.to_datetime(frame["received_at"], utc=True)
    frame["sequence"] = pd.array(frame["sequence"], dtype="Int64")
    return frame


def encode_tick_parquet(ticks: Iterable[MarketTick]) -> bytes:
    """Encode canonical ticks to stable Parquet bytes independent of input order."""
    frame = ticks_to_frame(ticks)
    table = pa.Table.from_pandas(
        frame,
        schema=_TICK_SCHEMA,
        preserve_index=False,
        safe=True,
    ).replace_schema_metadata(None)
    buffer = io.BytesIO()
    pq.write_table(
        table,
        buffer,
        compression="zstd",
        compression_level=9,
        use_dictionary=False,
        write_statistics=True,
        data_page_version="2.0",
        version="2.6",
    )
    return buffer.getvalue()


def decode_tick_parquet(data: bytes) -> pd.DataFrame:
    """Decode and strictly validate one canonical archive part."""
    table = pq.read_table(io.BytesIO(data))
    if not table.schema.remove_metadata().equals(_TICK_SCHEMA):
        raise ValueError("Tick archive Parquet schema is not canonical")
    frame = table.to_pandas()
    return _normalize_archive_frame(frame)


def assemble_tick_batches(
    ticks: Iterable[MarketTick], *, max_ticks: int
) -> tuple[TickArchiveBatch, ...]:
    """Plan deterministic non-empty bounded batches for exactly one partition."""
    if max_ticks < 1:
        raise ValueError("max_ticks must be greater than zero")
    ordered = replay_market_ticks(ticks)
    partition = _partition_for_ticks(ordered)
    batches = []
    for offset in range(0, len(ordered), max_ticks):
        chunk = ordered[offset : offset + max_ticks]
        parquet = encode_tick_parquet(chunk)
        batches.append(
            TickArchiveBatch(
                partition=partition,
                ticks=chunk,
                parquet=parquet,
                part_id=hashlib.sha256(parquet).hexdigest(),
            )
        )
    return tuple(batches)


async def publish_tick_batch(
    publisher: ImmutableDatasetPublisher,
    batch: TickArchiveBatch,
    *,
    partition_prefix: str,
) -> ImmutablePublicationResult:
    """Publish one batch through the existing version-before-READY boundary."""

    async def validate(persisted: bytes) -> None:
        decoded = decode_tick_parquet(persisted)
        _require_frame_partition(decoded, batch.partition)
        if tuple(decoded["event_id"]) != tuple(tick.event_id for tick in batch.ticks):
            raise ValueError("Persisted tick archive identities differ from candidate")

    return await publisher.publish(
        partition_prefix=partition_prefix,
        object_name=f"part-{batch.part_id}.parquet",
        data=batch.parquet,
        manifest={
            "dataset": "realtime-tick-archive",
            "partition": _partition_manifest(batch.partition),
            "partId": batch.part_id,
            "rowCount": len(batch.ticks),
        },
        validate_data=validate,
    )


def compact_tick_parts(parts: Iterable[bytes]) -> pd.DataFrame:
    """Rebuild finite parts, enforce one partition, deduplicate, and stably sort."""
    decoded = [decode_tick_parquet(part) for part in parts]
    if not decoded:
        raise ValueError("At least one tick archive part is required")
    if any(frame.empty for frame in decoded):
        raise ValueError("Tick archive parts must not be empty")
    partition = _partition_for_frame(decoded[0])
    for frame in decoded[1:]:
        _require_frame_partition(frame, partition)
    combined = pd.concat(decoded, ignore_index=True)
    conflicts = combined.groupby("event_id", sort=False)[list(_TICK_COLUMNS)].nunique(
        dropna=False
    )
    if (conflicts > 1).any(axis=None):
        raise ValueError("Conflicting duplicate tick eventId")
    compacted = combined.drop_duplicates("event_id", keep="first")
    return _sort_tick_frame(compacted)


def build_one_minute_bars(ticks: pd.DataFrame) -> pd.DataFrame:
    """Build deterministic UTC event-time OHLCV/value bars from finite ticks."""
    normalized = _normalize_archive_frame(ticks)
    if normalized.empty:
        raise ValueError("Cannot build bars from an empty tick set")
    partition = _partition_for_frame(normalized)
    ordered = _sort_tick_frame(normalized)
    ordered["minute"] = ordered["market_timestamp"].dt.floor("min")
    ordered["price_decimal"] = ordered["price"].map(Decimal)
    ordered["volume_decimal"] = ordered["volume"].map(Decimal)
    rows = []
    for minute, group in ordered.groupby("minute", sort=True):
        prices = tuple(group["price_decimal"])
        volumes = tuple(group["volume_decimal"])
        rows.append(
            {
                "source": partition.source,
                "exchange": partition.exchange,
                "symbol": partition.symbol,
                "trading_date": partition.trading_date,
                "bar_time": minute,
                "open": _decimal_text(prices[0]),
                "high": _decimal_text(max(prices)),
                "low": _decimal_text(min(prices)),
                "close": _decimal_text(prices[-1]),
                "volume": _decimal_text(sum(volumes, Decimal("0"))),
                "trade_value": _decimal_text(
                    sum(
                        (
                            price * volume
                            for price, volume in zip(prices, volumes, strict=True)
                        ),
                        Decimal("0"),
                    )
                ),
                "tick_count": len(group),
            }
        )
    return pd.DataFrame(rows)


def reconcile_completed_session(
    ticks: pd.DataFrame,
    trades: pd.DataFrame,
    *,
    tick_partition: TickPartition,
    trade_source: str,
    trade_exchange: str,
    trade_symbol: str,
    trade_trading_date: date,
) -> CompletedSessionReconciliation:
    """Compare one rebuilt tick partition with one exact P9-I1 trade partition."""
    normalized_ticks = _normalize_archive_frame(ticks)
    _require_frame_partition(normalized_ticks, tick_partition)
    expected_trade_identity = TickPartition(
        trade_source, trade_exchange, trade_symbol, trade_trading_date
    )
    if expected_trade_identity != tick_partition:
        raise ValueError("Tick and normalized-trade partition identities differ")
    required = {
        "timestamp",
        "price",
        "volume",
        "trade_value",
        "trading_date",
        "exchange",
        "symbol",
    }
    missing = sorted(required.difference(trades.columns))
    if missing:
        raise ValueError(f"Normalized trades missing required columns: {missing}")
    if trades.empty:
        raise ValueError("Normalized trades partition must not be empty")
    _require_scalar_identity(trades["exchange"], tick_partition.exchange, "exchange")
    _require_scalar_identity(trades["symbol"], tick_partition.symbol, "symbol")
    trade_dates = pd.to_datetime(trades["trading_date"]).dt.date
    _require_scalar_identity(trade_dates, tick_partition.trading_date, "trading_date")
    ordered_ticks = _sort_tick_frame(normalized_ticks)
    trade_sort = (
        ["timestamp", "provider_id"] if "provider_id" in trades else ["timestamp"]
    )
    ordered_trades = trades.assign(
        timestamp=pd.to_datetime(trades["timestamp"], utc=True)
    ).sort_values(trade_sort)
    tick_prices = ordered_ticks["price"].map(Decimal)
    tick_volumes = ordered_ticks["volume"].map(Decimal)
    trade_prices = ordered_trades["price"].map(lambda value: Decimal(str(value)))
    trade_volumes = ordered_trades["volume"].map(lambda value: Decimal(str(value)))
    trade_values = ordered_trades["trade_value"].map(lambda value: Decimal(str(value)))
    tick_value = sum(
        (
            price * volume
            for price, volume in zip(tick_prices, tick_volumes, strict=True)
        ),
        Decimal("0"),
    )
    return CompletedSessionReconciliation(
        tick_count=compare_reconciliation_value(
            len(ordered_ticks), len(ordered_trades), _COUNT_THRESHOLD
        ),
        trade_count=compare_reconciliation_value(
            len(ordered_trades), len(ordered_ticks), _COUNT_THRESHOLD
        ),
        total_volume=compare_reconciliation_value(
            sum(tick_volumes, Decimal("0")),
            sum(trade_volumes, Decimal("0")),
            VOLUME_VALUE_THRESHOLD,
        ),
        total_trade_value=compare_reconciliation_value(
            tick_value,
            sum(trade_values, Decimal("0")),
            VOLUME_VALUE_THRESHOLD,
        ),
        open=compare_reconciliation_value(
            tick_prices.iloc[0], trade_prices.iloc[0], CLOSE_THRESHOLD
        ),
        close=compare_reconciliation_value(
            tick_prices.iloc[-1], trade_prices.iloc[-1], CLOSE_THRESHOLD
        ),
    )


def _empty_tick_frame() -> pd.DataFrame:
    table = pa.Table.from_arrays(
        [pa.array([], type=field.type) for field in _TICK_SCHEMA], schema=_TICK_SCHEMA
    )
    return table.to_pandas()


def _normalize_archive_frame(frame: pd.DataFrame) -> pd.DataFrame:
    if tuple(frame.columns) != _TICK_COLUMNS:
        raise ValueError("Tick archive columns are not canonical")
    normalized = frame.copy()
    normalized["market_timestamp"] = pd.to_datetime(
        normalized["market_timestamp"], utc=True
    )
    normalized["received_at"] = pd.to_datetime(normalized["received_at"], utc=True)
    normalized["trading_date"] = pd.to_datetime(normalized["trading_date"]).dt.date
    normalized["sequence"] = pd.array(normalized["sequence"], dtype="Int64")
    normalized["price"] = normalized["price"].map(
        lambda value: _decimal_text(Decimal(value))
    )
    normalized["volume"] = normalized["volume"].map(
        lambda value: _decimal_text(Decimal(value))
    )
    return normalized


def _partition_for_ticks(ticks: Sequence[MarketTick]) -> TickPartition:
    if not ticks:
        raise ValueError("At least one tick is required")
    partition = TickPartition.from_tick(ticks[0])
    if any(TickPartition.from_tick(tick) != partition for tick in ticks[1:]):
        raise ValueError("Ticks must belong to exactly one partition")
    return partition


def _partition_for_frame(frame: pd.DataFrame) -> TickPartition:
    if frame.empty:
        raise ValueError("Tick archive frame must not be empty")
    values = {
        TickPartition(row.source, row.exchange, row.symbol, row.trading_date)
        for row in frame.loc[
            :, ["source", "exchange", "symbol", "trading_date"]
        ].itertuples(index=False)
    }
    if len(values) != 1:
        raise ValueError("Tick archive frame contains mixed partition identities")
    return values.pop()


def _require_frame_partition(frame: pd.DataFrame, expected: TickPartition) -> None:
    if _partition_for_frame(frame) != expected:
        raise ValueError("Tick archive partition identity differs from expected")


def _require_scalar_identity(
    values: Iterable[object], expected: object, name: str
) -> None:
    actual = set(values)
    if actual != {expected}:
        raise ValueError(f"Normalized-trade {name} identity differs from expected")


def _sort_tick_frame(frame: pd.DataFrame) -> pd.DataFrame:
    ordered = frame.assign(_sequence_missing=frame["sequence"].isna())
    return (
        ordered.sort_values(
            ["market_timestamp", "_sequence_missing", "sequence", "event_id"],
            kind="mergesort",
            na_position="last",
        )
        .drop(columns="_sequence_missing")
        .reset_index(drop=True)
    )


def _partition_manifest(partition: TickPartition) -> dict[str, str]:
    return {
        "source": partition.source,
        "exchange": partition.exchange,
        "symbol": partition.symbol,
        "tradingDate": partition.trading_date.isoformat(),
    }


def _decimal_text(value: Decimal) -> str:
    rendered = format(value.normalize(), "f")
    return "0" if rendered in {"-0", ""} else rendered
