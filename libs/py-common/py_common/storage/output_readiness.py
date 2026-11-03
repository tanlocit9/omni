"""Explicit metadata authority selection. API failures never fall back to JSON."""

from __future__ import annotations

from datetime import UTC, datetime
from dataclasses import replace
import hashlib
import json

import httpx

from py_common.config.paths import StockDataPaths
from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.output_snapshots import OutputSnapshotIdentity, snapshot_object_name
from py_common.storage.global_metadata import (
    GlobalColumnMetadata, GlobalDatasetInput, GlobalDatasetMetadata,
    GlobalMetadataDocument, GlobalMetadataReader, GlobalPartitionMetadata,
)


class PostgresMetadataReader:
    def __init__(self, url: str, token: str, *, paths: StockDataPaths | None = None, transport=None) -> None:
        if not url.startswith(("http://", "https://")) or not token:
            raise ValueError("PostgreSQL metadata requires an authenticated private API URL")
        self._url, self._token = url, token
        self._transport = transport
        self._paths = paths if paths is not None else StockDataPaths.from_config({})

    async def read(self) -> GlobalMetadataDocument:
        partitions: dict[str, list[GlobalPartitionMetadata]] = {
            definition.name: [] for definition in OMNI_DATASET_REGISTRY.all()
        }
        revision = None
        offset = 0
        try:
            async with httpx.AsyncClient(timeout=10, follow_redirects=False, trust_env=False, transport=self._transport) as client:
                for _ in range(100001):
                    params = {"offset": offset, "limit": 200}
                    if revision is not None:
                        params["revision"] = revision
                    response = await client.get(self._url, params=params,
                                                headers={"Authorization": f"Bearer {self._token}"})
                    response.raise_for_status()
                    if len(response.content) > 4 * 1024 * 1024:
                        raise ManifestInvalidError("Readiness page exceeds size limit")
                    page = response.json()
                    if type(page["revision"]) is not int or (revision is not None and revision != page["revision"]):
                        raise ManifestInvalidError("Inconsistent readiness revision")
                    revision = page["revision"]
                    if not isinstance(page["items"], list) or len(page["items"]) > 200 or type(page["hasMore"]) is not bool:
                        raise ManifestInvalidError("Invalid readiness page")
                    next_offset = page.get("nextOffset", offset + len(page["items"]))
                    if (type(next_offset) is not int or next_offset != offset + len(page["items"])
                            or next_offset > 100000 or (page["hasMore"] and next_offset == offset)):
                        raise ManifestInvalidError("Invalid readiness page cursor")
                    for item in page["items"]:
                        raw = dict(item["manifest"])
                        if not raw.get("canonicalPath") or not raw.get("coverageDates"):
                            raise ManifestInvalidError("READY requires snapshot identity and exact coverage")
                        if raw.get("workScope"):
                            self._validate_work_path(item["dataset"], raw)
                        else:
                            self._validate_snapshot_path(item["dataset"], raw)
                        raw["columns"] = [GlobalColumnMetadata(**column) for column in raw["columns"]]
                        raw["inputs"] = [GlobalDatasetInput(**value) for value in raw["inputs"]]
                        partitions[item["dataset"]].append(GlobalPartitionMetadata(**raw))
                    if not page["hasMore"]:
                        break
                    offset = next_offset
                else:
                    raise ManifestInvalidError("Readiness catalog exceeds bounded read")
        except (httpx.HTTPError, KeyError, TypeError, ValueError) as exc:
            raise ManifestInvalidError("PostgreSQL readiness read failed; no manifest fallback") from exc
        return self._document(partitions)

    def _validate_snapshot_path(self, dataset, raw):
        expected_path = snapshot_object_name(OutputSnapshotIdentity(dataset, raw["values"]), raw["dataVersion"], self._paths)
        if raw["path"] != expected_path or raw["path"] == raw["canonicalPath"]:
            raise ManifestInvalidError("READY path differs from immutable snapshot identity")

    def _validate_work_path(self, dataset, raw):
        from py_common.messaging.output_registration import WorkRowScope
        WorkRowScope.model_validate(raw["workScope"])
        from py_common.messaging.output_registration import RegisteredOutput
        RegisteredOutput.model_validate({"dataset": dataset, "partition": raw["values"],
            **{k: raw[k] for k in ("dataVersion","coverageDates","rowCount","totalBytes","schemaVersion","schemaHash","columns","inputs","workScope")}})
        expected = getattr(self._paths, dataset)(**raw["values"])
        if raw["path"] != expected or raw["canonicalPath"] != expected:
            raise ManifestInvalidError("Work READY path differs from configured canonical path")

    @staticmethod
    def _document(partitions):
        # The private page lists work proofs. Public discovery still has one item per partition.
        grouped = {}
        for dataset, manifests in partitions.items():
            values = {}
            for manifest in manifests:
                key = tuple(sorted(manifest.values.items()))
                values.setdefault(key, []).append(manifest)
            grouped[dataset] = []
            for manifests in values.values():
                scoped = [m for m in manifests if m.workScope]
                if not scoped:
                    if len(manifests)!=1:
                        raise ManifestInvalidError("Duplicate legacy partition readiness")
                    grouped[dataset].append(manifests[0])
                    continue
                proofs = [{"workScope": m.workScope, "schemaHash": m.schemaHash,
                           "columns": [vars(c) for c in m.columns], "inputs": [vars(i) for i in m.inputs],
                           "dataVersion": m.dataVersion} for m in scoped]
                if len({p["workScope"]["atomicWorkId"] for p in proofs})!=len(proofs):
                    raise ManifestInvalidError("Duplicate work readiness proof")
                if len(scoped)==1:
                    grouped[dataset].append(scoped[0])
                    continue
                version = "sha256:" + hashlib.sha256(json.dumps(sorted(proofs, key=lambda p: p["workScope"]["atomicWorkId"]),
                    sort_keys=True, separators=(",", ":")).encode()).hexdigest()
                grouped[dataset].append(replace(scoped[0], dataVersion=version, workScope=None, workProofs=proofs,
                    coverageDates=sorted({d for m in scoped for d in m.coverageDates}), rowCount=sum(m.rowCount for m in scoped),
                    inputs=[], sourceExecutionId=None))
        return GlobalMetadataDocument(version=1, generatedAt=datetime.now(UTC).isoformat(),
            datasets=[GlobalDatasetMetadata(name=d.name, label=d.label, dataPrefix=d.data_prefix,
                partitionKeys=list(d.partition_keys), partitions=grouped[d.name]) for d in OMNI_DATASET_REGISTRY.all()])

    async def read_for(self, metadata):
        if "outputCompletion" not in metadata:
            return await self.read()
        from py_common.messaging.approved_inputs import approved_input_bindings
        bindings = approved_input_bindings(metadata)
        partitions = {d.name: [] for d in OMNI_DATASET_REGISTRY.all()}
        try:
            async with httpx.AsyncClient(timeout=10, follow_redirects=False, trust_env=False, transport=self._transport) as client:
                for binding in bindings:
                    response = await client.get(self._url.rstrip("/")+"/work", params={"atomicWorkId": binding["atomicWorkId"]},
                        headers={"Authorization": f"Bearer {self._token}"})
                    response.raise_for_status()
                    if len(response.content)>4*1024*1024:
                        raise ManifestInvalidError("Work readiness response exceeds size limit")
                    body = response.json()
                    matches = [x for x in body["items"] if x["output"]["dataset"]==binding["dataset"]
                               and x["output"]["partition"]==binding["partition"]]
                    if len(matches)!=1:
                        raise ManifestInvalidError("Approved work output is not READY")
                    item = matches[0]
                    if any(item.get(k)!=binding[k] for k in ("atomicWorkId","atomicAttemptId","businessDate","dataVersion")):
                        raise ManifestInvalidError("Current work proof differs from approved binding")
                    from py_common.messaging.output_registration import RegisteredOutput
                    output = RegisteredOutput.model_validate(item["output"]).model_dump(mode="json", by_alias=True)
                    if not output["workScope"] or output["workScope"]["atomicWorkId"]!=binding["atomicWorkId"]:
                        raise ManifestInvalidError("Work proof lacks matching data scope")
                    path = getattr(self._paths,binding["dataset"])(**binding["partition"])
                    raw = dict(values=output["partition"],status="READY",path=path,canonicalPath=path,
                        dataVersion=output["dataVersion"],schemaVersion=output["schemaVersion"],schemaHash=output["schemaHash"],
                        objectCount=1,totalBytes=output["totalBytes"],rowCount=output["rowCount"],columnCount=len(output["columns"]),
                        columns=[GlobalColumnMetadata(**c) for c in output["columns"]],
                        inputs=[GlobalDatasetInput(**i) for i in output["inputs"]],coverageDates=output["coverageDates"],
                        workScope=output["workScope"],generatedAt=datetime.now(UTC).isoformat(),sourceExecutionId=item["executionId"])
                    partitions[binding["dataset"]].append(GlobalPartitionMetadata(**raw))
        except (httpx.HTTPError,KeyError,TypeError,ValueError) as exc:
            raise ManifestInvalidError("Approved work readiness read failed; no manifest fallback") from exc
        return self._document(partitions)


