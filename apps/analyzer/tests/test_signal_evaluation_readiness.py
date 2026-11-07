"""Evaluation -> frozen metadata -> actual Parquet -> preserved shared history."""

from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pandas as pd
import pytest

from py_common.messaging.atomic_work import atomic_work_id
from py_common.config.paths import StockDataPaths
from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.global_metadata import GlobalColumnMetadata, GlobalPartitionMetadata
from py_common.storage.manifest import calculate_schema_hash, extract_schema_from_dataframe
from py_common.storage.output_readiness import PostgresMetadataReader
from py_common.storage.parquet import ParquetCodec, ParquetStorage
from py_common.storage.ports import CopyableStorage, DeletableStorage, ReadableStorage, WritableStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.work_rows import select_rows, work_version

from app.signals.evaluator import SignalOutcomeEvaluator
from app.signals.storage import OUTCOME_WINDOWS, SignalHistoryRepository


HISTORY = "signals/trend_momentum_v1/1d/hose.parquet"
DAY = date(2026, 11, 2)


@pytest.mark.anyio
@pytest.mark.parametrize("state", ["empty", "no-decision", "complete"])
async def test_bound_history_without_pending_outcomes_never_reads_eod_or_replaces(state):
    history = pd.DataFrame() if state == "empty" else pd.DataFrame([{
        "symbol_key": "HOSE-HPG", "signal_date": "2026-10-26",
        "model_version": "V1", "signal": "NO_DECISION" if state == "no-decision" else "BULLISH",
        "signal_price": 100.0,
    }])
    if state == "complete":
        for window in OUTCOME_WINDOWS:
            history[f"actual_price_t{window}"] = 110.0
            history[f"actual_return_t{window}"] = 0.1
    storage = SimpleNamespace(read_dataframe=AsyncMock(), replace_dataframe=AsyncMock())
    load_history = AsyncMock(return_value=history)
    load_eod = AsyncMock()
    result = await SignalHistoryRepository(storage).update_outcomes(
        HISTORY, load_eod, history_loader=load_history,
        eligible_rows=frozenset({("HOSE-HPG", "2026-10-26", "V1")}),
    )
    assert result.records_updated == 0
    load_history.assert_awaited_once()
    load_eod.assert_not_awaited()
    storage.read_dataframe.assert_not_awaited()
    storage.replace_dataframe.assert_not_awaited()


def proof(dataset, partition, path, frame, keys, identity):
    scope = {"atomicWorkId": "work:" + identity * 64, "rowKeys": keys}
    rows = select_rows(dataset, frame, scope)
    columns = extract_schema_from_dataframe(rows)
    schema = calculate_schema_hash(columns)
    return GlobalPartitionMetadata(
        values=partition, status="READY", path=path, canonicalPath=path,
        dataVersion=work_version(dataset, partition, scope, rows, schema, []),
        schemaVersion=1, schemaHash=schema, objectCount=1, totalBytes=100,
        rowCount=len(rows), columnCount=len(columns),
        columns=[GlobalColumnMetadata(**vars(column)) for column in columns],
        generatedAt="2026-11-02T00:00:00Z", coverageDates=[keys[0].get("date", keys[0].get("signal_date"))],
        workScope=scope,
    )


