"""Accepted physical versions survive stale PUTs, retries and scoped merges."""

import hashlib
from dataclasses import replace
from datetime import date
from types import SimpleNamespace
from unittest.mock import MagicMock

import pandas as pd
import pytest
from minio.versioningconfig import VersioningConfig

from py_common.messaging.atomic_work import atomic_work_id
from py_common.messaging.output_publication import capture_output_publication
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
    StorageReadError,
    StorageWriteError,
)
from py_common.storage.global_metadata import (
    GlobalColumnMetadata,
    GlobalDatasetInput,
    GlobalPartitionMetadata,
)
from py_common.storage.manifest import (
    calculate_data_version,
    calculate_schema_hash,
    extract_schema_from_dataframe,
)
from py_common.storage.output_readiness import (
    PostgresMetadataReader,
    ShadowMetadataReader,
    read_input_document,
)
from py_common.storage.output_snapshots import OutputSnapshotIdentity
from py_common.storage.parquet import ParquetCodec, ParquetStorage
from py_common.storage.providers import StorageProvider

DAY = date(2026, 11, 2)
PARTITION = {"exchange": "hose", "code": "hpg"}
PATH = "eod/hose/hpg.parquet"
IDENTITY = OutputSnapshotIdentity("eod", PARTITION)


class VersionedMemory:
    def __init__(self):
        self.objects, self.versions = {}, {}
        self.fail_write = False
        self.corrupt_readback = False

    async def read_bytes(self, bucket, object_name):
        if object_name not in self.objects:
            raise StorageObjectNotFoundError(bucket, object_name)
        return self.objects[object_name]

    async def write_bytes(self, bucket, object_name, data, content_type):
        self.objects[object_name] = data

    async def read_versioned_bytes(self, bucket, object_name, version_id):
        if (object_name, version_id) not in self.versions:
            raise StorageObjectNotFoundError(bucket, object_name)
        data = self.versions[object_name, version_id]
        if self.corrupt_readback:
            frame = ParquetCodec.decode(data)
            frame["close"] = -999
            return ParquetCodec.encode(frame)
        return data

    async def write_versioned_bytes(self, bucket, object_name, data, content_type):
        if self.fail_write:
            raise StorageWriteError(bucket, object_name, RuntimeError("upload failed"))
        version = f"version-{len(self.versions) + 1}"
        self.versions[object_name, version] = data
        self.objects[object_name] = data
        return version

    async def delete(self, bucket, object_name):
        self.objects.pop(object_name, None)


def storage():
    backend = VersionedMemory()
    parquet = ParquetStorage(
        SimpleNamespace(get_port=lambda *args: backend), StorageProvider.MINIO, "test"
    )
    return backend, parquet


def raw(version=None, generation=None, day=DAY):
    return {
        "jobType": "SYNC_STOCK_PRICE",
        "workType": "SYMBOL",
        "workKey": "HOSE-HPG",
        "executionId": "00000000-0000-0000-0000-000000000001",
        "businessDate": day.isoformat(),
        "parameters": {},
        "atomicWorkId": atomic_work_id(
            "SYNC_STOCK_PRICE", "SYMBOL", "HOSE-HPG", day, {}
        ),
        "metadata": {
            "approvedInputs": [],
            "outputCompletion": {
                "atomicAttemptId": "00000000-0000-0000-0000-000000000002",
                "eventId": "00000000-0000-0000-0000-000000000003",
                "registrationId": "00000000-0000-0000-0000-000000000004",
                "requiredOutputs": [{"dataset": "eod", "partition": PARTITION}],
                "storagePolicy": "VERSIONED",
                "storageBases": [
                    {
                        "dataset": "eod",
                        "partition": PARTITION,
                        "storageVersionId": version,
                        "generation": generation,
                    }
                ],
            },
        },
    }


