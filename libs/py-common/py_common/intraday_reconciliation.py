"""Deterministic end-of-day reconciliation for normalized intraday trades."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum


class ReconciliationStatus(StrEnum):
    READY = "READY"
    WARNING = "WARNING"
    REJECTED = "REJECTED"


@dataclass(frozen=True)
class ReconciliationThreshold:
    warning_relative: Decimal
    rejection_relative: Decimal
    absolute_tick: Decimal = Decimal("0")


@dataclass(frozen=True)
class ReconciliationDifference:
    actual: Decimal
    expected: Decimal
    absolute: Decimal
    relative: Decimal
    status: ReconciliationStatus


@dataclass(frozen=True)
class IntradayReconciliation:
    close: ReconciliationDifference
    volume: ReconciliationDifference
    value: ReconciliationDifference

    @property
    def status(self) -> ReconciliationStatus:
        statuses = {self.close.status, self.volume.status, self.value.status}
        if ReconciliationStatus.REJECTED in statuses:
            return ReconciliationStatus.REJECTED
        if ReconciliationStatus.WARNING in statuses:
            return ReconciliationStatus.WARNING
        return ReconciliationStatus.READY


CLOSE_THRESHOLD = ReconciliationThreshold(
    warning_relative=Decimal("0.0001"),
    rejection_relative=Decimal("0.0005"),
)
VOLUME_VALUE_THRESHOLD = ReconciliationThreshold(
    warning_relative=Decimal("0.001"),
    rejection_relative=Decimal("0.005"),
)


def compare_reconciliation_value(
    actual: Decimal | int | str,
    expected: Decimal | int | str,
    threshold: ReconciliationThreshold,
) -> ReconciliationDifference:
    """Compare one aggregate using the greater of relative tolerance or a tick."""
    actual_value = Decimal(str(actual))
    expected_value = Decimal(str(expected))
    absolute = abs(actual_value - expected_value)
    relative = (
        absolute / abs(expected_value)
        if expected_value
        else (Decimal("0") if absolute == 0 else Decimal("Infinity"))
    )
    rejection_limit = max(
        abs(expected_value) * threshold.rejection_relative,
        threshold.absolute_tick,
    )
    warning_limit = max(
        abs(expected_value) * threshold.warning_relative,
        threshold.absolute_tick,
    )
    if absolute > rejection_limit:
        status = ReconciliationStatus.REJECTED
    elif absolute > warning_limit:
        status = ReconciliationStatus.WARNING
    else:
        status = ReconciliationStatus.READY
    return ReconciliationDifference(
        actual=actual_value,
        expected=expected_value,
        absolute=absolute,
        relative=relative,
        status=status,
    )


def reconcile_intraday_eod(
    *,
    final_price: Decimal | int | str,
    expected_close: Decimal | int | str,
    total_volume: Decimal | int | str,
    expected_volume: Decimal | int | str,
    total_value: Decimal | int | str,
    expected_value: Decimal | int | str,
    price_tick: Decimal | int | str = 0,
    quantity_tick: Decimal | int | str = 0,
) -> IntradayReconciliation:
    """Reconcile normalized session aggregates against canonical EOD values."""
    return IntradayReconciliation(
        close=compare_reconciliation_value(
            final_price,
            expected_close,
            ReconciliationThreshold(
                CLOSE_THRESHOLD.warning_relative,
                CLOSE_THRESHOLD.rejection_relative,
                Decimal(str(price_tick)),
            ),
        ),
        volume=compare_reconciliation_value(
            total_volume,
            expected_volume,
            ReconciliationThreshold(
                VOLUME_VALUE_THRESHOLD.warning_relative,
                VOLUME_VALUE_THRESHOLD.rejection_relative,
                Decimal(str(quantity_tick)),
            ),
        ),
        value=compare_reconciliation_value(
            total_value,
            expected_value,
            VOLUME_VALUE_THRESHOLD,
        ),
    )
