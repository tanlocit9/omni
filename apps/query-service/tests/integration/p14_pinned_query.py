"""Exercise the real Query resolver/executor after a stale canonical replacement."""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from py_common.config.models import MinioSettings
from py_common.storage.adapters import create_minio_client
from py_common.storage.adapters.minio import MinioStorageAdapter
from py_common.storage.output_readiness import metadata_reader
from py_common.storage.parquet import ParquetStorage
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry
from pydantic import SecretStr

from app.executor import DuckDBExecutor
from app.models import DatasetRef
from app.security import validate_read_only_sql
from app.settings import QueryServiceSettings
from app.storage import DatasetResolver


async def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    settings = QueryServiceSettings(
        minio=MinioSettings(
            endpoint=config["minio"],
            access_key=config["accessKey"],
            secret_key=config["secretKey"],
            bucket=config["bucket"],
            secure=False,
        ),
        query_storage_scheme="s3",
        output_readiness_mode="postgres",
        output_readiness_api_url=config["outputReadinessUrl"],
        output_readiness_api_token=SecretStr(config["outputReadinessToken"]),
    )
    adapter = MinioStorageAdapter(create_minio_client(settings.minio))
    await adapter.validate()
    reader = metadata_reader(settings, adapter)
    document = await reader.read()
    manifest = document.resolve("eod", {"exchange": "hose", "code": "hpg"})
    assert manifest is not None
    parquet = ParquetStorage(
        StorageProviderRegistry([adapter]), StorageProvider.MINIO, settings.minio.bucket
    )
    refs = [
        DatasetRef(
            dataset="eod", partition=manifest.values, dataVersion=manifest.dataVersion
        )
    ]
    datasets = await DatasetResolver(reader, settings, parquet).resolve_many(refs)
    result = await DuckDBExecutor(settings).execute(
        "pinned-after-late-writer",
        validate_read_only_sql("SELECT date, ad_close FROM eod ORDER BY date", {"eod"}),
        datasets,
        {},
        1000,
    )
    actual = {str(row["date"])[:10]: float(row["ad_close"]) for row in result.rows}
    control = Path(config["providerControlDir"])
    expected = json.loads((control / "expected-pinned-prices.json").read_text())
    for day, value in expected.items():
        assert actual[day] == value, (day, actual[day], value)
    raw = await parquet.read_dataframe("eod/hose/hpg.parquet")
    canonical = {
        str(row["date"])[:10]: float(row["ad_close"]) for row in raw.to_dict("records")
    }
    for day, value in json.loads(
        (control / "expected-canonical-prices.json").read_text()
    ).items():
        assert canonical[day] == value, (day, canonical[day], value)
    # Validate Signals through the same real reader and row-version checker.
    checked_signals = 0
    for dataset in document.datasets:
        if dataset.name == "signals":
            for proof in dataset.partitions:
                await parquet.read_bound_dataframe("signals", proof, proof.dataVersion)
                checked_signals += 1
    assert checked_signals > 0
    checked_frozen = 0
    frozen_path = control / "frozen-read-approvals.json"
    if frozen_path.exists():
        expected_proofs = json.loads(frozen_path.read_text())
        bindings = [
            {key: value for key, value in item.items() if key != "storageVersionId"}
            for item in expected_proofs
        ]
        frozen = await reader.read_for(
            {
                "outputCompletion": {"storagePolicy": "VERSIONED"},
                "approvedInputs": bindings,
            }
        )
        for expected_proof in expected_proofs:
            proof = frozen.resolve(
                expected_proof["dataset"], expected_proof["partition"]
            )
            assert proof is not None
            await parquet.read_bound_dataframe(
                expected_proof["dataset"], proof, proof.dataVersion
            )
            candidates = proof.workProofs or [
                {
                    "workScope": proof.workScope,
                    "dataVersion": proof.dataVersion,
                    "storageVersionId": proof.storageVersionId,
                }
            ]
            selected = next(
                item
                for item in candidates
                if item["workScope"]["atomicWorkId"] == expected_proof["atomicWorkId"]
            )
            assert selected["storageVersionId"] == expected_proof["storageVersionId"]
            assert selected["dataVersion"] == expected_proof["dataVersion"]
            checked_frozen += 1
    (control / "pinned-query-result.json").write_text(
        json.dumps(
            {
                "actual": actual,
                "canonical": canonical,
                "signals": checked_signals,
                "frozenProofs": checked_frozen,
            }
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
