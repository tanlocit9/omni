"""Additive I8 atomic command contract; legacy records remain readable."""

from __future__ import annotations

import hashlib
import json
from datetime import date, datetime
from decimal import Decimal
from typing import Any
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, model_validator

_UNITS = {
    "SYNC_STOCK_PRICE": "SYMBOL",
    "SYNC_INDICATORS": "SYMBOL",
    "SYNC_SIGNALS": "SYMBOL",
    "PRECOMPUTE_SYMBOL_FEATURES": "SYMBOL",
    "SYNC_INTRADAY_EOD": "SYMBOL",
    "SYNC_SYMBOLS": "EXCHANGE",
    "EVALUATE_SIGNALS": "EXCHANGE",
    "PRECOMPUTE_SECTOR_FEATURES": "SECTOR",
    "SECTOR_ROTATION_BACKTEST": "GLOBAL",
    "SECTOR_TRANSITION_ANALYZE": "GLOBAL",
    "SECTOR_TRANSITION_EVALUATE_OUTCOMES": "GLOBAL",
    "SYNC_METADATA": "GLOBAL",
}
_TYPES = {
    "SymbolJobMessage": "SYNC_STOCK_PRICE",
    "IndicatorJobMessage": "SYNC_INDICATORS",
    "SignalJobMessage": "SYNC_SIGNALS",
    "SyncSymbolsJobMessage": "SYNC_SYMBOLS",
    "SignalEvaluationJobMessage": "EVALUATE_SIGNALS",
    "SectorWaveSymbolFeatureJobMessage": "PRECOMPUTE_SYMBOL_FEATURES",
    "SectorWaveSectorFeatureJobMessage": "PRECOMPUTE_SECTOR_FEATURES",
    "IntradayEodJobMessage": "SYNC_INTRADAY_EOD",
    "SectorRotationBacktestJobMessage": "SECTOR_ROTATION_BACKTEST",
    "SectorTransitionAnalyzeJobMessage": "SECTOR_TRANSITION_ANALYZE",
    "SectorTransitionOutcomeEvaluationJobMessage": (
        "SECTOR_TRANSITION_EVALUATE_OUTCOMES"
    ),
    "SyncMetadataJobMessage": "SYNC_METADATA",
}


def canonical(value: Any) -> str:
    if isinstance(value, dict):
        return (
            "{"
            + ",".join(
                canonical(str(k)) + ":" + canonical(value[k]) for k in sorted(value)
            )
            + "}"
        )
    if isinstance(value, list):
        return "[" + ",".join(canonical(v) for v in value) + "]"
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float, Decimal)):
        number = Decimal(str(value))
        if not number.is_finite():
            raise ValueError("Atomic parameters must contain finite numbers")
        if number == 0:
            return "0"
        # Decimal.normalize() rounds through the caller's precision context.
        # Fixed-point formatting and trimming are exact, like Java BigDecimal.
        rendered = format(number, "f")
        return rendered.rstrip("0").rstrip(".") if "." in rendered else rendered
    return json.dumps(str(value), ensure_ascii=False)


def atomic_work_id(
    job_type: str,
    work_type: str,
    work_key: str,
    business_date: date,
    parameters: dict[str, Any],
) -> str:
    identity = [job_type, work_type, work_key, business_date.isoformat(), parameters]
    return "work:" + hashlib.sha256(canonical(identity).encode("utf-8")).hexdigest()


class AtomicWorkEnvelope(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    job_type: str | None = Field(default=None, alias="jobType")
    business_date: date | None = Field(default=None, alias="businessDate")
    parameters: dict[str, Any] | None = None
    atomic_work_id: str | None = Field(default=None, alias="atomicWorkId")

    @model_validator(mode="after")
    def validate_atomic_work(self):
        fields = (
            self.job_type,
            self.business_date,
            self.parameters,
            self.atomic_work_id,
        )
        if all(v is None for v in fields):
            return self  # Historical wire records retain their old behavior.
        if any(v is None for v in fields):
            raise ValueError("Atomic command requires all identity fields")
        if self.job_type not in _UNITS or self.business_date.weekday() >= 5:
            raise ValueError("Unsupported atomic job type or weekend business date")
        expected = _TYPES.get(type(self).__name__)
        if expected is not None and expected != self.job_type:
            raise ValueError("Atomic job type does not match consumer")
        work_type = str(getattr(self, "work_type", ""))
        work_key = str(getattr(self, "work_key", ""))
        if work_type != _UNITS[self.job_type]:
            raise ValueError("Atomic work unit does not match job type")
        domain_attr = {
            "SYMBOL": "symbol_key",
            "EXCHANGE": "exchange",
            "SECTOR": "sector_code",
        }.get(work_type)
        if domain_attr and str(getattr(self, domain_attr, "")) != work_key:
            raise ValueError("Atomic key differs from domain target")
        if (
            work_type == "GLOBAL"
            and self.job_type != "SYNC_METADATA"
            and str(getattr(self, "strategy", "")) != work_key
        ):
            raise ValueError("Atomic key differs from global strategy")
        expected_id = atomic_work_id(
            self.job_type, work_type, work_key, self.business_date, self.parameters
        )
        if self.atomic_work_id != expected_id:
            raise ValueError("Atomic work identity does not match command")
        aliases = {
            "source": "source",
            "timeframe": "timeframe",
            "strategy": "strategy",
            "indicatorSource": "indicator_source",
            "indicators": "indicators",
            "provider": "provider",
            "sectorLevel": "sector_level",
            "predictionHorizons": "prediction_horizons",
            "sectorCodes": "sector_codes",
            "focusSectorCodes": "focus_sector_codes",
            "target": "target",
        }
        for wire, attr in aliases.items():
            if hasattr(self, attr) and wire not in self.parameters:
                raise ValueError(f"Missing effective parameter: {wire}")
            if wire in self.parameters and hasattr(self, attr):
                actual = getattr(self, attr)
                expected_value = self.parameters[wire]
                if isinstance(actual, BaseModel):
                    actual = actual.model_dump(by_alias=True, exclude_unset=True)
                if wire in {
                    "sectorCodes",
                    "focusSectorCodes",
                    "indicators",
                    "predictionHorizons",
                }:
                    actual, expected_value = sorted(actual), sorted(expected_value)
                if canonical(actual) != canonical(expected_value):
                    raise ValueError(f"Atomic parameter mismatch: {wire}")
        if hasattr(self, "trading_date") and self.trading_date != self.business_date:
            raise ValueError("Trading date differs from atomic day")
        if (
            hasattr(self, "evaluation_date")
            and self.evaluation_date != self.business_date
        ):
            raise ValueError("Evaluation date differs from atomic day")
        return self

    def require_current_snapshot(self) -> None:
        if self.business_date is not None:
            today = datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date()
            if self.business_date != today:
                raise ValueError("Historical catalog/metadata snapshot is unsupported")


def carry_work_status(status: Any, raw: dict[str, Any]) -> Any:
    """Preserve atomic attribution in diagnostics; execution UUID remains authority."""
    if not raw.get("atomicWorkId"):
        return status
    return status.model_copy(
        update={
            "meta_json": {
                **status.meta_json,
                **{
                    key: raw[key]
                    for key in ("jobType", "parameters", "atomicWorkId")
                    if key in raw
                },
                "workBusinessDate": raw.get("businessDate"),
            }
        }
    )
