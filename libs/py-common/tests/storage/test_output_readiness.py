import httpx
import pytest

from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.output_readiness import (
    PostgresMetadataReader,
    ShadowMetadataReader,
)


@pytest.mark.anyio
async def test_enrolled_inputs_use_work_reader_without_switching_legacy_catalog():
    from types import SimpleNamespace
    from unittest.mock import AsyncMock
    from py_common.storage.output_readiness import read_input_document

    work = PostgresMetadataReader("http://platform/readiness", "fixture")
    work.read_for = AsyncMock(return_value="frozen-work")
    legacy = SimpleNamespace(
        read=AsyncMock(return_value="manifest"), enrolled_work_reader=work
    )
    assert await read_input_document(legacy, {}) == "manifest"
    assert await read_input_document(legacy, {"outputCompletion": {}}) == "frozen-work"
    legacy.read.assert_awaited_once()
    work.read_for.assert_awaited_once()

@pytest.fixture
def anyio_backend():
    return "asyncio"


def page(revision=1):
    return {
        "revision": revision,
        "hasMore": False,
        "items": [
            {
                "dataset": "eod",
                "manifest": {
                    "values": {"exchange": "upcom", "code": "hd6"},
                    "status": "READY",
                    "path": shared_page_fixture()["items"][0]["manifest"]["path"],
                    "canonicalPath": "eod/upcom/hd6.parquet",
                    "dataVersion": "sha256:" + "a" * 64,
                    "schemaVersion": 1,
                    "schemaHash": "sha256:" + "b" * 64,
                    "objectCount": 1,
                    "totalBytes": 128,
                    "rowCount": 2,
                    "columnCount": 1,
                    "columns": [{"name": "date", "type": "DATE", "nullable": False}],
                    "inputs": [],
                    "coverageDates": ["2026-10-30", "2026-11-02"],
                    "generatedAt": "2026-11-02T12:00:00Z",
                    "sourceExecutionId": None,
                },
            }
        ],
    }


@pytest.mark.anyio
async def test_ready_api_preserves_snapshot_coverage_and_unknown_provenance():
    def respond(request):
        assert request.headers["Authorization"] == "Bearer test-token"
        return httpx.Response(200, json=page())

    reader = PostgresMetadataReader(
        "http://platform/api/v1/jobs/output-readiness",
        "test-token",
        transport=httpx.MockTransport(respond),
    )
    manifest = (await reader.read()).resolve(
        "eod", {"exchange": "upcom", "code": "hd6"}
    )
    assert manifest.canonicalPath == "eod/upcom/hd6.parquet"
    assert manifest.sourceExecutionId is None
    assert manifest.coverageDates == ["2026-10-30", "2026-11-02"]


@pytest.mark.anyio
@pytest.mark.parametrize("status", [401, 409, 500])
async def test_api_errors_fail_closed_without_manifest_fallback(status):
    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(lambda request: httpx.Response(status)),
    )
    with pytest.raises(ManifestInvalidError, match="no manifest fallback"):
        await reader.read()


@pytest.mark.anyio
async def test_catalog_revision_cannot_change_across_pages():
    def respond(request):
        result = page(1 if request.url.params["offset"] == "0" else 2)
        result["hasMore"] = request.url.params["offset"] == "0"
        return httpx.Response(200, json=result)

    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(respond),
    )
    with pytest.raises(ManifestInvalidError, match="Inconsistent readiness revision"):
        await reader.read()


@pytest.mark.anyio
async def test_shadow_mismatch_keeps_manifest_authority_explicitly():
    from unittest.mock import AsyncMock

    from py_common.storage.global_metadata import GlobalMetadataDocument

    manifest = GlobalMetadataDocument(1, "2026-11-02T12:00:00Z", [])
    baseline = AsyncMock(read=AsyncMock(return_value=manifest))
    unavailable = AsyncMock(
        read=AsyncMock(side_effect=ManifestInvalidError("unavailable"))
    )
    reader = ShadowMetadataReader(baseline, unavailable)
    assert await reader.read() is manifest
    assert reader.last_comparison == {
        "compared": False,
        "matches": False,
        "reason": "POSTGRES_UNAVAILABLE",
    }


