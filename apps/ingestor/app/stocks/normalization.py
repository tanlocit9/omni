import re
from collections.abc import Mapping
from typing import Any

import pandas as pd

_NON_WORD_PATTERN = re.compile(r"[^0-9A-Za-z]+")
_LOWER_TO_UPPER_PATTERN = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_ACRONYM_PATTERN = re.compile(r"(?<=[A-Z])(?=[A-Z][a-z])")
_MULTI_UNDERSCORE_PATTERN = re.compile(r"_+")


def to_snake_case(value: str) -> str:
    separated = _NON_WORD_PATTERN.sub("_", value.strip())
    separated = _ACRONYM_PATTERN.sub("_", separated)
    separated = _LOWER_TO_UPPER_PATTERN.sub("_", separated)
    normalized = _MULTI_UNDERSCORE_PATTERN.sub("_", separated).strip("_")
    return normalized.lower()


def _is_missing(value: Any) -> bool:
    if value is None:
        return True
    return bool(pd.isna(value))


def normalize_record_keys(record: Mapping[str, Any]) -> dict[str, Any]:
    normalized: dict[str, Any] = {}
    original_keys_by_normalized: dict[str, str] = {}

    for key, value in record.items():
        normalized_key = to_snake_case(str(key))
        if normalized_key in normalized:
            existing_value = normalized[normalized_key]
            if _is_missing(existing_value):
                normalized[normalized_key] = value
                original_keys_by_normalized[normalized_key] = str(key)
                continue
            if _is_missing(value) or existing_value == value:
                continue

            original_key = original_keys_by_normalized[normalized_key]
            raise ValueError(
                "Cannot normalize record keys because "
                f"{original_key!r} and {key!r} both map to {normalized_key!r} "
                "with different values"
            )

        normalized[normalized_key] = value
        original_keys_by_normalized[normalized_key] = str(key)

    return normalized


def normalize_record_list_keys(
    records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    return [normalize_record_keys(record) for record in records]