def evaluation_fixture():
    history = pd.DataFrame([
        {"symbol_key": symbol, "signal_date": "2026-10-26", "model_version": model,
         "exchange": "HOSE", "strategy": "TREND_MOMENTUM_V1", "timeframe": "1d",
         "signal": "BULLISH", "signal_price": 100.0, "score": 4}
        for symbol, model in [("HOSE-HPG", "V1"), ("HOSE-VNM", "V1"), ("HOSE-HPG", "V2"), ("HOSE-MSN", "V1")]
    ])
    objects = {HISTORY: ParquetCodec.encode(history)}
    history = ParquetCodec.decode(objects[HISTORY])
    partitions = {dataset.name: [] for dataset in OMNI_DATASET_REGISTRY.all()}
    signal_partition = {"strategy": "trend_momentum_v1", "timeframe": "1d", "exchange": "hose"}
    for symbol, identity in [("HOSE-HPG", "a"), ("HOSE-VNM", "b")]:
        keys = [{"symbol_key": symbol, "signal_date": "2026-10-26", "model_version": "V1"}]
        partitions["signals"].append(proof("signals", signal_partition, HISTORY, history, keys, identity))
        code = symbol.split("-")[1].lower()
        path = f"eod/hose/{code}.parquet"
        eod = pd.DataFrame({"date": pd.bdate_range("2026-10-27", periods=5), "ad_close": [110., 120., 130., 140., 150.]})
        objects[path] = ParquetCodec.encode(eod)
        eod = ParquetCodec.decode(objects[path])
        partitions["eod"].append(proof("eod", {"exchange": "hose", "code": code}, path, eod, [{"date": DAY.isoformat()}], identity))

    async def read(bucket, path):
        return objects[path]

    async def write(*, bucket, object_name, data, **kwargs):
        objects[object_name] = data

    async def copy(*, bucket, source_object_name, target_object_name, **kwargs):
        objects[target_object_name] = objects[source_object_name]

    async def delete(bucket=None, object_name=None, **kwargs):
        objects.pop(object_name, None)

    reads = AsyncMock(side_effect=read)
    writes = AsyncMock(side_effect=write)
    ports = {
        ReadableStorage: SimpleNamespace(read_bytes=reads),
        WritableStorage: SimpleNamespace(write_bytes=writes),
        CopyableStorage: SimpleNamespace(copy_object=AsyncMock(side_effect=copy)),
        DeletableStorage: SimpleNamespace(delete=AsyncMock(side_effect=delete)),
    }
    storage = ParquetStorage(SimpleNamespace(get_port=lambda provider, port: ports[port]), StorageProvider.MINIO, "data")
    reader = PostgresMetadataReader("http://unused.invalid/readiness", "fixture")
    reader.read_for = AsyncMock(return_value=reader._document(partitions))
    reader.read = AsyncMock(side_effect=AssertionError("Latest catalog must not replace frozen approvals"))
    settings = SimpleNamespace(stock_data_paths=StockDataPaths.from_config({}), output_readiness_mode="manifest", output_snapshots_enabled=False)
    payload = {"jobDefinitionId": "definition", "executionId": "execution", "source": "ANALYZER", "workType": "EXCHANGE", "workKey": "HOSE", "exchange": "HOSE", "timeframe": "1d", "strategy": "TREND_MOMENTUM_V1", "businessDate": DAY.isoformat(), "metadata": {"outputCompletion": {}, "approvedInputs": []}}
    payload["jobType"] = "EVALUATE_SIGNALS"
    payload["parameters"] = {"source": "ANALYZER", "timeframe": "1d", "strategy": "TREND_MOMENTUM_V1"}
    payload["atomicWorkId"] = atomic_work_id("EVALUATE_SIGNALS", "EXCHANGE", "HOSE", DAY, payload["parameters"])
    return SignalOutcomeEvaluator(settings, storage, reader), payload, objects, reader, reads, writes


@pytest.mark.anyio
async def test_evaluation_validates_signals_once_and_preserves_unapproved_models_and_symbols():
    evaluator, payload, objects, reader, reads, _writes = evaluation_fixture()
    result = await evaluator.evaluate(payload)
    updated = ParquetCodec.decode(objects[HISTORY])
    assert result.records_updated == 2
    approved = updated[(updated["model_version"] == "V1") & updated["symbol_key"].isin(["HOSE-HPG", "HOSE-VNM"])]
    assert approved["actual_price_t5"].tolist() == [150., 150.]
    unrelated = updated[(updated["model_version"] == "V2") | (updated["symbol_key"] == "HOSE-MSN")]
    assert unrelated["actual_price_t5"].isna().all()
    assert len(updated) == 4
    reader.read_for.assert_awaited_once()
    reader.read.assert_not_awaited()
    assert sum(call.args[1] == HISTORY for call in reads.await_args_list) == 1
    assert not any(call.args[1].endswith("msn.parquet") for call in reads.await_args_list)


@pytest.mark.anyio
@pytest.mark.parametrize("corrupt", ["signal", "eod"])
async def test_changed_approved_bytes_fail_before_any_history_replacement(corrupt):
    evaluator, payload, objects, _reader, _reads, writes = evaluation_fixture()
    path = HISTORY if corrupt == "signal" else "eod/hose/hpg.parquet"
    frame = ParquetCodec.decode(objects[path])
    frame.loc[0 if corrupt == "signal" else len(frame)-1, "signal_price" if corrupt == "signal" else "ad_close"] = 999.
    objects[path] = ParquetCodec.encode(frame)
    before = dict(objects)
    with pytest.raises(ManifestInvalidError, match="approved version"):
        await evaluator.evaluate(payload)
    writes.assert_not_awaited()
    assert objects == before
