from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import date
from unittest.mock import MagicMock

import pandas as pd
import pytest

from py_common.config.paths import StockDataPaths
from py_common.storage.exceptions import ManifestInvalidError, StorageObjectNotFoundError, StorageWriteError
from py_common.storage.manifest import calculate_data_version
from py_common.storage.output_snapshots import OutputSnapshotIdentity, snapshot_object_name
from py_common.storage.parquet import ParquetCodec, ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry


@pytest.fixture
def anyio_backend():
    return "asyncio"


class MemoryPorts:
    def __init__(self):
        self.objects = {}
        self.writes = []
        self.fail_snapshot = False
        self.drop_snapshot_ack = False
        self.corrupt_snapshot_read = False

    async def read_bytes(self, bucket, object_name):
        if (bucket, object_name) not in self.objects:
            raise StorageObjectNotFoundError(bucket, object_name)
        data = self.objects[bucket, object_name]
        if self.corrupt_snapshot_read and object_name.startswith("_output-snapshots/"):
            return b"corrupt"
        return data

    async def write_bytes(self, bucket, object_name, data, content_type):
        if self.fail_snapshot and object_name.startswith("_output-snapshots/"):
            raise StorageWriteError(bucket, object_name, RuntimeError("injected snapshot write failure"))
        self.writes.append(object_name)
        self.objects[bucket, object_name] = data
        if self.drop_snapshot_ack and object_name.startswith("_output-snapshots/"):
            raise StorageWriteError(bucket, object_name, RuntimeError("injected snapshot ACK loss"))

    async def copy_object(self, bucket, source_object_name, target_object_name, content_type=None):
        self.objects[bucket, target_object_name] = self.objects[bucket, source_object_name]

    async def delete(self, bucket, object_name):
        self.objects.pop((bucket, object_name), None)


def storage(ports):
    registry = MagicMock(spec=StorageProviderRegistry)
    registry.get_port.return_value = ports
    return ParquetStorage(registry, StorageProvider.MINIO, "stock-data")


def frame(close=10):
    return pd.DataFrame({"date": [date(2026, 11, 2)], "close": [close]})


IDENTITY = OutputSnapshotIdentity("eod", {"exchange": "hose", "code": "hpg"})
CANONICAL = "eod/hose/hpg.parquet"


@pytest.mark.anyio
async def test_confirmed_snapshot_preserves_exact_component_versions_after_parquet_roundtrip():
    parquet = storage(MemoryPorts())
    partition = {"exchange": "hose", "timeframe": "1d", "strategy": "confirmed_trend_equals"}
    reference = {"dataset": "signals", "partition": {**partition, "strategy": "trend_momentum_v1"},
                 "dataVersion": "sha256:" + "b" * 64}
    second = {"dataset": "signals", "partition": {**partition, "strategy": "ichimoku_v1"},
              "dataVersion": "sha256:" + "c" * 64}
    frame = pd.DataFrame({"signal_date": [date(2026, 11, 2)], "symbol_key": ["HOSE-HPG"],
                          "eod_data_version": ["sha256:" + "a" * 64],
                          "input_versions": [{"registeredComponents": [reference, second]}]})
    identity = OutputSnapshotIdentity("signals", partition)
    written = await parquet.write_dataframe("signals/confirmed_trend_equals/1d/hose.parquet", frame,
                                             snapshot_identity=identity)
    assert {(i.dataset, i.dataVersion) for i in written.snapshot.inputs} == {
        ("eod", "sha256:" + "a" * 64), ("signals", "sha256:" + "b" * 64),
        ("signals", "sha256:" + "c" * 64)}
    await parquet.read_output_snapshot(identity, written.snapshot)


@pytest.mark.anyio
async def test_late_mutable_write_cannot_change_an_addressed_old_snapshot():
    ports = MemoryPorts()
    parquet = storage(ports)
    first = await parquet.write_dataframe(CANONICAL, frame(10), snapshot_identity=IDENTITY)
    second = await parquet.write_dataframe(CANONICAL, frame(20), snapshot_identity=IDENTITY)
    assert first.snapshot.data_version != second.snapshot.data_version
    await parquet.write_dataframe(CANONICAL, frame(99))
    old = await parquet.read_output_snapshot(IDENTITY, first.snapshot)
    newer = await parquet.read_output_snapshot(IDENTITY, second.snapshot)
    assert old["close"].tolist() == [10]
    assert newer["close"].tolist() == [20]
    assert (await parquet.read_dataframe(CANONICAL))["close"].tolist() == [99]
    assert all("READY" not in name and "_metadata/" not in name for name in ports.writes)


