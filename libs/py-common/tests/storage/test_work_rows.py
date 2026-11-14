from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pandas as pd
import pytest

from py_common.messaging.atomic_work import atomic_work_id
from py_common.messaging.output_publication import capture_output_publication
from py_common.storage.exceptions import (
    ManifestInvalidError,
    StorageObjectNotFoundError,
)
from py_common.storage.global_metadata import (
    GlobalColumnMetadata,
    GlobalPartitionMetadata,
)
from py_common.storage.manifest import (
    calculate_schema_hash,
    extract_schema_from_dataframe,
)
from py_common.storage.output_readiness import PostgresMetadataReader
from py_common.storage.parquet import ParquetCodec, ParquetStorage
from py_common.storage.ports import (
    CopyableStorage,
    DeletableStorage,
    ReadableStorage,
    WritableStorage,
)
from py_common.storage.providers import StorageProvider
from py_common.storage.work_rows import (
    scope_for_work,
    select_rows,
    validate_replacement,
    work_version,
)

DAY = date(2026, 11, 2)
PARTITION = {"exchange": "hose", "code": "hpg"}
WORK = atomic_work_id("SYNC_STOCK_PRICE", "SYMBOL", "HOSE-HPG", DAY, {})
RAW = {
    "jobType": "SYNC_STOCK_PRICE",
    "workType": "SYMBOL",
    "workKey": "HOSE-HPG",
    "executionId": "00000000-0000-0000-0000-000000000001",
    "businessDate": DAY.isoformat(),
    "parameters": {},
    "atomicWorkId": WORK,
    "metadata": {
        "approvedInputs": [],
        "outputCompletion": {
            "eventId": "00000000-0000-0000-0000-000000000002",
            "registrationId": "00000000-0000-0000-0000-000000000003",
            "atomicAttemptId": "00000000-0000-0000-0000-000000000004",
            "requiredOutputs": [{"dataset": "eod", "partition": PARTITION}],
        },
    },
}


def signal_raw(*, evaluation=False, parameters=None):
    from copy import deepcopy

    raw = deepcopy(RAW)
    raw["jobType"] = "EVALUATE_SIGNALS" if evaluation else "SYNC_SIGNALS"
    raw["workType"] = "EXCHANGE" if evaluation else "SYMBOL"
    raw["workKey"] = "HOSE" if evaluation else "HOSE-HPG"
    raw["parameters"] = parameters or {}
    raw["atomicWorkId"] = atomic_work_id(
        raw["jobType"], raw["workType"], raw["workKey"], DAY, raw["parameters"]
    )
    raw["metadata"]["outputCompletion"]["requiredOutputs"] = [
        {
            "dataset": "signals",
            "partition": {
                "exchange": "hose",
                "strategy": "trend_momentum_v1",
                "timeframe": "1d",
            },
        }
    ]
    return raw


@pytest.mark.parametrize("business_day", [DAY.isoformat(), DAY])
def test_work_scope_accepts_wire_and_validated_payload_dates(business_day):
    raw = {**RAW, "businessDate": business_day}
    frame = pd.DataFrame({"date": [date(2026, 11, 1), DAY], "close": [100, 101]})
    scope, selected = scope_for_work(raw, "eod", frame)
    assert scope["atomicWorkId"] == WORK
    assert selected["date"].tolist() == [DAY]
    with pytest.raises(ManifestInvalidError, match="does not cover"):
        scope_for_work(raw, "eod", frame.iloc[:1])


def evidence(frame):
    scope, rows = scope_for_work(RAW, "eod", frame)
    columns = extract_schema_from_dataframe(rows)
    schema = calculate_schema_hash(columns)
    version = work_version("eod", PARTITION, scope, rows, schema, [])
    return GlobalPartitionMetadata(
        values=PARTITION,
        status="READY",
        path="eod/hose/hpg.parquet",
        canonicalPath="eod/hose/hpg.parquet",
        dataVersion=version,
        schemaVersion=1,
        schemaHash=schema,
        objectCount=1,
        totalBytes=100,
        rowCount=len(rows),
        columnCount=len(columns),
        columns=[GlobalColumnMetadata(**vars(c)) for c in columns],
        generatedAt="2026-11-02T00:00:00Z",
        coverageDates=[DAY.isoformat()],
        workScope=scope,
    )


