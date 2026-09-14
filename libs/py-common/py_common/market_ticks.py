"""Provider-independent canonical market-tick contract and replay behavior."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable, Mapping
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from enum import StrEnum
from typing import Any, Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_serializer,
    field_validator,
    model_validator,
)


class TickRejectionCode(StrEnum):
    """Stable boundary rejection categories for observability and quarantine."""

    INVALID_JSON = "INVALID_JSON"
    INVALID_SHAPE = "INVALID_SHAPE"
    MISSING_FIELD = "MISSING_FIELD"
    UNKNOWN_FIELD = "UNKNOWN_FIELD"
    INVALID_VALUE = "INVALID_VALUE"
    INVALID_TIMESTAMP = "INVALID_TIMESTAMP"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"


class TickBoundaryRejection(ValueError):
    """Actionable rejection raised before a tick enters stream processing."""

    def __init__(self, code: TickRejectionCode, field: str, reason: str) -> None:
        self.code = code
        self.field = field
        self.reason = reason
        super().__init__(f"{code.value}:{field}:{reason}")


class MarketTick(BaseModel):
    """Strict canonical JSON trade tick independent of any provider DTO."""

    model_config = ConfigDict(
        alias_generator=lambda name: {
            "schema_version": "schemaVersion",
            "event_id": "eventId",
            "market_timestamp": "marketTimestamp",
            "received_at": "receivedAt",
            "trade_id": "tradeId",
        }.get(name, name),
        extra="forbid",
        frozen=True,
        populate_by_name=False,
        strict=True,
    )

    schema_version: int = Field(alias="schemaVersion", ge=1, le=1)
    event_id: str = Field(alias="eventId", pattern=r"^mt_[0-9a-f]{64}$")
    source: str = Field(min_length=1, max_length=64, pattern=r"^[a-z0-9][a-z0-9_-]*$")
    exchange: str = Field(min_length=1, max_length=32, pattern=r"^[A-Z0-9_-]+$")
    symbol: str = Field(min_length=1, max_length=32, pattern=r"^[A-Z0-9._-]+$")
    market_timestamp: datetime = Field(alias="marketTimestamp")
    received_at: datetime = Field(alias="receivedAt")
    price: Decimal = Field(gt=0)
    volume: Decimal = Field(gt=0)
    trade_id: str | None = Field(
        default=None, alias="tradeId", min_length=1, max_length=128
    )
    sequence: int | None = Field(default=None, ge=0)

    @field_validator("price", "volume", mode="before")
    @classmethod
    def _parse_exact_decimal(cls, value: Any) -> Decimal:
        if not isinstance(value, str):
            raise ValueError("decimal must be a canonical JSON string")
        try:
            parsed = Decimal(value)
        except InvalidOperation as exc:
            raise ValueError("decimal string is invalid") from exc
        if not parsed.is_finite() or _canonical_decimal(parsed) != value:
            raise ValueError("decimal string must use canonical finite form")
        return parsed

    @field_validator("market_timestamp", "received_at", mode="before")
    @classmethod
    def _require_utc(cls, value: Any) -> datetime:
        if isinstance(value, str):
            try:
                parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValueError("timestamp must be an ISO-8601 UTC instant") from exc
        elif isinstance(value, datetime):
            parsed = value
        else:
            raise ValueError("timestamp must be an ISO-8601 UTC string")
        if parsed.tzinfo is None or parsed.utcoffset() != UTC.utcoffset(parsed):
            raise ValueError("timestamp must include UTC offset Z or +00:00")
        return parsed.astimezone(UTC)

    @field_serializer("market_timestamp", "received_at", when_used="json")
    def _serialize_timestamp(self, value: datetime) -> str:
        return value.isoformat(timespec="microseconds").replace("+00:00", "Z")

    @field_serializer("price", "volume", when_used="json")
    def _serialize_decimal(self, value: Decimal) -> str:
        return _canonical_decimal(value)

    @model_validator(mode="after")
    def _validate_identity(self) -> Self:
        expected = self.calculate_event_id(
            source=self.source,
            exchange=self.exchange,
            symbol=self.symbol,
            market_timestamp=self.market_timestamp,
            price=self.price,
            volume=self.volume,
            trade_id=self.trade_id,
            sequence=self.sequence,
        )
        if self.event_id != expected:
            raise ValueError(f"eventId does not match canonical identity {expected}")
        return self

    @staticmethod
    def calculate_event_id(
        *,
        source: str,
        exchange: str,
        symbol: str,
        market_timestamp: datetime,
        price: Decimal,
        volume: Decimal,
        trade_id: str | None,
        sequence: int | None,
    ) -> str:
        """Derive stable identity from immutable normalized trade facts."""
        identity = {
            "exchange": exchange,
            "marketTimestamp": market_timestamp.astimezone(UTC)
            .isoformat(timespec="microseconds")
            .replace("+00:00", "Z"),
            "price": _canonical_decimal(price),
            "sequence": sequence,
            "source": source,
            "symbol": symbol,
            "tradeId": trade_id,
            "volume": _canonical_decimal(volume),
        }
        encoded = json.dumps(
            identity, ensure_ascii=True, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        return f"mt_{hashlib.sha256(encoded).hexdigest()}"

    @classmethod
    def create(
        cls,
        *,
        source: str,
        exchange: str,
        symbol: str,
        market_timestamp: datetime,
        received_at: datetime,
        price: Decimal,
        volume: Decimal,
        trade_id: str | None = None,
        sequence: int | None = None,
    ) -> Self:
        """Create a canonical tick while deriving, never accepting, its identity."""
        event_id = cls.calculate_event_id(
            source=source,
            exchange=exchange,
            symbol=symbol,
            market_timestamp=market_timestamp,
            price=price,
            volume=volume,
            trade_id=trade_id,
            sequence=sequence,
        )
        return cls.model_validate(
            {
                "schemaVersion": 1,
                "eventId": event_id,
                "source": source,
                "exchange": exchange,
                "symbol": symbol,
                "marketTimestamp": market_timestamp,
                "receivedAt": received_at,
                "price": _canonical_decimal(price),
                "volume": _canonical_decimal(volume),
                "tradeId": trade_id,
                "sequence": sequence,
            }
        )

    def canonical_json(self) -> bytes:
        """Serialize exact canonical JSON bytes for strict transport and replay."""
        payload = self.model_dump(mode="json", by_alias=True, exclude_none=True)
        return json.dumps(
            payload, ensure_ascii=True, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")


def parse_market_tick(payload: bytes | str) -> MarketTick:
    """Validate strict canonical JSON and map failures to a stable taxonomy."""
    try:
        decoded = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError) as exc:
        raise TickBoundaryRejection(
            TickRejectionCode.INVALID_JSON, "$", "payload is not valid JSON"
        ) from exc
    if not isinstance(decoded, Mapping):
        raise TickBoundaryRejection(
            TickRejectionCode.INVALID_SHAPE, "$", "payload must be a JSON object"
        )
    try:
        return MarketTick.model_validate(decoded)
    except ValidationError as exc:
        error = exc.errors(include_url=False)[0]
        field = ".".join(str(part) for part in error["loc"]) or "$"
        error_type = str(error["type"])
        message = str(error["msg"])
        if error_type == "missing":
            code = TickRejectionCode.MISSING_FIELD
        elif error_type == "extra_forbidden":
            code = TickRejectionCode.UNKNOWN_FIELD
        elif "datetime" in error_type or "timestamp" in message:
            code = TickRejectionCode.INVALID_TIMESTAMP
        elif field in {"eventId", "$"} and "canonical identity" in message:
            code = TickRejectionCode.IDENTITY_MISMATCH
            field = "eventId"
        else:
            code = TickRejectionCode.INVALID_VALUE
        raise TickBoundaryRejection(code, field, message) from exc


def replay_market_ticks(ticks: Iterable[MarketTick]) -> tuple[MarketTick, ...]:
    """Deduplicate and deterministically order a finite canonical replay stream.

    ``receivedAt`` is deliberately excluded from event identity because retries may
    observe the same trade at different arrival times. Keep the earliest observation
    so reordered retry input cannot change canonical replay or archive bytes.
    """
    unique: dict[str, MarketTick] = {}
    for tick in ticks:
        previous = unique.get(tick.event_id)
        if previous is None or tick.received_at < previous.received_at:
            unique[tick.event_id] = tick
    return tuple(
        sorted(
            unique.values(),
            key=lambda tick: (
                tick.market_timestamp,
                tick.sequence is None,
                tick.sequence if tick.sequence is not None else 0,
                tick.event_id,
            ),
        )
    )


def _canonical_decimal(value: Decimal) -> str:
    normalized = value.normalize()
    rendered = format(normalized, "f")
    return "0" if rendered in {"-0", ""} else rendered
