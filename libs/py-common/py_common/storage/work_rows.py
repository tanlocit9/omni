"""Version the rows produced by one work, independently of their Parquet container."""

from __future__ import annotations

import hashlib
import json
import math
from datetime import date, datetime
from typing import Any

import numpy as np
import pandas as pd

from py_common.storage.exceptions import ManifestInvalidError


def canonical_value(item):
    if item is None or item is pd.NA or item is pd.NaT:
        return None
    if isinstance(item, (list, tuple, np.ndarray)):
        return [canonical_value(x) for x in item]
    if isinstance(item, dict):
        return {k: canonical_value(v) for k, v in item.items()}
    if isinstance(item, np.generic):
        return canonical_value(item.item())
    if isinstance(item, float):
        if math.isnan(item):
            return None
        if item.is_integer():
            return int(item)
    return item


def canonical_rows(frame):
    # Infer types from owned values only. Unrelated rows may widen a container
    # column (null -> double, int -> float, list<null> -> list<string>).
    records = [canonical_value(row) for row in frame.to_dict("records")]
    return pd.DataFrame(records, columns=frame.columns).convert_dtypes()


def key_columns(dataset: str) -> list[str]:
    if dataset in {"eod", "indicators"}:
        return ["date"]
    if dataset == "signals":
        return ["symbol_key", "signal_date"]
    raise ManifestInvalidError("Dataset has no approved work-row scope")


def _coordinates(dataset: str, frame: pd.DataFrame) -> list[dict[str, str]]:
    names = key_columns(dataset)
    if not set(names).issubset(frame.columns) or frame[names].isna().any().any():
        raise ManifestInvalidError("Work rows lack complete primary coordinates")
    values = frame[names].copy()
    day = names[-1]
    values[day] = pd.to_datetime(values[day], errors="raise").dt.date.astype(str)
    if "symbol_key" in values:
        values["symbol_key"] = values["symbol_key"].astype(str)
    keys = values.to_dict("records")
    if dataset == "signals" and "model_version" in frame:
        for key, version in zip(keys, frame["model_version"], strict=True):
            if pd.notna(version):
                if not isinstance(version, str) or not version.strip():
                    raise ManifestInvalidError("Invalid signal model version")
                key["model_version"] = version
    return keys


def row_keys(dataset: str, frame: pd.DataFrame) -> list[dict[str, str]]:
    keys = _coordinates(dataset, frame)
    encoded = [json.dumps(k, sort_keys=True, separators=(",", ":")) for k in keys]
    if len(set(encoded)) != len(encoded):
        raise ManifestInvalidError("Duplicate work-row coordinates")
    return [keys[i] for i in sorted(range(len(keys)), key=encoded.__getitem__)]


def select_rows(
    dataset: str, frame: pd.DataFrame, scope: dict[str, Any], columns=None
) -> pd.DataFrame:
    wanted = scope.get("rowKeys")
    if not isinstance(wanted, list) or not wanted:
        raise ManifestInvalidError("Work scope requires bounded row coordinates")
    names = key_columns(dataset)
    for key in wanted:
        allowed = [set(names)]
        if dataset == "signals":
            allowed.append(set(names) | {"model_version"})
        if (
            not isinstance(key, dict)
            or set(key) not in allowed
            or not all(isinstance(v, str) and v.strip() for v in key.values())
        ):
            raise ManifestInvalidError("Invalid work-row coordinates")
        if date.fromisoformat(key[names[-1]]).isoformat() != key[names[-1]]:
            raise ManifestInvalidError("Invalid work-row date")
    wanted_ids = [json.dumps(k, sort_keys=True, separators=(",", ":")) for k in wanted]
    if len(set(wanted_ids)) != len(wanted_ids):
        raise ManifestInvalidError("Duplicate requested work rows")
    if frame.empty or not set(names).issubset(frame.columns):
        raise ManifestInvalidError("Parquet lacks work coordinates")
    # An unversioned coordinate matches only a legacy/null-version row. It must
    # never broaden an old proof to every model at the same symbol/date.
    encoded = pd.Series(
        [
            json.dumps(k, sort_keys=True, separators=(",", ":"))
            for k in _coordinates(dataset, frame)
        ],
        index=frame.index,
    )
    selected = frame.loc[encoded.isin(wanted_ids)].copy()
    if len(selected) != len(wanted) or row_keys(dataset, selected) != sorted(
        wanted, key=lambda k: json.dumps(k, sort_keys=True, separators=(",", ":"))
    ):
        raise ManifestInvalidError(
            "Parquet is missing or duplicates required work data"
        )
    selected = (
        selected.assign(_work_row_key=encoded.loc[selected.index])
        .sort_values("_work_row_key")
        .drop(columns="_work_row_key")
    )
    if columns is not None:
        if not set(columns).issubset(selected.columns):
            raise ManifestInvalidError("Parquet lacks registered work columns")
        selected = selected[list(columns)]
    return canonical_rows(selected.reset_index(drop=True))