def storage(objects):
    async def read(bucket, name):
        if name not in objects:
            raise StorageObjectNotFoundError(bucket=bucket, object_name=name)
        return objects[name]

    async def write(*, bucket, object_name, data, **kwargs):
        objects[object_name] = data

    async def copy(*, bucket, source_object_name, target_object_name, **kwargs):
        objects[target_object_name] = objects[source_object_name]

    async def delete(bucket=None, object_name=None, **kwargs):
        objects.pop(object_name, None)

    ports = {
        ReadableStorage: SimpleNamespace(read_bytes=AsyncMock(side_effect=read)),
        WritableStorage: SimpleNamespace(write_bytes=AsyncMock(side_effect=write)),
        CopyableStorage: SimpleNamespace(copy_object=AsyncMock(side_effect=copy)),
        DeletableStorage: SimpleNamespace(delete=AsyncMock(side_effect=delete)),
    }
    return ParquetStorage(
        SimpleNamespace(get_port=lambda provider, port: ports[port]),
        StorageProvider.MINIO,
        "data",
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "change", ["append", "other-value", "owned-value", "missing", "duplicate", "schema"]
)
async def test_scoped_reader_ignores_other_rows_but_rejects_changed_owned_data(
    change,
):
    frame = pd.DataFrame({"date": [DAY], "close": [10]})
    proof = evidence(frame)
    candidate = pd.concat(
        [frame, pd.DataFrame({"date": [date(2026, 11, 3)], "close": [20]})],
        ignore_index=True,
    )
    if change == "other-value":
        candidate.loc[1, "close"] = 99
    if change == "owned-value":
        candidate.loc[0, "close"] = 99
    if change == "missing":
        candidate = candidate.iloc[1:]
    if change == "duplicate":
        candidate = pd.concat([candidate, frame], ignore_index=True)
    if change == "schema":
        candidate = candidate.drop(columns="close")
    parquet = storage({proof.path: ParquetCodec.encode(candidate)})
    if change in {"append", "other-value"}:
        actual = await parquet.read_bound_dataframe("eod", proof, proof.dataVersion)
        assert len(actual) == 2  # calculation retains historical context
    else:
        with pytest.raises(ManifestInvalidError):
            await parquet.read_bound_dataframe("eod", proof, proof.dataVersion)


@pytest.mark.asyncio
@pytest.mark.parametrize("replace", [False, True])
async def test_enrolled_canonical_write_registers_only_work_rows_without_snapshot(
    replace,
):
    path = "eod/hose/hpg.parquet"
    frame = pd.DataFrame({"date": [date(2026, 10, 30), DAY], "close": [9, 10]})
    objects = {path: ParquetCodec.encode(frame.iloc[:1])}
    parquet = storage(objects)
    with capture_output_publication(RAW, False) as publication:
        if replace:
            await parquet.replace_dataframe(path, frame)
        else:
            await parquet.write_dataframe(path, frame)
        output = next(iter(publication.outputs.values()))
        assert output.row_count == 1
        assert output.coverage_dates == [DAY]
        assert output.work_scope.atomic_work_id == WORK
        assert all(not p.startswith("_output-snapshots") for p in objects)
        assert output.data_version == evidence(frame).dataVersion


def test_scope_is_not_file_lineage_or_total_history_and_is_order_independent():
    dates = pd.date_range("2010-01-01", DAY).date
    frame = pd.DataFrame({"date": dates, "close": range(len(dates))})
    assert evidence(frame).dataVersion == evidence(frame.iloc[::-1]).dataVersion
    assert evidence(frame).rowCount == 1
    scope, rows = scope_for_work(RAW, "eod", frame)
    assert len(scope["rowKeys"]) == 1
    with pytest.raises(ManifestInvalidError):
        validate_replacement("eod", frame, rows, scope)
    changed = frame.copy()
    changed.loc[0, "close"] = -1
    with pytest.raises(ManifestInvalidError, match="another work"):
        validate_replacement("eod", frame, changed, scope)


