from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import pandas as pd
import pytest

from py_common.intraday_reconciliation import ReconciliationStatus
from py_common.market_tick_archive import (
    TickPartition,
    assemble_tick_batches,
    build_one_minute_bars,
    compact_tick_parts,
    decode_tick_parquet,
    encode_tick_parquet,
    publish_tick_batch,
    reconcile_completed_session,
    ticks_to_frame,
)
from py_common.market_ticks import MarketTick
from py_common.storage.immutable_publication import ImmutableDatasetPublisher


class MemoryStorage:
    def __init__(self) -> None:
        self.objects: dict[tuple[str, str], bytes] = {}
        self.fail_suffix: str | None = None

    async def write_bytes(
        self, bucket: str, object_name: str, data: bytes, content_type: str
    ) -> None:
        if self.fail_suffix and object_name.endswith(self.fail_suffix):
            raise RuntimeError("injected write failure")
        self.objects[(bucket, object_name)] = data

    async def read_bytes(self, bucket: str, object_name: str) -> bytes:
        return self.objects[(bucket, object_name)]


def make_tick(
    second: int,
    *,
    price: str = "10",
    volume: str = "100",
    source: str = "vci",
    exchange: str = "HOSE",
    symbol: str = "HPG",
    day: int = 11,
) -> MarketTick:
    timestamp = datetime(2026, 9, day, 2, 15, tzinfo=UTC) + timedelta(seconds=second)
    return MarketTick.create(
        source=source,
        exchange=exchange,
        symbol=symbol,
        market_timestamp=timestamp,
        received_at=timestamp + timedelta(milliseconds=20),
        price=Decimal(price),
        volume=Decimal(volume),
        trade_id=f"trade-{day}-{second}-{price}-{volume}",
        sequence=second,
    )


def trade_frame(
    ticks: list[MarketTick], *, volume_factor: Decimal = Decimal("1")
) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "timestamp": [tick.market_timestamp for tick in ticks],
            "provider_id": [tick.trade_id for tick in ticks],
            "price": [tick.price for tick in ticks],
            "volume": [tick.volume * volume_factor for tick in ticks],
            "trade_value": [tick.price * tick.volume * volume_factor for tick in ticks],
            "trading_date": [date(2026, 9, 11)] * len(ticks),
            "exchange": ["HOSE"] * len(ticks),
            "symbol": ["HPG"] * len(ticks),
        }
    )


def test_frame_and_parquet_bytes_are_deterministic_and_duplicate_safe() -> None:
    first = make_tick(1, price="10.00")
    second = make_tick(2, price="11")

    canonical = ticks_to_frame([first, second, first])
    reordered = ticks_to_frame([second, first])
    first_bytes = encode_tick_parquet([first, second, first])
    second_bytes = encode_tick_parquet([second, first])

    pd.testing.assert_frame_equal(canonical, reordered)
    assert first_bytes == second_bytes
    pd.testing.assert_frame_equal(decode_tick_parquet(first_bytes), canonical)


def test_parquet_bytes_ignore_retry_order_with_different_receive_times() -> None:
    first_observation = make_tick(1)
    retry = MarketTick.create(
        source=first_observation.source,
        exchange=first_observation.exchange,
        symbol=first_observation.symbol,
        market_timestamp=first_observation.market_timestamp,
        received_at=first_observation.received_at + timedelta(seconds=3),
        price=first_observation.price,
        volume=first_observation.volume,
        trade_id=first_observation.trade_id,
        sequence=first_observation.sequence,
    )

    assert first_observation.event_id == retry.event_id
    assert encode_tick_parquet([retry, first_observation]) == encode_tick_parquet(
        [first_observation, retry]
    )


def test_bounded_batch_plan_is_stable_and_rejects_empty_or_mixed_partition() -> None:
    ticks = [make_tick(3), make_tick(1), make_tick(2)]

    first = assemble_tick_batches(ticks, max_ticks=2)
    second = assemble_tick_batches(reversed(ticks), max_ticks=2)

    assert [len(batch.ticks) for batch in first] == [2, 1]
    assert [batch.part_id for batch in first] == [batch.part_id for batch in second]
    with pytest.raises(ValueError, match="At least one tick"):
        assemble_tick_batches([], max_ticks=2)
    with pytest.raises(ValueError, match="exactly one partition"):
        assemble_tick_batches([ticks[0], make_tick(4, symbol="FPT")], max_ticks=2)


