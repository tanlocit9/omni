import numpy as np
import pandas as pd
import pytest

from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.output_lineage import extract_output_lineage
from py_common.storage.parquet import ParquetCodec


VALUES = {"exchange": "hose", "timeframe": "1d", "strategy": "CONFIRMED_TREND_EQUALS"}
VERSION = "sha256:" + "a" * 64


def signal_frame(references):
    return pd.DataFrame({"symbol_key": ["HOSE-HPG"], "eod_data_version": [VERSION],
                         "input_versions": [{"registeredComponents": references}]})


def component():
    return {"dataset": "signals", "partition": {"exchange": "hose", "timeframe": "1d",
            "strategy": "trend_momentum_v1"}, "dataVersion": VERSION}


def test_confirmed_component_versions_survive_real_parquet_round_trip_and_deduplicate():
    frame = signal_frame([component(), component()])
    persisted = ParquetCodec.decode(ParquetCodec.encode(frame))
    inputs = extract_output_lineage("signals", persisted, VALUES)
    assert {item.dataset for item in inputs} == {"eod", "signals"}
    assert len(inputs) == 2
    assert all(item.dataVersion == VERSION for item in inputs)


@pytest.mark.parametrize("references", ["malformed", [None], [{"dataset": "eod"}],
    [{**component(), "partition": {"exchange": "hnx", "timeframe": "1d", "strategy": "trend_momentum_v1"}}],
    [{**component(), "dataVersion": "unknown"}]])
def test_unverifiable_persisted_components_cannot_become_registered_lineage(references):
    with pytest.raises(ManifestInvalidError):
        extract_output_lineage("signals", signal_frame(references), VALUES)


def test_numpy_materialized_components_are_supported_without_losing_versions():
    inputs = extract_output_lineage("signals", signal_frame(np.array([component()], dtype=object)), VALUES)
    assert len(inputs) == 2


def test_invalid_symbol_or_unknown_dataset_cannot_create_derived_lineage():
    frame = signal_frame([])
    frame["symbol_key"] = "HPG"
    with pytest.raises(ManifestInvalidError, match="symbol_key"):
        extract_output_lineage("signals", frame, VALUES)
    with pytest.raises(ManifestInvalidError, match="no approved"):
        extract_output_lineage("unknown", frame, VALUES)


@pytest.mark.parametrize("references", [None, [], [component()], [component(), component()]])
def test_snapshot_lineage_requires_both_confirmed_components_without_relabeling_legacy(references):
    frame = signal_frame(references)
    # Existing manifest discovery remains readable; that is not proof for a new snapshot.
    assert extract_output_lineage("signals", frame, VALUES)
    with pytest.raises(ManifestInvalidError, match="complete persisted component lineage"):
        extract_output_lineage("signals", frame, VALUES, require_complete_components=True)


def test_complete_confirmed_lineage_survives_parquet_in_strict_snapshot_mode():
    second = {**component(), "partition": {**component()["partition"], "strategy": "ichimoku_v1"}}
    frame = ParquetCodec.decode(ParquetCodec.encode(signal_frame([component(), second])))
    inputs = extract_output_lineage("signals", frame, VALUES, require_complete_components=True)
    assert len(inputs) == 3
    assert {item.partition.get("strategy") for item in inputs if item.dataset == "signals"} == {
        "trend_momentum_v1", "ichimoku_v1"}