def test_outcome_scope_contains_only_modified_symbol_dates():
    raw = {**RAW, "jobType": "EVALUATE_SIGNALS"}
    before = pd.DataFrame(
        {
            "symbol_key": ["HOSE-HPG", "HOSE-FPT"],
            "signal_date": [DAY, DAY],
            "return": [1, 2],
        }
    )
    after = before.copy()
    after.loc[0, "return"] = 3
    scope, rows = scope_for_work(raw, "signals", after, before)
    assert scope["rowKeys"] == [
        {"symbol_key": "HOSE-HPG", "signal_date": DAY.isoformat()}
    ]
    assert len(rows) == 1
    with pytest.raises(ManifestInvalidError, match="no changed"):
        scope_for_work(raw, "signals", before, before)


@pytest.mark.parametrize(
    "scope",
    [
        {"rowKeys": []},
        {"rowKeys": [{"date": "20261102"}]},
        {"rowKeys": [{"date": "2026-11-02", "extra": "x"}]},
        {"rowKeys": [{"date": "2026-11-02"}] * 2},
    ],
)
def test_invalid_scope_fails_closed(scope):
    with pytest.raises((ManifestInvalidError, ValueError)):
        select_rows("eod", pd.DataFrame({"date": [DAY]}), scope)


def work_reply(frame):
    proof = evidence(frame)
    output = {
        "dataset": "eod",
        "partition": PARTITION,
        "dataVersion": proof.dataVersion,
        "coverageDates": proof.coverageDates,
        "rowCount": proof.rowCount,
        "totalBytes": 100,
        "schemaVersion": 1,
        "schemaHash": proof.schemaHash,
        "columns": [vars(c) for c in proof.columns],
        "inputs": [],
        "workScope": proof.workScope,
    }
    return {
        "atomicWorkId": WORK,
        "items": [
            {
                "atomicWorkId": WORK,
                "atomicAttemptId": "00000000-0000-0000-0000-000000000001",
                "businessDate": DAY.isoformat(),
                "dataVersion": proof.dataVersion,
                "output": output,
                "executionId": "00000000-0000-0000-0000-000000000002",
            }
        ],
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "change",
    [
        "none",
        "missing",
        "attempt",
        "date",
        "version",
        "scope",
        "malformed",
        "unavailable",
    ],
)
async def test_worker_resolves_frozen_work_proof_instead_of_latest_partition(change):
    from copy import deepcopy

    frame = pd.DataFrame({"date": [DAY], "close": [10]})
    payload = work_reply(frame)
    binding = {
        k: payload["items"][0][k]
        for k in ("atomicWorkId", "atomicAttemptId", "businessDate", "dataVersion")
    }
    binding.update(dataset="eod", partition=PARTITION)
    metadata = {"outputCompletion": {}, "approvedInputs": [binding]}
    payload = deepcopy(payload)
    item = payload["items"][0]
    if change == "missing":
        payload["items"] = []
    if change == "attempt":
        item["atomicAttemptId"] = "00000000-0000-0000-0000-000000000009"
    if change == "date":
        item["businessDate"] = "2026-11-03"
    if change == "version":
        item["dataVersion"] = "sha256:" + "0" * 64
    if change == "scope":
        item["output"]["workScope"]["atomicWorkId"] = "work:" + "0" * 64
    if change == "malformed":
        item["output"]["rowCount"] = 3

    def respond(request):
        assert request.url.path.endswith("/work")
        assert request.url.params["atomicWorkId"] == WORK
        assert request.url.params["atomicAttemptId"] == binding["atomicAttemptId"]
        return httpx.Response(503 if change == "unavailable" else 200, json=payload)

    reader = PostgresMetadataReader(
        "http://platform/api/v1/jobs/output-readiness",
        "test",
        transport=httpx.MockTransport(respond),
    )
    if change == "none":
        document = await reader.read_for(metadata)
        proof = document.resolve("eod", PARTITION)
        actual = await storage(
            {proof.path: ParquetCodec.encode(frame)}
        ).read_bound_dataframe("eod", proof, binding["dataVersion"])
        assert len(actual) == 1
    else:
        with pytest.raises(ManifestInvalidError):
            await reader.read_for(metadata)


