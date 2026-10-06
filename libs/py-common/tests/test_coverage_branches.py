from __future__ import annotations

from datetime import UTC, date, datetime
from decimal import Decimal

import pandas as pd
import pytest

from py_common.intraday_reconciliation import (
    IntradayReconciliation,
    ReconciliationDifference,
    ReconciliationStatus,
    ReconciliationThreshold,
    compare_reconciliation_value,
)
from py_common.market_tick_archive import (
    TickPartition,
    _partition_for_frame,
    _require_scalar_identity,
    assemble_tick_batches,
    build_one_minute_bars,
    reconcile_completed_session,
    ticks_to_frame,
)
from py_common.market_ticks import MarketTick
from py_common.storage.immutable_publication import ImmutableDatasetPublisher


class CorruptingStorage:
    def __init__(self, corrupt_suffix: str) -> None:
        self.objects: dict[tuple[str, str], bytes] = {}
        self.corrupt_suffix = corrupt_suffix

    async def write_bytes(
        self, bucket: str, object_name: str, data: bytes, content_type: str
    ) -> None:
        del content_type
        self.objects[(bucket, object_name)] = data

    async def read_bytes(self, bucket: str, object_name: str) -> bytes:
        value = self.objects[(bucket, object_name)]
        return b"corrupt" if object_name.endswith(self.corrupt_suffix) else value


def difference(status: ReconciliationStatus) -> ReconciliationDifference:
    return ReconciliationDifference(
        Decimal("1"), Decimal("1"), Decimal("0"), Decimal("0"), status
    )


def tick() -> MarketTick:
    timestamp = datetime(2026, 9, 11, 2, 15, tzinfo=UTC)
    return MarketTick.create(
        source="vci",
        exchange="HOSE",
        symbol="HPG",
        market_timestamp=timestamp,
        received_at=timestamp,
        price=Decimal("10"),
        volume=Decimal("100"),
        trade_id="trade-1",
        sequence=1,
    )


def test_reconciliation_status_precedence_and_zero_expected_boundaries() -> None:
    ready = difference(ReconciliationStatus.READY)
    warning = difference(ReconciliationStatus.WARNING)
    rejected = difference(ReconciliationStatus.REJECTED)
    assert (
        IntradayReconciliation(ready, ready, ready).status is ReconciliationStatus.READY
    )
    assert (
        IntradayReconciliation(ready, warning, ready).status
        is ReconciliationStatus.WARNING
    )
    assert (
        IntradayReconciliation(warning, rejected, ready).status
        is ReconciliationStatus.REJECTED
    )

    threshold = ReconciliationThreshold(Decimal("0.1"), Decimal("0.2"), Decimal("1"))
    assert compare_reconciliation_value(0, 0, threshold).relative == 0
    assert compare_reconciliation_value(2, 0, threshold).relative.is_infinite()
    assert (
        compare_reconciliation_value(1, 0, threshold).status
        is ReconciliationStatus.READY
    )


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("suffix", "error"),
    [
        ("data.parquet", "data failed"),
        ("manifest.json", "manifest failed"),
        ("READY.json", "READY pointer failed"),
    ],
)
async def test_immutable_publication_rejects_readback_corruption(
    suffix: str, error: str
) -> None:
    storage = CorruptingStorage(suffix)
    publisher = ImmutableDatasetPublisher(storage, storage, "stock-data")

    with pytest.raises(ValueError, match=error):
        await publisher.publish(
            partition_prefix="dataset/partition=value",
            object_name="data.parquet",
            data=b"candidate",
            manifest={"dataset": "test"},
        )


@pytest.mark.anyio
async def test_publication_awaits_validator_and_rejects_unsafe_paths() -> None:
    storage = CorruptingStorage("never")
    publisher = ImmutableDatasetPublisher(storage, storage, "stock-data")
    validated: list[bytes] = []

    async def validate(data: bytes) -> None:
        validated.append(data)

    await publisher.publish(
        partition_prefix="dataset/partition=value",
        object_name="data.parquet",
        data=b"candidate",
        manifest={"dataset": "test"},
        validate_data=validate,
    )
    assert validated == [b"candidate"]
    with pytest.raises(ValueError, match="safe relative"):
        await publisher.publish(
            partition_prefix="../unsafe",
            object_name="data.parquet",
            data=b"candidate",
            manifest={},
        )
    with pytest.raises(ValueError, match="safe object leaf"):
        await publisher.publish(
            partition_prefix="safe",
            object_name="nested/data.parquet",
            data=b"candidate",
            manifest={},
        )


def test_archive_boundary_guards() -> None:
    with pytest.raises(ValueError, match="greater than zero"):
        assemble_tick_batches([tick()], max_ticks=0)
    with pytest.raises(ValueError, match="empty tick set"):
        build_one_minute_bars(ticks_to_frame([]))

    frame = ticks_to_frame([tick()])
    mixed = pd.concat([frame, frame.assign(symbol="FPT")], ignore_index=True)
    with pytest.raises(ValueError, match="mixed partition"):
        _partition_for_frame(mixed)
    with pytest.raises(ValueError, match="identity differs"):
        _require_scalar_identity(["HOSE", "HNX"], "HOSE", "exchange")


def test_completed_reconciliation_rejects_missing_and_empty_trades() -> None:
    frame = ticks_to_frame([tick()])
    partition = TickPartition("vci", "HOSE", "HPG", date(2026, 9, 11))
    kwargs = {
        "tick_partition": partition,
        "trade_source": "vci",
        "trade_exchange": "HOSE",
        "trade_symbol": "HPG",
        "trade_trading_date": date(2026, 9, 11),
    }
    with pytest.raises(ValueError, match="missing required columns"):
        reconcile_completed_session(frame, pd.DataFrame({"timestamp": []}), **kwargs)
    empty = pd.DataFrame(
        columns=[
            "timestamp",
            "price",
            "volume",
            "trade_value",
            "trading_date",
            "exchange",
            "symbol",
        ]
    )
    with pytest.raises(ValueError, match="must not be empty"):
        reconcile_completed_session(frame, empty, **kwargs)