def manifest(output):
    return GlobalPartitionMetadata(
        values=PARTITION,
        status="READY",
        path=PATH,
        canonicalPath=PATH,
        dataVersion=output.data_version,
        schemaVersion=1,
        schemaHash=output.schema_hash,
        objectCount=1,
        totalBytes=output.total_bytes,
        rowCount=output.row_count,
        columnCount=len(output.columns),
        columns=[
            GlobalColumnMetadata(c.name, c.type, c.nullable) for c in output.columns
        ],
        inputs=[
            GlobalDatasetInput(i.dataset, i.partition, i.data_version)
            for i in output.inputs
        ],
        generatedAt="2026-11-02T00:00:00Z",
        coverageDates=[d.isoformat() for d in output.coverage_dates],
        workScope=output.work_scope.model_dump(mode="json", by_alias=True),
        storageVersionId=output.storage_version_id,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("replace_output", [False, True])
async def test_accepted_version_survives_late_writer_and_future_merge_preserves_history(
    replace_output,
):
    backend, parquet = storage()
    before = pd.DataFrame({"date": [DAY, date(2026, 10, 30)], "close": [10, 9]})
    base = await backend.write_versioned_bytes(
        "test", PATH, ParquetCodec.encode(before), "parquet"
    )
    candidate = before.copy()
    candidate.loc[
        1, "close"
    ] = -666  # stale/unowned caller state must not overwrite history
    candidate.loc[0, "close"] = 777
    with capture_output_publication(raw(base, 1), False) as publication:
        await (
            parquet.replace_dataframe(PATH, candidate)
            if replace_output
            else parquet.write_dataframe(PATH, candidate)
        )
        output = next(iter(publication.outputs.values()))
        assert output.storage_version_id != base
    pinned = manifest(output)
    backend.objects[PATH] = ParquetCodec.encode(before.assign(close=[444, 444]))
    checked = await parquet.read_bound_dataframe("eod", pinned, pinned.dataVersion)
    assert checked["close"].tolist() == [9, 777]
    with capture_output_publication(raw(output.storage_version_id, 2), False):
        captured = await parquet.read_optional_dataframe(PATH)
        assert captured["close"].tolist() == [9, 777]
        await parquet.write_dataframe(PATH, captured)
    assert ParquetCodec.decode(backend.objects[PATH])["close"].tolist() == [9, 777]


@pytest.mark.asyncio
async def test_cold_bootstrap_registers_only_work_day_and_pins_exact_uploaded_version():
    backend, parquet = storage()
    frame = pd.DataFrame({"date": [date(2026, 10, 30), DAY], "close": [9, 10]})
    with capture_output_publication(raw(), False) as publication:
        await parquet.write_dataframe(PATH, frame)
        output = next(iter(publication.outputs.values()))
    assert output.coverage_dates == [DAY]
    assert output.row_count == 1
    assert (
        len(ParquetCodec.decode(backend.versions[PATH, output.storage_version_id])) == 2
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    [
        "missing-list",
        "wrong-partition",
        "duplicate",
        "bad-generation",
        "partial",
        "missing-version",
        "unpinned-existing",
        "write",
        "readback",
    ],
)
async def test_invalid_base_or_upload_never_confirms_a_ready_output(failure):
    backend, parquet = storage()
    frame = pd.DataFrame({"date": [DAY], "close": [10]})
    base = await backend.write_versioned_bytes(
        "test", PATH, ParquetCodec.encode(frame), "parquet"
    )
    command = raw(base, 1)
    contract = command["metadata"]["outputCompletion"]
    if failure == "missing-list":
        contract.pop("storageBases")
    if failure == "wrong-partition":
        contract["storageBases"][0]["partition"] = {"exchange": "hnx", "code": "hpg"}
    if failure == "duplicate":
        contract["storageBases"] *= 2
    if failure == "bad-generation":
        contract["storageBases"][0]["generation"] = True
    if failure == "partial":
        contract["storageBases"][0]["storageVersionId"] = None
    if failure == "missing-version":
        backend.versions.clear()
    if failure == "unpinned-existing":
        command = raw()
    backend.fail_write = failure == "write"
    with capture_output_publication(command, False) as publication:
        if failure == "readback":
            original = backend.write_versioned_bytes

            async def corrupt_after_put(*args):
                version = await original(*args)
                backend.corrupt_readback = True
                return version

            backend.write_versioned_bytes = corrupt_after_put
        with pytest.raises((ManifestInvalidError, StorageWriteError)):
            await parquet.write_dataframe(PATH, frame)
        assert publication.outputs == {}


@pytest.mark.asyncio
async def test_multi_proof_reads_frozen_versions_without_changing_logical_hash():
    backend, parquet = storage()
    outputs = []
    for day, price in [(DAY, 777), (date(2026, 11, 3), 888)]:
        # Independent versions intentionally do not include each other's work rows.
        frame = pd.DataFrame({"date": [day], "close": [price]})
        backend.objects.clear()
        with capture_output_publication(raw(day=day), False) as publication:
            await parquet.write_dataframe(PATH, frame)
            outputs.append(next(iter(publication.outputs.values())))
    partitions = {d.name: [] for d in OMNI_DATASET_REGISTRY.all()}
    document = PostgresMetadataReader._document(
        {**partitions, "eod": [manifest(o) for o in outputs]}
    )
    composite = document.resolve("eod", PARTITION)
    alternate = PostgresMetadataReader._document(
        {
            **partitions,
            "eod": [
                replace(manifest(o), storageVersionId=f"replica-{i}")
                for i, o in enumerate(outputs)
            ],
        }
    )
    assert composite.dataVersion == alternate.resolve("eod", PARTITION).dataVersion
    backend.objects[PATH] = ParquetCodec.encode(
        pd.DataFrame({"date": [DAY], "close": [444]})
    )
    checked = await parquet.read_bound_dataframe(
        "eod", composite, composite.dataVersion
    )
    assert sorted(checked["close"].tolist()) == [777, 888]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "state", [None, "Suspended", "excluded", "folder", "enabled", "missing-result"]
)
async def test_minio_versioned_put_requires_enabled_bucket_and_captures_put_result(
    state,
):
    client = MagicMock()
    client.get_bucket_versioning.return_value = VersioningConfig(
        "Enabled"
        if state in {"excluded", "folder", "enabled", "missing-result"}
        else state,
        excluded_prefixes=["eod/"] if state == "excluded" else None,
        exclude_folders=state == "folder",
    )
    client.put_object.return_value = SimpleNamespace(
        version_id=None if state == "missing-result" else "actual-put-version"
    )
    adapter = MinioStorageAdapter(client)
    name = "eod/" if state == "folder" else PATH
    if state == "enabled":
        assert (
            await adapter.write_versioned_bytes("test", name, b"bytes", "parquet")
            == "actual-put-version"
        )
    else:
        with pytest.raises(StorageWriteError):
            await adapter.write_versioned_bytes("test", name, b"bytes", "parquet")
        if state != "missing-result":
            client.put_object.assert_not_called()
    client.stat_object.assert_not_called()
    client.set_bucket_versioning.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "version", ["pinned", "different", "null", "", "space version"]
)
async def test_minio_exact_version_read_checks_response_and_releases_connection(
    version,
):
    client = MagicMock()
    response = client.get_object.return_value
    response.headers = {"x-amz-version-id": version}
    response.read.return_value = b"accepted"
    adapter = MinioStorageAdapter(client)
    if version == "pinned":
        assert await adapter.read_versioned_bytes("test", PATH, "pinned") == b"accepted"
    else:
        with pytest.raises(StorageReadError):
            await adapter.read_versioned_bytes("test", PATH, "pinned")
    client.get_object.assert_called_once_with("test", PATH, version_id="pinned")
    response.close.assert_called_once()
    response.release_conn.assert_called_once()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "scenario",
    ["success", "unapproved", "missing", "empty", "changes", "wrong-proof", "readback"],
)
async def test_historical_pin_validates_approval_stability_proofs_and_exact_readback(
    scenario,
):
    backend, parquet = storage()
    frame = pd.DataFrame({"date": [DAY], "close": [777]})
    with capture_output_publication(raw(), False) as publication:
        await parquet.write_dataframe(PATH, frame)
        accepted = manifest(next(iter(publication.outputs.values())))
    if scenario == "missing":
        backend.objects.clear()
    if scenario == "empty":
        backend.objects[PATH] = ParquetCodec.encode(frame.iloc[:0])
    if scenario == "wrong-proof":
        backend.objects[PATH] = ParquetCodec.encode(frame.assign(close=444))
    if scenario == "changes":
        original = backend.read_bytes
        calls = 0

        async def changed(*args):
            nonlocal calls
            calls += 1
            return (
                await original(*args)
                if calls == 1
                else ParquetCodec.encode(frame.assign(close=444))
            )

        backend.read_bytes = changed
    if scenario == "readback":
        backend.corrupt_readback = True
    if scenario == "success":
        result = await parquet.pin_historical_output(
            IDENTITY, accepted, allow_unproven_history=True
        )
        assert result["storageVersionId"] != accepted.storageVersionId
        assert result["checksum"].startswith("sha256:")
    else:
        with pytest.raises((ManifestInvalidError, StorageObjectNotFoundError)):
            await parquet.pin_historical_output(
                IDENTITY,
                accepted if scenario == "wrong-proof" else None,
                allow_unproven_history=scenario != "unapproved",
            )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "invalid", [None, 42, "", "null", "two words", "x" * 1025, "vé"]
)
async def test_minio_invalid_version_never_issues_an_unversioned_get(invalid):
    client = MagicMock()
    with pytest.raises(StorageReadError, match="immutable storage version"):
        await MinioStorageAdapter(client).read_versioned_bytes("test", PATH, invalid)
    client.get_object.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("code", ["NoSuchVersion", "AccessDenied", "runtime"])