@pytest.mark.anyio
async def test_duplicate_publication_keeps_snapshot_bytes_and_canonical_version_semantics():
    ports = MemoryPorts()
    parquet = storage(ports)
    first = await parquet.write_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    second = await parquet.write_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    assert first.snapshot == second.snapshot
    assert ports.writes.count(first.snapshot.object_name) == 1
    assert first.snapshot.data_version == calculate_data_version(
        "eod", IDENTITY.partition, first.snapshot.schema_hash, [(CANONICAL, first.checksum)], inputs=[]
    )


@pytest.mark.anyio
async def test_conflicting_existing_snapshot_is_rejected_without_overwrite():
    ports = MemoryPorts()
    parquet = storage(ports)
    result = await parquet.write_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    ports.objects["stock-data", result.snapshot.object_name] = b"unexpected bytes"
    writes = len(ports.writes)
    with pytest.raises(ManifestInvalidError, match="conflicts"):
        await parquet.write_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    assert len(ports.writes) == writes
    assert ports.objects["stock-data", result.snapshot.object_name] == b"unexpected bytes"


@pytest.mark.anyio
@pytest.mark.parametrize("failure", ["write", "readback"])
async def test_snapshot_failure_preserves_mutable_output_and_has_no_ready_side_effect(failure):
    ports = MemoryPorts()
    parquet = storage(ports)
    await parquet.write_dataframe(CANONICAL, frame(5))
    old = ports.objects["stock-data", CANONICAL]
    ports.fail_snapshot = failure == "write"
    ports.corrupt_snapshot_read = failure == "readback"
    with pytest.raises((StorageWriteError, ManifestInvalidError)):
        await parquet.write_dataframe(CANONICAL, frame(6), snapshot_identity=IDENTITY)
    assert ports.objects["stock-data", CANONICAL] == old


@pytest.mark.anyio
async def test_lost_ack_propagates_without_a_recovery_journal_or_hidden_retry():
    ports = MemoryPorts()
    ports.drop_snapshot_ack = True
    parquet = storage(ports)
    with pytest.raises(StorageWriteError):
        await parquet.write_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    assert len(ports.writes) == 1
    assert ("stock-data", CANONICAL) not in ports.objects
    assert list(ports.objects)[0][1].startswith("_output-snapshots/")


@pytest.mark.anyio
async def test_corruption_and_cross_partition_read_fail_closed():
    ports = MemoryPorts()
    parquet = storage(ports)
    result = await parquet.write_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    other = OutputSnapshotIdentity("eod", {"exchange": "hose", "code": "fpt"})
    with pytest.raises(ManifestInvalidError, match="logical identity"):
        await parquet.read_output_snapshot(other, result.snapshot)
    ports.objects["stock-data", result.snapshot.object_name] = ParquetCodec.encode(frame(99))
    with pytest.raises(ManifestInvalidError, match="registered version"):
        await parquet.read_output_snapshot(IDENTITY, result.snapshot)


@pytest.mark.anyio
async def test_concurrent_versions_and_duplicate_writers_preserve_every_snapshot():
    ports = MemoryPorts()
    parquet = storage(ports)
    results = await asyncio.gather(*(parquet.write_dataframe(
        CANONICAL, frame(value), snapshot_identity=IDENTITY
    ) for value in [10, 20, 10]))
    for result, expected in zip(results, [10, 20, 10], strict=True):
        assert (await parquet.read_output_snapshot(IDENTITY, result.snapshot))["close"].tolist() == [expected]