@pytest.mark.asyncio
async def test_catalog_aggregates_work_proofs_without_claiming_the_whole_file_ready():
    from dataclasses import replace

    from py_common.messaging.approved_inputs import approved_manifest_version

    frame = pd.DataFrame({"date": [DAY, date(2026, 11, 3)], "close": [10, 11]})
    first = evidence(frame)
    raw = {**RAW, "businessDate": "2026-11-03", "atomicWorkId": "work:" + "b" * 64}
    scope, rows = scope_for_work(raw, "eod", frame)
    second = replace(
        first,
        workScope=scope,
        coverageDates=["2026-11-03"],
        dataVersion=work_version("eod", PARTITION, scope, rows, first.schemaHash, []),
    )
    document = PostgresMetadataReader._document(
        {
            "eod": [first, second],
            "indicators": [],
            "signals": [],
            **{
                d.name: []
                for d in __import__(
                    "py_common.storage.dataset_registry",
                    fromlist=["OMNI_DATASET_REGISTRY"],
                ).OMNI_DATASET_REGISTRY.all()
                if d.name not in {"eod", "indicators", "signals"}
            },
        }
    )
    proof = document.resolve("eod", PARTITION)
    assert proof.workScope is None and len(proof.workProofs) == 2
    assert proof.rowCount == 2
    assert proof.dataVersion not in {first.dataVersion, second.dataVersion}
    checked = await storage(
        {proof.path: ParquetCodec.encode(frame)}
    ).read_bound_dataframe("eod", proof, proof.dataVersion)
    assert len(checked) == 2
    bindings = [
        {
            "dataset": "eod",
            "partition": PARTITION,
            "dataVersion": p.dataVersion,
            "atomicWorkId": p.workScope["atomicWorkId"],
            "atomicAttemptId": "00000000-0000-0000-0000-000000000001",
            "businessDate": p.coverageDates[0],
        }
        for p in (first, second)
    ]
    assert (
        approved_manifest_version(
            {"outputCompletion": {}, "approvedInputs": bindings},
            "eod",
            PARTITION,
            proof,
            required=True,
        )
        == proof.dataVersion
    )
    with pytest.raises(ValueError, match="approved work scopes"):
        approved_manifest_version(
            {"outputCompletion": {}, "approvedInputs": bindings[:1]},
            "eod",
            PARTITION,
            proof,
            required=True,
        )


@pytest.mark.asyncio
async def test_signal_nested_roundtrip_and_outcome_scope_use_the_same_version():
    partition = {"exchange": "hose", "strategy": "trend_momentum_v1", "timeframe": "1d"}
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    raw = signal_raw()
    frame = pd.DataFrame(
        {
            "symbol_key": ["HOSE-HPG", "HOSE-FPT"],
            "signal_date": [DAY, DAY],
            "reason_codes": [["TREND"], ["FLAT"]],
            "input_versions": [
                [{"dataset": "eod", "version": "a"}],
                [{"dataset": "eod", "version": "b"}],
            ],
            "actual_return_t5": [None, None],
        }
    )
    objects = {}
    parquet = storage(objects)
    with capture_output_publication(raw, False) as publication:
        await parquet.replace_dataframe(path, frame)
        output = next(iter(publication.outputs.values()))
    proof = GlobalPartitionMetadata(
        values=partition,
        status="READY",
        path=path,
        canonicalPath=path,
        dataVersion=output.data_version,
        schemaVersion=1,
        schemaHash=output.schema_hash,
        objectCount=1,
        totalBytes=output.total_bytes,
        rowCount=1,
        columnCount=len(output.columns),
        columns=[GlobalColumnMetadata(**c.model_dump()) for c in output.columns],
        generatedAt="2026-11-02T00:00:00Z",
        coverageDates=[DAY.isoformat()],
        workScope=output.work_scope.model_dump(by_alias=True),
    )
    checked = await parquet.read_bound_dataframe("signals", proof, proof.dataVersion)
    assert len(checked) == 2
    after = checked.copy()
    after.loc[0, "actual_return_t5"] = 1.25
    evaluation = signal_raw(evaluation=True)
    with capture_output_publication(evaluation, False) as publication:
        await parquet.replace_dataframe(path, after)
        result = next(iter(publication.outputs.values()))
        assert result.row_count == 1
        assert result.work_scope.row_keys == [
            {"symbol_key": "HOSE-HPG", "signal_date": DAY.isoformat()}
        ]


