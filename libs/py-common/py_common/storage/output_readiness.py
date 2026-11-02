"""Explicit metadata authority selection. API failures never fall back to JSON."""

from __future__ import annotations

from datetime import UTC, datetime

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
                        expected_path = snapshot_object_name(
                            OutputSnapshotIdentity(item["dataset"], raw["values"]),
                            raw["dataVersion"], self._paths,
                        )
                        if raw["path"] != expected_path or raw["path"] == raw["canonicalPath"]:
                            raise ManifestInvalidError("READY path differs from immutable snapshot identity")
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
        return GlobalMetadataDocument(
            version=1, generatedAt=datetime.now(UTC).isoformat(),
            datasets=[GlobalDatasetMetadata(
                name=d.name, label=d.label, dataPrefix=d.data_prefix,
                partitionKeys=list(d.partition_keys), partitions=partitions[d.name],
            ) for d in OMNI_DATASET_REGISTRY.all()],
        )


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