async def test_minio_version_lookup_failure_has_no_mutable_fallback(code):
    from minio.error import S3Error

    client = MagicMock()
    client.get_object.side_effect = (
        RuntimeError("unavailable")
        if code == "runtime"
        else S3Error(
            code=code,
            message=code,
            resource=PATH,
            request_id="fixture",
            host_id="fixture",
            response=None,
        )
    )
    error = StorageObjectNotFoundError if code == "NoSuchVersion" else StorageReadError
    with pytest.raises(error):
        await MinioStorageAdapter(client).read_versioned_bytes("test", PATH, "pinned")
    client.get_object.assert_called_once_with("test", PATH, version_id="pinned")


@pytest.mark.asyncio
@pytest.mark.parametrize("missing", ["base", "proof"])
async def test_partial_pinned_metadata_never_reads_mutable_bytes(missing):
    from unittest.mock import AsyncMock

    backend, parquet = storage()
    frame = pd.DataFrame({"date": [DAY], "close": [777]})
    with capture_output_publication(raw(), False) as publication:
        await parquet.write_dataframe(PATH, frame)
        proof = manifest(next(iter(publication.outputs.values())))
    scoped = {"workScope": proof.workScope, "storageVersionId": proof.storageVersionId}
    if missing == "proof":
        scoped.pop("storageVersionId")
    candidate = replace(
        proof,
        workScope=None,
        workProofs=[scoped],
        storageVersionId=None if missing == "base" else proof.storageVersionId,
    )
    backend.read_bytes = AsyncMock(side_effect=AssertionError("mutable fallback"))
    with pytest.raises(ManifestInvalidError, match="Incomplete immutable"):
        await parquet.read_bound_dataframe("eod", candidate, candidate.dataVersion)
    backend.read_bytes.assert_not_awaited()