@pytest.mark.anyio
async def test_replacement_snapshot_uses_final_identity_and_validated_staging_bytes():
    ports = MemoryPorts()
    parquet = storage(ports)
    result = await parquet.replace_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    assert result.snapshot.canonical_object_name == CANONICAL
    assert ".tmp/" not in result.snapshot.object_name
    assert ports.objects["stock-data", CANONICAL] == ports.objects["stock-data", result.snapshot.object_name]
    assert (await parquet.read_output_snapshot(IDENTITY, result.snapshot))["close"].tolist() == [10]


@pytest.mark.anyio
async def test_missing_derived_lineage_cannot_publish_snapshot_or_mutable_bytes():
    ports = MemoryPorts()
    parquet = storage(ports)
    identity = OutputSnapshotIdentity("indicators", {"source": "ad_close", "timeframe": "1d", **IDENTITY.partition})
    with pytest.raises(ManifestInvalidError, match="lineage"):
        await parquet.write_dataframe("indicators/ad_close/1d/hose/hpg.parquet", frame(), snapshot_identity=identity)
    assert not ports.writes


@pytest.mark.anyio
async def test_derived_snapshot_tracks_all_persisted_input_versions():
    ports = MemoryPorts()
    parquet = storage(ports)
    identity = OutputSnapshotIdentity("indicators", {"source": "ad_close", "timeframe": "1d", **IDENTITY.partition})
    data = pd.DataFrame({"date": [date(2026, 10, 30), date(2026, 11, 2)], "value": [1, 2],
                         "eod_data_version": ["sha256:" + "a" * 64, "sha256:" + "b" * 64]})
    result = await parquet.write_dataframe("indicators/ad_close/1d/hose/hpg.parquet", data, snapshot_identity=identity)
    assert {item.dataVersion for item in result.snapshot.inputs} == {"sha256:" + c * 64 for c in "ab"}
    assert len(await parquet.read_output_snapshot(identity, result.snapshot)) == 2


def test_snapshot_paths_are_order_independent_and_reject_unsafe_configuration():
    paths = StockDataPaths.from_config({})
    version = "sha256:" + "a" * 64
    swapped = OutputSnapshotIdentity("eod", {"code": "hpg", "exchange": "hose"})
    assert snapshot_object_name(IDENTITY, version, paths) == snapshot_object_name(swapped, version, paths)
    with pytest.raises(ManifestInvalidError):
        snapshot_object_name(IDENTITY, version, replace(paths, output_snapshots_base="../"))
    with pytest.raises(ValueError):
        snapshot_object_name(IDENTITY, version, replace(paths, output_snapshots_pattern="{dataset}.parquet"))


@pytest.mark.parametrize("dataset", ["../eod", "EOD", "", None])
def test_snapshot_dataset_cannot_escape_configured_namespace(dataset):
    with pytest.raises(ManifestInvalidError, match="dataset"):
        OutputSnapshotIdentity(dataset, IDENTITY.partition)


def test_snapshot_address_requires_a_complete_content_version():
    with pytest.raises(ManifestInvalidError, match="dataVersion"):
        snapshot_object_name(IDENTITY, "sha256:short", StockDataPaths.from_config({}))


@pytest.mark.anyio
@pytest.mark.parametrize("change", ["schema", "empty", "unsafe"])
async def test_invalid_snapshot_publication_never_writes_bytes(change):
    from py_common.storage.output_snapshots import OutputSnapshotStore

    ports = MemoryPorts()
    paths = StockDataPaths.from_config({})
    data, schema, canonical = b"parquet-bytes", "sha256:" + "a" * 64, CANONICAL
    if change == "schema":
        schema = "unknown"
    elif change == "empty":
        data = b""
    elif change == "unsafe":
        canonical = "../eod.parquet"
    store = OutputSnapshotStore(ports, ports, "stock-data", paths)
    with pytest.raises((ManifestInvalidError, ValueError)):
        await store.publish(identity=IDENTITY, canonical_object_name=canonical,
                            data=data, schema_hash=schema)
    assert not ports.writes


