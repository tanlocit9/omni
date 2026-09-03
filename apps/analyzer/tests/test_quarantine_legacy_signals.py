from __future__ import annotations

import pandas as pd
from py_common.storage.parquet import ParquetCodec

from tools.quarantine_legacy_signals import inspect_signal_parquet

_VERSION = f"sha256:{'a' * 64}"


def test_inspection_accepts_complete_authoritative_lineage() -> None:
    payload = ParquetCodec.encode(
        pd.DataFrame(
            {
                "symbol_key": ["HOSE-HPG"],
                "eod_data_version": [_VERSION],
                "indicators_data_version": [_VERSION],
            }
        )
    )

    result = inspect_signal_parquet("signals/strategy/1d/hose.parquet", payload)

    assert result.legacy is False
    assert result.reason is None


def test_inspection_rejects_legacy_signal_without_lineage_columns() -> None:
    payload = ParquetCodec.encode(
        pd.DataFrame({"symbol_key": ["HOSE-HPG"], "signal": ["BULLISH"]})
    )

    result = inspect_signal_parquet("signals/strategy/1d/hose.parquet", payload)

    assert result.legacy is True
    assert result.reason == (
        "missing columns: eod_data_version, indicators_data_version"
    )


def test_inspection_rejects_null_or_malformed_versions() -> None:
    payload = ParquetCodec.encode(
        pd.DataFrame(
            {
                "symbol_key": ["HOSE-HPG"],
                "eod_data_version": [None],
                "indicators_data_version": ["not-a-version"],
            }
        )
    )

    result = inspect_signal_parquet("signals/strategy/1d/hose.parquet", payload)

    assert result.legacy is True
    assert result.reason == "invalid values in eod_data_version"