def legacy_manifest(frame, *, path=PATH, partition=None):
    partition = PARTITION if partition is None else partition
    data = ParquetCodec.encode(frame)
    columns = extract_schema_from_dataframe(ParquetCodec.decode(data))
    schema = calculate_schema_hash(columns)
    version = calculate_data_version(
        "eod",
        partition,
        schema,
        [(path, "sha256:" + hashlib.sha256(data).hexdigest())],
        inputs=[],
    )
    return GlobalPartitionMetadata(
        values=partition,
        status="READY",
        path=path,
        dataVersion=version,
        schemaVersion=1,
        schemaHash=schema,
        objectCount=1,
        totalBytes=len(data),
        rowCount=len(frame),
        columnCount=len(columns),
        columns=[GlobalColumnMetadata(c.name, c.type, c.nullable) for c in columns],
        generatedAt="2026-11-02T00:00:00Z",
        coverageDates=sorted({str(d)[:10] for d in frame["date"]}),
    )


def document(manifests):
    return PostgresMetadataReader._document(
        {**{d.name: [] for d in OMNI_DATASET_REGISTRY.all()}, "eod": manifests}
    )


@pytest.mark.asyncio
async def test_shadow_enrolled_inputs_use_frozen_postgres_without_legacy_reads():
    from unittest.mock import AsyncMock

    postgres = PostgresMetadataReader("http://platform/readiness", "fixture")
    postgres.read_for = AsyncMock(return_value="exact-frozen-work")
    legacy = AsyncMock()
    shadow = ShadowMetadataReader(legacy, postgres)
    assert (
        await read_input_document(shadow, {"outputCompletion": {}})
        == "exact-frozen-work"
    )
    legacy.read.assert_not_awaited()
    postgres.read_for.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "scenario",
    [
        "success",
        "other-row",
        "owned-row",
        "missing-row",
        "columns",
        "rowcount",
        "pinned-corrupt",
        "storage-failure",
        "no-validator",
        "extra-partition",
        "legacy-baseline",
        "legacy-conflict",
        "only-baselines",
    ],
)
async def test_shadow_compares_validated_ready_rows_without_certifying_unowned_history(
    scenario,
):
    from unittest.mock import AsyncMock

    backend, parquet = storage()
    frame = pd.DataFrame({"date": [date(2026, 10, 30), DAY], "close": [9, 777]})
    with capture_output_publication(raw(), False) as publication:
        await parquet.write_dataframe(PATH, frame)
        pinned = manifest(next(iter(publication.outputs.values())))
    current = frame.copy()
    if scenario == "other-row":
        current.loc[0, "close"] = 999
    if scenario == "owned-row":
        current.loc[1, "close"] = 444
    if scenario == "missing-row":
        current = current.iloc[:1]
    if scenario == "columns":
        current = current.rename(columns={"close": "another_column"})
    backend.objects[PATH] = ParquetCodec.encode(current)
    legacy = legacy_manifest(current)
    if scenario == "rowcount":
        legacy = replace(legacy, rowCount=999)
    if scenario == "pinned-corrupt":
        backend.corrupt_readback = True
    old, new = [legacy], [pinned]
    if scenario in {"extra-partition", "legacy-baseline", "legacy-conflict"}:
        path = "eod/hose/fpt.parquet"
        backend.objects[path] = ParquetCodec.encode(frame)
        baseline = legacy_manifest(
            frame, path=path, partition={"exchange": "hose", "code": "fpt"}
        )
        old.append(baseline)
        if scenario != "extra-partition":
            new.append(
                replace(baseline, dataVersion="sha256:" + "c" * 64)
                if scenario == "legacy-conflict"
                else baseline
            )
    if scenario == "only-baselines":
        new = old
    legacy_document, postgres_document = document(old), document(new)
    if scenario == "storage-failure":
        backend.read_bytes = AsyncMock(
            side_effect=StorageReadError("test", PATH, RuntimeError("offline"))
        )
    shadow = ShadowMetadataReader(
        AsyncMock(read=AsyncMock(return_value=legacy_document)),
        AsyncMock(read=AsyncMock(return_value=postgres_document)),
        parquet=None if scenario == "no-validator" else parquet,
    )
    assert await shadow.read() is legacy_document
    result = shadow.last_comparison
    if scenario in {"no-validator", "pinned-corrupt", "storage-failure"}:
        assert result["compared"] is False and result["matches"] is False
        assert result["reason"] == (
            "SCOPED_PARITY_REQUIRES_BYTES"
            if scenario == "no-validator"
            else "PARITY_INPUT_INVALID"
        )
    else:
        assert result["compared"] is True
        assert result["matches"] is (
            scenario in {"success", "other-row", "legacy-baseline", "only-baselines"}
        )
        assert result["scope"] == (
            "PARTITIONS" if scenario == "only-baselines" else "WORK_ROWS_V1"
        )
        if result["matches"] and scenario != "only-baselines":
            assert result["comparedRows"] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("difference", ["dataset", "partition", "version"])