class ShadowMetadataReader:
    def __init__(self, manifest, postgres) -> None:
        self._manifest, self._postgres = manifest, postgres
        self.last_comparison = {"compared": False}

    async def read(self):
        manifest = await self._manifest.read()
        try:
            postgres = await self._postgres.read()
            def inventory(document):
                return {(d.name, tuple(sorted(p.values.items()))): (
                    p.dataVersion, p.schemaHash, p.rowCount, p.totalBytes,
                    tuple(p.coverageDates), tuple(sorted((i.dataset, tuple(sorted(i.partition.items())), i.dataVersion) for i in p.inputs)),
                ) for d in document.datasets for p in d.partitions}
            old, new = inventory(manifest), inventory(postgres)
            mismatches = [str(key) for key in old.keys() | new.keys() if old.get(key) != new.get(key)]
            self.last_comparison = {"compared": True, "matches": not mismatches,
                                    "mismatchCount": len(mismatches), "examples": mismatches[:20]}
        except ManifestInvalidError:
            self.last_comparison = {"compared": False, "matches": False, "reason": "POSTGRES_UNAVAILABLE"}
        # Shadow is explicitly manifest-authoritative, never used after postgres cutover.
        return manifest


def metadata_reader(settings, readable):
    if settings.output_readiness_mode == "manifest":
        return GlobalMetadataReader(readable, settings.minio.bucket)
    if settings.output_readiness_mode == "postgres":
        return PostgresMetadataReader(settings.output_readiness_api_url,
                                      settings.output_readiness_api_token.get_secret_value(),
                                      paths=settings.stock_data_paths)
    if settings.output_readiness_mode == "shadow":
        return ShadowMetadataReader(GlobalMetadataReader(readable, settings.minio.bucket),
                                    PostgresMetadataReader(settings.output_readiness_api_url,
                                        settings.output_readiness_api_token.get_secret_value(),
                                        paths=settings.stock_data_paths))
    raise ValueError("Unsupported output_readiness_mode")


async def read_input_document(reader, metadata):
    if "outputCompletion" in metadata:
        if not isinstance(reader, PostgresMetadataReader):
            raise ManifestInvalidError("Enrolled work requires PostgreSQL work readiness reader")
        return await reader.read_for(metadata)
    return await reader.read()