@pytest.mark.anyio
async def test_repeated_publication_has_same_identity_and_ready_last() -> None:
    storage = MemoryStorage()
    publisher = ImmutableDatasetPublisher(storage, storage, "stock-data")
    batch = assemble_tick_batches([make_tick(1), make_tick(2)], max_ticks=10)[0]
    prefix = (
        "realtime/ticks/source=vci/exchange=hose/"
        "trading_date=2026-09-11/symbol=hpg/parts"
    )

    first = await publish_tick_batch(publisher, batch, partition_prefix=prefix)
    second = await publish_tick_batch(publisher, batch, partition_prefix=prefix)

    assert first == second
    assert first.data_version == f"sha256:{batch.part_id}"
    assert storage.objects[("stock-data", first.ready_object)].startswith(
        b'{"dataVersion"'
    )


@pytest.mark.anyio
async def test_publication_failure_preserves_previous_ready() -> None:
    storage = MemoryStorage()
    publisher = ImmutableDatasetPublisher(storage, storage, "stock-data")
    batch = assemble_tick_batches([make_tick(1)], max_ticks=10)[0]
    prefix = (
        "realtime/ticks/source=vci/exchange=hose/"
        "trading_date=2026-09-11/symbol=hpg/parts"
    )
    ready = f"{prefix}/READY.json"
    storage.objects[("stock-data", ready)] = b"previous-ready"
    storage.fail_suffix = "manifest.json"

    with pytest.raises(RuntimeError, match="injected write failure"):
        await publish_tick_batch(publisher, batch, partition_prefix=prefix)

    assert storage.objects[("stock-data", ready)] == b"previous-ready"


def test_compaction_is_order_independent_and_collapses_duplicates() -> None:
    first = make_tick(1)
    second = make_tick(2)
    part_one = encode_tick_parquet([second, first])
    part_two = encode_tick_parquet([first])

    compacted = compact_tick_parts([part_one, part_two])
    reordered = compact_tick_parts([part_two, part_one])

    pd.testing.assert_frame_equal(compacted, reordered)
    assert list(compacted["event_id"]) == [first.event_id, second.event_id]


def test_compaction_rejects_no_parts_empty_parts_and_mixed_partitions() -> None:
    with pytest.raises(ValueError, match="At least one"):
        compact_tick_parts([])
    with pytest.raises(ValueError, match="must not be empty"):
        compact_tick_parts([encode_tick_parquet([])])
    with pytest.raises(ValueError, match="differs from expected"):
        compact_tick_parts(
            [
                encode_tick_parquet([make_tick(1)]),
                encode_tick_parquet([make_tick(2, symbol="FPT")]),
            ]
        )


def test_one_minute_bars_use_utc_event_time_and_are_duplicate_safe() -> None:
    ticks = [
        make_tick(1, price="10", volume="2"),
        make_tick(20, price="12", volume="3"),
        make_tick(59, price="9", volume="4"),
        make_tick(1, price="10", volume="2"),
        make_tick(60, price="11", volume="5"),
    ]

    bars = build_one_minute_bars(ticks_to_frame(ticks))

    assert len(bars) == 2
    assert bars.iloc[0].to_dict() == {
        "source": "vci",
        "exchange": "HOSE",
        "symbol": "HPG",
        "trading_date": date(2026, 9, 11),
        "bar_time": pd.Timestamp("2026-09-11T02:15:00Z"),
        "open": "10",
        "high": "12",
        "low": "9",
        "close": "9",
        "volume": "9",
        "trade_value": "92",
        "tick_count": 3,
    }
    assert bars.iloc[1]["bar_time"] == pd.Timestamp("2026-09-11T02:16:00Z")


@pytest.mark.parametrize(
    ("volume_factor", "status"),
    [
        (Decimal("1"), ReconciliationStatus.READY),
        (Decimal("1.002"), ReconciliationStatus.WARNING),
        (Decimal("1.01"), ReconciliationStatus.REJECTED),
    ],
)
def test_completed_session_reconciliation_statuses(
    volume_factor: Decimal, status: ReconciliationStatus
) -> None:
    ticks = [
        make_tick(1, price="10", volume="1000"),
        make_tick(2, price="11", volume="1000"),
    ]
    partition = TickPartition("vci", "HOSE", "HPG", date(2026, 9, 11))

    result = reconcile_completed_session(
        ticks_to_frame(ticks),
        trade_frame(ticks, volume_factor=volume_factor),
        tick_partition=partition,
        trade_source="vci",
        trade_exchange="HOSE",
        trade_symbol="HPG",
        trade_trading_date=date(2026, 9, 11),
    )

    assert result.status is status


def test_completed_session_reconciliation_rejects_date_mismatch() -> None:
    ticks = [make_tick(1)]
    partition = TickPartition("vci", "HOSE", "HPG", date(2026, 9, 11))

    with pytest.raises(ValueError, match="identities differ"):
        reconcile_completed_session(
            ticks_to_frame(ticks),
            trade_frame(ticks),
            tick_partition=partition,
            trade_source="vci",
            trade_exchange="HOSE",
            trade_symbol="HPG",
            trade_trading_date=date(2026, 9, 12),
        )