@pytest.mark.anyio
@pytest.mark.parametrize(
    "change",
    ["revision", "items", "more", "coverage", "identity", "unknown-dataset", "json"],
)
async def test_malformed_provider_data_cannot_grant_ready(change):
    payload = page()
    if change == "revision":
        payload["revision"] = "1"
    elif change == "items":
        payload["items"] = {}
    elif change == "more":
        payload["hasMore"] = 1
    elif change == "coverage":
        payload["items"][0]["manifest"]["coverageDates"] = []
    elif change == "identity":
        payload["items"][0]["manifest"].pop("canonicalPath")
    elif change == "unknown-dataset":
        payload["items"][0]["dataset"] = "unknown"

    def respond(request):
        return (
            httpx.Response(200, content=b"not json")
            if change == "json"
            else httpx.Response(200, json=payload)
        )

    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(respond),
    )
    with pytest.raises(ManifestInvalidError):
        await reader.read()


@pytest.mark.anyio
@pytest.mark.parametrize("different", [False, True])
async def test_shadow_compares_exact_versions_and_keeps_legacy_authority(different):
    from dataclasses import replace
    from unittest.mock import AsyncMock

    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=page())),
    )
    manifest = await reader.read()
    postgres = manifest
    if different:
        eod = manifest.dataset("eod")
        changed = replace(
            eod,
            partitions=[replace(eod.partitions[0], dataVersion="sha256:" + "c" * 64)],
        )
        postgres = replace(manifest, datasets=[changed])
    shadow = ShadowMetadataReader(
        AsyncMock(read=AsyncMock(return_value=manifest)),
        AsyncMock(read=AsyncMock(return_value=postgres)),
    )
    assert await shadow.read() is manifest
    assert shadow.last_comparison["matches"] is (not different)
    assert shadow.last_comparison["mismatchCount"] == (1 if different else 0)


@pytest.mark.parametrize(
    "url,token",
    [("", "secret"), ("file:///metadata", "secret"), ("http://platform", "")],
)
def test_private_api_configuration_is_required(url, token):
    with pytest.raises(ValueError, match="authenticated private API"):
        PostgresMetadataReader(url, token)


def shared_page_fixture():
    import json
    from pathlib import Path

    path = (
        Path(__file__).resolve().parents[3]
        / "contracts/fixtures/output-readiness-page-v1.json"
    )
    return json.loads(path.read_text())


@pytest.mark.anyio
async def test_java_python_shared_ready_page_fixture_preserves_wire_contract():
    payload = shared_page_fixture()
    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=payload)
        ),
    )
    document = await reader.read()
    manifest = document.resolve("eod", {"exchange": "upcom", "code": "hd6"})
    wire = payload["items"][0]["manifest"]
    assert manifest.path == wire["path"]
    assert manifest.canonicalPath == wire["canonicalPath"]
    assert manifest.coverageDates == wire["coverageDates"]
    assert manifest.sourceExecutionId is None
    assert manifest.dataVersion == wire["dataVersion"]


@pytest.mark.anyio
async def test_short_byte_bounded_pages_advance_by_returned_items_not_requested_limit():
    from py_common.config.paths import StockDataPaths
    from py_common.storage.output_snapshots import (
        OutputSnapshotIdentity,
        snapshot_object_name,
    )

    offsets = []

    def respond(request):
        offset = int(request.url.params["offset"])
        offsets.append(offset)
        result = shared_page_fixture()
        result["hasMore"] = offset == 0
        result["nextOffset"] = offset + 1
        if offset:
            assert request.url.params["revision"] == "7"
            raw = result["items"][0]["manifest"]
            raw["values"]["code"] = "hd7"
            raw["canonicalPath"] = "eod/upcom/hd7.parquet"
            raw["path"] = snapshot_object_name(
                OutputSnapshotIdentity("eod", raw["values"]),
                raw["dataVersion"],
                StockDataPaths.from_config({}),
            )
        return httpx.Response(200, json=result)

    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(respond),
    )
    catalog = await reader.read()
    assert offsets == [0, 1]
    assert {p.values["code"] for p in catalog.dataset("eod").partitions} == {
        "hd6",
        "hd7",
    }