@pytest.mark.anyio
async def test_historical_confirmed_rows_without_component_provenance_cannot_publish_ready_snapshot():
    ports = MemoryPorts()
    parquet = storage(ports)
    partition = {"strategy": "confirmed_trend_equals", "timeframe": "1d", "exchange": "hose"}
    frame = pd.DataFrame({"signal_date": [date(2026, 11, 2)], "symbol_key": ["HOSE-HPG"],
                          "eod_data_version": ["sha256:" + "a" * 64]})
    with pytest.raises(ManifestInvalidError, match="complete persisted component lineage"):
        await parquet.write_dataframe("signals/confirmed_trend_equals/1d/hose.parquet", frame,
                                      snapshot_identity=OutputSnapshotIdentity("signals", partition))
    assert not ports.writes


def bound_manifest(identity, snapshot, *, legacy=False):
    from types import SimpleNamespace
    return SimpleNamespace(status="READY", values=identity.partition, dataVersion=snapshot.data_version,
        schemaHash=snapshot.schema_hash, inputs=snapshot.inputs,
        path=snapshot.canonical_object_name if legacy else snapshot.object_name,
        canonicalPath=None if legacy else snapshot.canonical_object_name)


@pytest.mark.anyio
@pytest.mark.parametrize("replace_during_scan", [False, True])
async def test_backfill_snapshots_exact_bound_bytes_and_rejects_concurrent_mutable_replacement(replace_during_scan):
    ports = MemoryPorts()
    parquet = storage(ports)
    written = await parquet.write_dataframe(CANONICAL, frame(), snapshot_identity=IDENTITY)
    manifest = bound_manifest(IDENTITY, written.snapshot, legacy=True)
    original_read = ports.read_bytes
    reads = 0
    async def read(bucket, path):
        nonlocal reads
        if path == CANONICAL:
            reads += 1
            if replace_during_scan and reads == 2:
                return ParquetCodec.encode(frame(99))
        return await original_read(bucket, path)
    ports.read_bytes = read
    if replace_during_scan:
        with pytest.raises(ManifestInvalidError, match="changed while creating backfill"):
            await parquet.snapshot_bound_output("eod", manifest)
    else:
        snapshot, actual = await parquet.snapshot_bound_output("eod", manifest)
        assert snapshot == written.snapshot
        assert actual["close"].tolist() == [10]
    assert (await parquet.read_output_snapshot(IDENTITY, written.snapshot))["close"].tolist() == [10]


@pytest.mark.anyio
@pytest.mark.parametrize("invalid", ["schema", "lineage"])
async def test_self_consistent_storage_versions_cannot_hide_wrong_parquet_schema_or_lineage(invalid):
    from py_common.storage.manifest import DatasetInput, calculate_schema_hash, extract_schema_from_dataframe
    from py_common.storage.output_snapshots import OutputSnapshotStore

    ports = MemoryPorts()
    parquet = storage(ports)
    identity = OutputSnapshotIdentity("indicators", {"source": "ad_close", "timeframe": "1d", **IDENTITY.partition})
    data = pd.DataFrame({"date": [date(2026, 11, 2)], "eod_data_version": ["sha256:" + "a" * 64]})
    schema = calculate_schema_hash(extract_schema_from_dataframe(data))
    inputs = (DatasetInput("eod", IDENTITY.partition, "sha256:" + ("b" if invalid == "lineage" else "a") * 64),)
    # Lower-level byte storage can be internally consistent while its advertised semantic evidence is wrong.
    candidate = await OutputSnapshotStore(ports, ports, "stock-data", StockDataPaths.from_config({})).publish(
        identity=identity, canonical_object_name="indicators/ad_close/1d/hose/hpg.parquet",
        data=ParquetCodec.encode(data), schema_hash="sha256:" + "c" * 64 if invalid == "schema" else schema,
        inputs=inputs)
    with pytest.raises(ManifestInvalidError, match=f"Snapshot {invalid}"):
        await parquet.read_output_snapshot(identity, candidate)
    with pytest.raises(ManifestInvalidError, match=f"Parquet {invalid}"):
        await parquet.read_bound_dataframe("indicators", bound_manifest(identity, candidate), candidate.data_version)


@pytest.mark.anyio
async def test_empty_snapshot_has_no_bytes_or_registration_evidence():
    ports = MemoryPorts()
    with pytest.raises(ManifestInvalidError, match="empty output snapshot"):
        await storage(ports).write_dataframe(CANONICAL, frame().iloc[:0], snapshot_identity=IDENTITY)
    assert not ports.writes
