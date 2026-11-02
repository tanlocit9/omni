"""Persisted output lineage shared by metadata sync and immutable snapshots."""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

import pandas as pd

from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.global_metadata import GlobalDatasetInput


def extract_output_lineage(
    dataset: str, frame: pd.DataFrame, values: Mapping[str, Any]
) -> list[GlobalDatasetInput]:
    if dataset == "eod":
        return []
    if dataset not in {"indicators", "signals"}:
        raise ManifestInvalidError("Dataset has no approved snapshot lineage extractor")
    required = {"symbol_key", "eod_data_version"}
    if dataset == "indicators":
        required = {"eod_data_version"}
    if not required.issubset(frame.columns):
        raise ManifestInvalidError(
            f"Dataset {dataset!r} lacks authoritative persisted lineage evidence"
        )
    inputs: dict[tuple[str, tuple[tuple[str, Any], ...], str], GlobalDatasetInput] = {}
    for row in frame.to_dict("records"):
        if dataset == "indicators":
            partition = {"exchange": values["exchange"], "code": values["code"]}
            candidates = (("eod", partition, row.get("eod_data_version")),)
        else:
            parts = str(row.get("symbol_key", "")).lower().split("-", 1)
            if len(parts) != 2:
                raise ManifestInvalidError(
                    "Signal lineage contains an invalid symbol_key"
                )
            partition = {"exchange": parts[0], "code": parts[1]}
            indicator_partition = {
                "source": "ad_close",
                "timeframe": values["timeframe"],
                **partition,
            }
            candidates = [("eod", partition, row.get("eod_data_version"))]
            if str(values.get("strategy", "")).upper() != "CONFIRMED_TREND_EQUALS":
                candidates.append(
                    (
                        "indicators",
                        indicator_partition,
                        row.get("indicators_data_version"),
                    )
                )
        for upstream, partition, version in candidates:
            if not isinstance(version, str) or not re.fullmatch(
                r"sha256:[0-9a-f]{64}", version
            ):
                raise ManifestInvalidError(
                    "Derived lineage contains an invalid dataVersion"
                )
            item = GlobalDatasetInput(upstream, partition, version)
            inputs[(upstream, tuple(sorted(partition.items())), version)] = item
    return list(inputs.values())