def work_version(
    dataset: str,
    partition: dict,
    scope: dict,
    rows: pd.DataFrame,
    schema_hash: str,
    inputs: list,
) -> str:
    # Use the canonical codec on the selected rows, never the container's bytes.
    from py_common.storage.parquet import ParquetCodec

    ordered = select_rows(dataset, rows, scope)
    material = {
        "format": "WORK_ROWS_V1",
        "dataset": dataset,
        "partition": partition,
        "scope": scope,
        "schemaHash": schema_hash,
        "rowChecksum": hashlib.sha256(ParquetCodec.encode(ordered)).hexdigest(),
        "inputs": sorted(
            inputs, key=lambda x: json.dumps(x, sort_keys=True, separators=(",", ":"))
        ),
    }
    return (
        "sha256:"
        + hashlib.sha256(
            json.dumps(
                material, sort_keys=True, separators=(",", ":"), ensure_ascii=True
            ).encode()
        ).hexdigest()
    )


def scope_for_work(
    raw: dict, dataset: str, frame: pd.DataFrame, before: pd.DataFrame | None = None
) -> tuple[dict, pd.DataFrame]:
    day_column = key_columns(dataset)[-1]
    if day_column not in frame or frame[day_column].isna().any():
        raise ManifestInvalidError("Output has no complete calendar-date coverage")
    selected = frame
    if raw["jobType"] == "EVALUATE_SIGNALS":
        if before is not None and not before.empty:
            # Compare canonical row values once, instead of rescanning/encoding the
            # entire shared history for every coordinate (quadratic for large files).
            def value(item):
                normalized = canonical_value(item)
                if isinstance(normalized, (date, datetime)):
                    return normalized.isoformat()
                if isinstance(normalized, list):
                    return [value(x) for x in normalized]
                if isinstance(normalized, dict):
                    return {k: value(v) for k, v in normalized.items()}
                return normalized

            def records(data):
                row_keys(dataset, data)  # Reject duplicate or incomplete coordinates.
                result = {}
                for coordinate, row in zip(
                    _coordinates(dataset, data), data.to_dict("records"), strict=True
                ):
                    key = json.dumps(coordinate, sort_keys=True, separators=(",", ":"))
                    result[key] = json.dumps(
                        value(row),
                        sort_keys=True,
                        separators=(",", ":"),
                        allow_nan=False,
                    )
                return result

            previous, current = records(before), records(frame)
            changed = [
                json.loads(key)
                for key, row in current.items()
                if previous.get(key) != row
            ]
            if not changed:
                raise ManifestInvalidError("Outcome write has no changed work rows")
            selected = select_rows(dataset, frame, {"rowKeys": changed})
    else:
        selected = frame.loc[
            pd.to_datetime(frame[day_column]).dt.date.eq(
                date.fromisoformat(str(raw["businessDate"]))
            )
        ]
        if dataset == "signals":
            selected = selected.loc[
                selected["symbol_key"].astype(str).eq(raw["workKey"])
            ]
    if selected.empty:
        raise ManifestInvalidError(
            f"Output does not cover work/date {raw['workKey']}/{raw['businessDate']}"
        )
    keys = row_keys(dataset, selected)
    scope = {"atomicWorkId": raw["atomicWorkId"], "rowKeys": keys}
    return scope, select_rows(dataset, selected, scope)


def validate_replacement(
    dataset: str, before: pd.DataFrame | None, after: pd.DataFrame, scope: dict
) -> None:
    """A scoped publication cannot silently remove or rewrite another work's rows."""
    if before is None or before.empty:
        return
    owned = {json.dumps(k, sort_keys=True) for k in scope["rowKeys"]}
    other = [
        k
        for k in row_keys(dataset, before)
        if json.dumps(k, sort_keys=True) not in owned
    ]
    if not other:
        return
    previous = select_rows(dataset, before, {"rowKeys": other})
    current = select_rows(dataset, after, {"rowKeys": other}, previous.columns)
    try:
        pd.testing.assert_frame_equal(
            previous, current, check_dtype=False, check_exact=True
        )
    except AssertionError as exc:
        raise ManifestInvalidError(
            "Scoped write would alter data owned by another work"
        ) from exc
