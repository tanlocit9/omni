import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from py_common.market_ticks import (
    MarketTick,
    TickBoundaryRejection,
    TickRejectionCode,
    parse_market_tick,
    replay_market_ticks,
)


def make_tick(
    *,
    second: int = 1,
    sequence: int | None = 10,
    trade_id: str | None = "trade-10",
) -> MarketTick:
    market_timestamp = datetime(2026, 9, 11, 2, 15, second, 123456, tzinfo=UTC)
    return MarketTick.create(
        source="provider-a",
        exchange="HOSE",
        symbol="HPG",
        market_timestamp=market_timestamp,
        received_at=market_timestamp + timedelta(milliseconds=25),
        price=Decimal("28.5000"),
        volume=Decimal("100.00"),
        trade_id=trade_id,
        sequence=sequence,
    )


def test_canonical_json_round_trip_is_stable() -> None:
    tick = make_tick()

    encoded = tick.canonical_json()

    assert parse_market_tick(encoded) == tick
    assert parse_market_tick(encoded).canonical_json() == encoded
    assert b'"price":"28.5"' in encoded
    assert b'"marketTimestamp":"2026-09-11T02:15:01.123456Z"' in encoded


def test_identity_is_deterministic_and_normalized() -> None:
    first = make_tick()
    second = MarketTick.create(
        source="provider-a",
        exchange="HOSE",
        symbol="HPG",
        market_timestamp=first.market_timestamp,
        received_at=first.received_at + timedelta(seconds=3),
        price=Decimal("28.5"),
        volume=Decimal("1E+2"),
        trade_id="trade-10",
        sequence=10,
    )

    assert first.event_id == second.event_id


def test_rejects_identity_tampering() -> None:
    payload = json.loads(make_tick().canonical_json())
    payload["price"] = "29"

    with pytest.raises(TickBoundaryRejection) as error:
        parse_market_tick(json.dumps(payload))

    assert error.value.code is TickRejectionCode.IDENTITY_MISMATCH
    assert error.value.field == "eventId"


@pytest.mark.parametrize(
    ("mutation", "code", "field"),
    [
        (
            lambda payload: payload.pop("symbol"),
            TickRejectionCode.MISSING_FIELD,
            "symbol",
        ),
        (
            lambda payload: payload.update({"ticker": payload["symbol"]}),
            TickRejectionCode.UNKNOWN_FIELD,
            "ticker",
        ),
        (
            lambda payload: payload.update(
                {"market_timestamp": payload["marketTimestamp"]}
            ),
            TickRejectionCode.UNKNOWN_FIELD,
            "market_timestamp",
        ),
        (
            lambda payload: payload.update({"marketTimestamp": "2026-09-11T02:15:01"}),
            TickRejectionCode.INVALID_TIMESTAMP,
            "marketTimestamp",
        ),
    ],
)
def test_strict_boundary_rejects_missing_alias_and_invalid_timestamp(
    mutation, code: TickRejectionCode, field: str
) -> None:
    payload = json.loads(make_tick().canonical_json())
    mutation(payload)

    with pytest.raises(TickBoundaryRejection) as error:
        parse_market_tick(json.dumps(payload))

    assert error.value.code is code
    assert error.value.field == field


def test_rejects_invalid_json_and_non_object_shape() -> None:
    with pytest.raises(TickBoundaryRejection) as invalid:
        parse_market_tick(b"not-json")
    assert invalid.value.code is TickRejectionCode.INVALID_JSON

    with pytest.raises(TickBoundaryRejection) as shape:
        parse_market_tick("[]")
    assert shape.value.code is TickRejectionCode.INVALID_SHAPE


def test_replay_deduplicates_and_orders_without_arrival_time() -> None:
    later = make_tick(second=2, sequence=None, trade_id=None)
    earlier_sequence_two = make_tick(second=1, sequence=2, trade_id="trade-2")
    earlier_sequence_one = make_tick(second=1, sequence=1, trade_id="trade-1")

    replayed = replay_market_ticks(
        [later, earlier_sequence_two, earlier_sequence_one, earlier_sequence_one]
    )

    assert replayed == (earlier_sequence_one, earlier_sequence_two, later)
    assert replay_market_ticks(reversed(replayed)) == replayed


def test_replay_uses_earliest_received_duplicate_independent_of_input_order() -> None:
    first_observation = make_tick()
    retried_observation = MarketTick.create(
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

    assert first_observation.event_id == retried_observation.event_id
    assert replay_market_ticks([retried_observation, first_observation]) == (
        first_observation,
    )
    assert replay_market_ticks([first_observation, retried_observation]) == (
        first_observation,
    )