@pytest.mark.anyio
@pytest.mark.parametrize("cursor", [True, "1", -1, 0, 2, 100001])
async def test_malformed_or_skipping_ready_cursor_fails_closed(cursor):
    payload = shared_page_fixture()
    payload["hasMore"] = True
    payload["nextOffset"] = cursor
    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=payload)
        ),
    )
    with pytest.raises(ManifestInvalidError, match="page cursor"):
        await reader.read()


@pytest.mark.anyio
async def test_empty_continuation_page_cannot_loop_forever():
    payload = {"revision": 7, "items": [], "hasMore": True, "nextOffset": 0}
    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=payload)
        ),
    )
    with pytest.raises(ManifestInvalidError, match="page cursor"):
        await reader.read()


@pytest.mark.anyio
async def test_ready_page_cannot_exceed_the_shared_four_mib_budget():
    payload = shared_page_fixture()
    payload["padding"] = "x" * (4 * 1024 * 1024)
    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=payload)
        ),
    )
    with pytest.raises(ManifestInvalidError, match="size limit"):
        await reader.read()


@pytest.mark.anyio
@pytest.mark.parametrize(
    "path",
    [
        "eod/upcom/hd6.parquet",
        "../snapshot.parquet",
        "_output-snapshots/eod/wrong/version.parquet",
    ],
)
async def test_ready_api_cannot_redirect_queries_to_mutable_or_wrong_snapshot(path):
    payload = shared_page_fixture()
    payload["items"][0]["manifest"]["path"] = path
    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=payload)
        ),
    )
    with pytest.raises(ManifestInvalidError, match="immutable snapshot identity"):
        await reader.read()


@pytest.mark.anyio
async def test_ready_reader_uses_the_same_configured_snapshot_layout_as_writers():
    from py_common.config.paths import StockDataPaths
    from py_common.storage.output_snapshots import (
        OutputSnapshotIdentity,
        snapshot_object_name,
    )

    paths = StockDataPaths.from_config(
        {"paths": {"output-snapshots": {"base": "versions/"}}}
    )
    payload = shared_page_fixture()
    raw = payload["items"][0]["manifest"]
    raw["path"] = snapshot_object_name(
        OutputSnapshotIdentity("eod", raw["values"]), raw["dataVersion"], paths
    )
    reader = PostgresMetadataReader(
        "http://platform/readiness",
        "test-token",
        paths=paths,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=payload)
        ),
    )
    assert (
        (await reader.read()).dataset("eod").partitions[0].path.startswith("versions/")
    )


@pytest.mark.parametrize("mode", ["manifest", "shadow", "postgres", "invalid"])
def test_authority_selection_keeps_explicit_mode_and_shared_path_configuration(mode):
    from types import SimpleNamespace
    from unittest.mock import Mock

    from pydantic import SecretStr

    from py_common.config.paths import StockDataPaths
    from py_common.storage.global_metadata import GlobalMetadataReader
    from py_common.storage.output_readiness import metadata_reader

    paths = StockDataPaths.from_config(
        {"paths": {"output-snapshots": {"base": "versions/"}}}
    )
    settings = SimpleNamespace(
        output_readiness_mode=mode,
        minio=SimpleNamespace(bucket="stock-data"),
        output_readiness_api_url="http://platform/readiness",
        output_readiness_api_token=SecretStr("reader"),
        stock_data_paths=paths,
    )
    if mode == "invalid":
        with pytest.raises(ValueError, match="Unsupported"):
            metadata_reader(settings, Mock())
        return
    reader = metadata_reader(settings, Mock())
    if mode == "manifest":
        assert isinstance(reader, GlobalMetadataReader)
        assert isinstance(reader.enrolled_work_reader, PostgresMetadataReader)
    else:
        postgres = reader._postgres if mode == "shadow" else reader
        assert isinstance(postgres, PostgresMetadataReader)
        assert postgres._paths is paths
