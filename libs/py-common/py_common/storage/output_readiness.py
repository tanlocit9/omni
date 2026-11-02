"""Explicit metadata authority selection. API failures never fall back to JSON."""

from __future__ import annotations

from datetime import UTC, datetime

import httpx

from py_common.storage.dataset_registry import OMNI_DATASET_REGISTRY
from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.global_metadata import (
    GlobalColumnMetadata, GlobalDatasetInput, GlobalDatasetMetadata,
    GlobalMetadataDocument, GlobalMetadataReader, GlobalPartitionMetadata,
)


class PostgresMetadataReader:
    def __init__(self, url: str, token: str, *, transport=None) -> None:
        if not url.startswith(("http://", "https://")) or not token:
            raise ValueError("PostgreSQL metadata requires an authenticated private API URL")
        self._url, self._token = url, token
        self._transport = transport

    async def read(self) -> GlobalMetadataDocument:
        partitions: dict[str, list[GlobalPartitionMetadata]] = {
            definition.name: [] for definition in OMNI_DATASET_REGISTRY.all()
        }
        revision = None
        try:
            async with httpx.AsyncClient(timeout=10, follow_redirects=False, trust_env=False, transport=self._transport) as client:
                for offset in range(0, 100001, 200):
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
                    for item in page["items"]:
                        raw = dict(item["manifest"])
                        if not raw.get("canonicalPath") or not raw.get("coverageDates"):
                            raise ManifestInvalidError("READY requires snapshot identity and exact coverage")
                        raw["columns"] = [GlobalColumnMetadata(**column) for column in raw["columns"]]
                        raw["inputs"] = [GlobalDatasetInput(**value) for value in raw["inputs"]]
                        partitions[item["dataset"]].append(GlobalPartitionMetadata(**raw))
                    if not page["hasMore"]:
                        break
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
                                      settings.output_readiness_api_token.get_secret_value())
    if settings.output_readiness_mode == "shadow":
        return ShadowMetadataReader(GlobalMetadataReader(readable, settings.minio.bucket),
                                    PostgresMetadataReader(settings.output_readiness_api_url,
                                        settings.output_readiness_api_token.get_secret_value()))
    raise ValueError("Unsupported output_readiness_mode")