@pytest.mark.asyncio
async def test_unrelated_rows_can_widen_file_types_without_changing_the_work_version():
    frame = pd.DataFrame(
        {"date": [DAY], "close": [10], "nullable_value": [None], "reasons": [[]]}
    )
    proof = evidence(frame)
    after = pd.concat(
        [
            frame,
            pd.DataFrame(
                {
                    "date": [date(2026, 11, 3)],
                    "close": [10.25],
                    "nullable_value": [1.25],
                    "reasons": [["new"]],
                }
            ),
        ],
        ignore_index=True,
    )
    assert evidence(after).dataVersion == proof.dataVersion
    result = await storage(
        {proof.path: ParquetCodec.encode(after)}
    ).read_bound_dataframe("eod", proof, proof.dataVersion)
    assert len(result) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("replace", [False, True])
async def test_versioned_signal_rows_preserve_legacy_and_other_models(replace):
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    before = pd.DataFrame(
        {
            "symbol_key": ["HOSE-HPG"] * 3,
            "signal_date": [DAY] * 3,
            "model_version": [None, "V1", "V2"],
            "score": [1, 2, 3],
        }
    )
    scope, _ = scope_for_work(signal_raw(), "signals", before)
    assert len(scope["rowKeys"]) == 3
    assert {k.get("model_version") for k in scope["rowKeys"]} == {None, "V1", "V2"}
    for version in (None, "V1", "V2"):
        key = {"symbol_key": "HOSE-HPG", "signal_date": DAY.isoformat()}
        if version:
            key["model_version"] = version
        selected = select_rows("signals", before, {"rowKeys": [key]})
        assert selected["score"].tolist() == [{None: 1, "V1": 2, "V2": 3}[version]]
    with pytest.raises(ManifestInvalidError, match="missing or duplicates"):
        select_rows(
            "signals",
            before.iloc[1:],
            {"rowKeys": [{"symbol_key": "HOSE-HPG", "signal_date": DAY.isoformat()}]},
        )
    after = before.copy()
    after.loc[2, "score"] = 4
    objects = {path: ParquetCodec.encode(before)}
    parquet = storage(objects)
    with capture_output_publication(signal_raw(evaluation=True), False) as publication:
        operation = parquet.replace_dataframe if replace else parquet.write_dataframe
        await operation(path, after)
        output = next(iter(publication.outputs.values()))
        assert output.work_scope.row_keys == [
            {
                "symbol_key": "HOSE-HPG",
                "signal_date": DAY.isoformat(),
                "model_version": "V2",
            }
        ]
        await publication.publish(AsyncMock())
    actual = ParquetCodec.decode(objects[path])
    assert actual["score"].tolist() == [1, 2, 4]
    proof = GlobalPartitionMetadata(
        values=output.partition,
        status="READY",
        path=path,
        canonicalPath=path,
        dataVersion=output.data_version,
        schemaVersion=1,
        schemaHash=output.schema_hash,
        objectCount=1,
        totalBytes=output.total_bytes,
        rowCount=1,
        columnCount=len(output.columns),
        columns=[GlobalColumnMetadata(**c.model_dump()) for c in output.columns],
        generatedAt="2026-11-02T00:00:00Z",
        coverageDates=[DAY.isoformat()],
        workScope=output.work_scope.model_dump(by_alias=True),
    )
    changed_other_model = after.copy()
    changed_other_model.loc[1, "score"] = 99
    objects[path] = ParquetCodec.encode(changed_other_model)
    await parquet.read_bound_dataframe("signals", proof, proof.dataVersion)
    changed_owned_model = after.copy()
    changed_owned_model.loc[2, "score"] = 99
    objects[path] = ParquetCodec.encode(changed_owned_model)
    with pytest.raises(ManifestInvalidError, match="approved version"):
        await parquet.read_bound_dataframe("signals", proof, proof.dataVersion)
    damaged = after.copy()
    damaged.loc[1, "score"] = 99
    with pytest.raises(ManifestInvalidError, match="another work"):
        validate_replacement(
            "signals", before, damaged, output.work_scope.model_dump(by_alias=True)
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("replace", [False, True])
@pytest.mark.parametrize("limit", ["rows", "wire", "inputs", "identity"])
async def test_preflight_rejection_keeps_canonical_bytes_and_no_confirmed_output(
    replace, limit
):
    path = "signals/trend_momentum_v1/1d/hose.parquet"
    count = 4097 if limit == "rows" else 1
    before = pd.DataFrame(
        {
            "symbol_key": [f"HOSE-S{i}" for i in range(count)],
            "signal_date": [DAY] * count,
            "score": [1] * count,
        }
    )
    after = before.assign(score=2)
    raw = signal_raw(
        evaluation=True,
        parameters={"padding": "x" * 262144} if limit == "wire" else None,
    )
    if limit == "inputs":
        raw["metadata"]["approvedInputs"] = [
            {
                "dataset": "eod",
                "partition": {"exchange": "hose", "code": f"s{i}"},
                "dataVersion": "sha256:" + "a" * 64,
            }
            for i in range(129)
        ]
    if limit == "identity":
        raw["atomicWorkId"] = "work:" + "0" * 64
    original = ParquetCodec.encode(before)
    objects = {path: original}
    parquet = storage(objects)
    with capture_output_publication(raw, False) as publication:
        with pytest.raises(ValueError):
            if replace:
                await parquet.replace_dataframe(path, after)
            else:
                await parquet.write_dataframe(path, after)
        assert publication.outputs == {}
    assert objects == {path: original}


@pytest.mark.asyncio
@pytest.mark.parametrize("replace", [False, True])
async def test_prepared_output_is_not_confirmed_when_canonical_readback_differs(
    replace,
):
    path = "eod/hose/hpg.parquet"
    objects = {}
    parquet = storage(objects)
    corrupted = ParquetCodec.encode(pd.DataFrame({"date": [DAY], "close": [999]}))

    async def corrupt(**kwargs):
        objects[path] = corrupted

    if replace:
        parquet._copyable.copy_object.side_effect = corrupt
    else:
        parquet._writable.write_bytes.side_effect = corrupt
    with capture_output_publication(RAW, False) as publication:
        with pytest.raises(ManifestInvalidError, match="published work data"):
            operation = (
                parquet.replace_dataframe if replace else parquet.write_dataframe
            )
            await operation(path, pd.DataFrame({"date": [DAY], "close": [10]}))
        assert publication.outputs == {}


@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("dataset", ["eod", "signals"])
def test_aggregate_timestamp_bounds_cover_all_works_and_preserve_nulls(
    reverse, dataset
):
    from dataclasses import replace

    from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY

    first = evidence(pd.DataFrame({"date": [DAY], "close": [10]}))
    first = replace(
        first,
        minTimestamp="2026-11-02T00:00:00" if dataset == "eod" else None,
        maxTimestamp="2026-11-02T00:00:00" if dataset == "eod" else None,
    )
    if dataset == "signals":
        first = replace(
            first,
            values={"strategy": "trend", "timeframe": "1d", "exchange": "hose"},
            workScope={
                "atomicWorkId": WORK,
                "rowKeys": [{"symbol_key": "HOSE-HPG", "signal_date": DAY.isoformat()}],
            },
        )
    second = replace(
        first,
        minTimestamp="2026-11-03T00:00:00" if dataset == "eod" else None,
        maxTimestamp="2026-11-03T00:00:00" if dataset == "eod" else None,
        workScope={
            "atomicWorkId": "work:" + "b" * 64,
            "rowKeys": [
                {"date": "2026-11-03"}
                if dataset == "eod"
                else {"symbol_key": "HOSE-HPG", "signal_date": "2026-11-03"}
            ],
        },
        coverageDates=["2026-11-03"],
    )
    partitions = {d.name: [] for d in OMNI_DATASET_REGISTRY.all()}
    partitions[dataset] = [second, first] if reverse else [first, second]
    result = PostgresMetadataReader._document(partitions).datasets
    manifest = next(d for d in result if d.name == dataset).partitions[0]
    assert manifest.minTimestamp == (
        "2026-11-02T00:00:00" if dataset == "eod" else None
    )
    assert manifest.maxTimestamp == (
        "2026-11-03T00:00:00" if dataset == "eod" else None
    )
