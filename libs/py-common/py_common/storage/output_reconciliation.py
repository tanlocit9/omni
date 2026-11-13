"""Explicit, fenced I10 backfill/reconciliation through the Platform API."""

from __future__ import annotations

from uuid import uuid4

import httpx
import pandas as pd

from py_common.messaging.output_registration import (
    RegisteredColumn,
    RegisteredInput,
    RegisteredOutput,
)
from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.manifest import extract_schema_from_dataframe
from py_common.storage.metadata_sync import MetadataSyncResult, MetadataSyncTarget
from py_common.storage.output_snapshots import OutputSnapshotIdentity


class OutputReconciler:
    def __init__(
        self, settings, reader, parquet, *, backfill: bool, transport=None
    ) -> None:
        self._settings, self._reader, self._parquet = settings, reader, parquet
        self._backfill = backfill
        self._transport = transport

    async def sync(self, *, target=None, execution_id=None):
        requested = target or MetadataSyncTarget()
        url = self._settings.output_readiness_api_url.rstrip("/")
        token = self._settings.output_readiness_reconcile_token.get_secret_value()
        if self._backfill and (not url or not token):
            raise ValueError(
                "Backfill requires the private reconciliation API and token"
            )
        async with httpx.AsyncClient(
            timeout=15,
            follow_redirects=False,
            trust_env=False,
            transport=self._transport,
        ) as client:
            headers = {"Authorization": f"Bearer {token}"}
            fence = None
            if self._backfill:
                response = await client.post(url + "/backfill/fence", headers=headers)
                response.raise_for_status()
                fence = response.json()["fence"]
            document = await self._reader.read()
            seen = published = 0
            for dataset in document.datasets:
                if requested.dataset is not None and dataset.name != requested.dataset:
                    continue
                for manifest in dataset.partitions:
                    if requested.partition is not None and manifest.values != dict(
                        requested.partition
                    ):
                        continue
                    seen += 1
                    if not self._backfill:
                        await self._parquet.read_bound_dataframe(
                            dataset.name, manifest, manifest.dataVersion
                        )
                        continue
                    snapshot, frame = await self._parquet.snapshot_bound_output(
                        dataset.name, manifest
                    )
                    column = "signal_date" if dataset.name == "signals" else "date"
                    if column not in frame or frame[column].isna().any():
                        raise ManifestInvalidError(
                            "Backfill lacks complete actual coverage dates"
                        )
                    coverage = sorted(
                        set(pd.to_datetime(frame[column], errors="raise").dt.date)
                    )
                    columns = extract_schema_from_dataframe(frame)
                    output = RegisteredOutput(
                        dataset=dataset.name,
                        partition=manifest.values,
                        dataVersion=snapshot.data_version,
                        coverageDates=coverage,
                        rowCount=len(frame),
                        totalBytes=snapshot.total_bytes,
                        schemaVersion=1,
                        schemaHash=snapshot.schema_hash,
                        columns=[
                            RegisteredColumn(
                                name=c.name, type=c.type, nullable=c.nullable
                            )
                            for c in columns
                        ],
                        inputs=[
                            RegisteredInput(
                                dataset=i.dataset,
                                partition=i.partition,
                                dataVersion=i.dataVersion,
                            )
                            for i in snapshot.inputs
                        ],
                    )
                    response = await client.post(
                        url + "/backfill",
                        headers=headers,
                        json={
                            "fence": fence,
                            "baselineId": str(uuid4()),
                            "output": output.model_dump(mode="json", by_alias=True),
                        },
                    )
                    response.raise_for_status()
                    published += 1
            if seen == 0:
                raise ManifestInvalidError(
                    "Reconciliation selected no validated partitions"
                )
        return MetadataSyncResult(
            requested.mode, seen, published, 0, 0, seen - published, 0, 0
        )


class OutputStorageBaselineCoordinator:
    """Pin one partition before enabling strict versioned readers."""

    def __init__(self, settings, reader, parquet, *, transport=None):
        self._settings, self._reader, self._parquet = settings, reader, parquet
        self._transport = transport

    async def pin(self, dataset, partition, *, allow_unproven_history=False):
        if not allow_unproven_history:
            raise ManifestInvalidError(
                "Physical baseline requires explicit historical-container approval"
            )
        url = self._settings.output_readiness_api_url.rstrip("/")
        reader_token = self._settings.output_readiness_api_token.get_secret_value()
        reconcile_token = (
            self._settings.output_readiness_reconcile_token.get_secret_value()
        )
        if not url or not reader_token or not reconcile_token:
            raise ValueError(
                "Storage baseline requires private reader and "
                "reconciliation credentials"
            )
        async with httpx.AsyncClient(
            timeout=15,
            follow_redirects=False,
            trust_env=False,
            transport=self._transport,
        ) as client:
            headers = {"Authorization": f"Bearer {reconcile_token}"}
            response = await client.post(url + "/backfill/fence", headers=headers)
            response.raise_for_status()
            fence = response.json()["fence"]
            response = await client.get(
                url,
                params={"offset": 0, "limit": 1},
                headers={"Authorization": f"Bearer {reader_token}"},
            )
            response.raise_for_status()
            revision = response.json()["revision"]
            document = await self._reader.read()
            manifest = document.resolve(dataset, partition)
            evidence = await self._parquet.pin_historical_output(
                OutputSnapshotIdentity(dataset, partition),
                manifest,
                allow_unproven_history=True,
            )
            response = await client.post(
                url + "/backfill/storage",
                headers=headers,
                json={
                    "fence": fence,
                    "revision": revision,
                    "dataset": dataset,
                    "partition": partition,
                    **evidence,
                },
            )
            response.raise_for_status()
            return response.json()
