"""Trusted logical dataset definitions for global metadata synchronization."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.global_metadata import PartitionKeyDefinition, PartitionValueType


@dataclass(frozen=True)
class DatasetAdapterDefinition:
    name: str
    label: str
    data_prefix: str
    partition_keys: tuple[PartitionKeyDefinition, ...]
    supports_full_sync: bool = True
    supports_dataset_sync: bool = True
    supports_exact_sync: bool = True

    def normalize_partition(self, values: Mapping[str, Any]) -> dict[str, Any]:
        supplied = set(values)
        required = {item.name for item in self.partition_keys if item.required}
        allowed = {item.name for item in self.partition_keys}
        if supplied != required or not supplied.issubset(allowed):
            raise ManifestInvalidError(
                f"Exact synchronization for {self.name!r} requires keys "
                f"{sorted(required)!r}"
            )
        return {
            item.name: item.normalize(values[item.name])
            for item in sorted(
                self.partition_keys, key=lambda definition: definition.order
            )
        }


class DatasetAdapterRegistry:
    def __init__(self, definitions: tuple[DatasetAdapterDefinition, ...]) -> None:
        self._definitions = {item.name: item for item in definitions}
        if len(self._definitions) != len(definitions):
            raise ManifestInvalidError("Dataset registry names must be unique")

    def get(self, name: str) -> DatasetAdapterDefinition:
        try:
            return self._definitions[name]
        except KeyError as exc:
            raise ManifestInvalidError(f"Unsupported dataset: {name!r}") from exc

    def all(self) -> tuple[DatasetAdapterDefinition, ...]:
        return tuple(self._definitions[name] for name in sorted(self._definitions))


def _key(name: str, order: int, label: str) -> PartitionKeyDefinition:
    return PartitionKeyDefinition(
        name=name,
        type=PartitionValueType.STRING,
        required=True,
        order=order,
        label=label,
    )


OMNI_DATASET_REGISTRY = DatasetAdapterRegistry(
    (
        DatasetAdapterDefinition(
            name="eod",
            label="End-of-Day Prices",
            data_prefix="eod/",
            partition_keys=(_key("exchange", 0, "Exchange"), _key("code", 1, "Code")),
        ),
        DatasetAdapterDefinition(
            name="indicators",
            label="Technical Indicators",
            data_prefix="indicators/",
            partition_keys=(
                _key("source", 0, "Source"),
                _key("timeframe", 1, "Timeframe"),
                _key("exchange", 2, "Exchange"),
                _key("code", 3, "Code"),
            ),
        ),
        DatasetAdapterDefinition(
            name="signals",
            label="Trading Signals",
            data_prefix="signals/",
            partition_keys=(
                _key("strategy", 0, "Strategy"),
                _key("timeframe", 1, "Timeframe"),
                _key("exchange", 2, "Exchange"),
            ),
        ),
    )
)