async def test_shadow_requires_exact_input_lineage_membership(difference):
    from unittest.mock import AsyncMock

    backend, parquet = storage()
    frame = pd.DataFrame({"date": [DAY], "close": [777]})
    with capture_output_publication(raw(), False) as publication:
        await parquet.write_dataframe(PATH, frame)
        ready = manifest(next(iter(publication.outputs.values())))
    original = GlobalDatasetInput("eod", PARTITION, "sha256:" + "a" * 64)
    changed = replace(
        original,
        dataset="indicators" if difference == "dataset" else original.dataset,
        partition={"exchange": "hose", "code": "fpt"}
        if difference == "partition"
        else original.partition,
        dataVersion="sha256:" + "b" * 64
        if difference == "version"
        else original.dataVersion,
    )
    legacy = replace(legacy_manifest(frame), inputs=[original])
    ready = replace(ready, inputs=[changed])
    # This unit isolates the comparison boundary; byte validation itself is
    # exercised by the real storage cases above and must run before comparison.
    validator = AsyncMock(read_bound_dataframe=AsyncMock(return_value=frame))
    old = document([legacy])
    shadow = ShadowMetadataReader(
        AsyncMock(read=AsyncMock(return_value=old)),
        AsyncMock(read=AsyncMock(return_value=document([ready]))),
        parquet=validator,
    )
    assert await shadow.read() is old
    assert shadow.last_comparison["compared"] is True
    assert shadow.last_comparison["matches"] is False
    assert validator.read_bound_dataframe.await_count == 2
