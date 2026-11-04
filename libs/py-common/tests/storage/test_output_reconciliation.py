from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pandas as pd
import pytest
from pydantic import SecretStr

from py_common.storage.exceptions import ManifestInvalidError
from py_common.storage.metadata_sync import MetadataSyncTarget
from py_common.storage.output_reconciliation import OutputReconciler


@pytest.fixture
def anyio_backend():
    return "asyncio"


def setup(backfill=True, *, failure=None, frame=None):
    settings = SimpleNamespace(
        output_readiness_api_url="http://platform/readiness",
        output_readiness_reconcile_token=SecretStr("reconciler"),
    )
    manifest = SimpleNamespace(
        values={"exchange": "upcom", "code": "hd6"}, dataVersion="sha256:" + "a" * 64
    )
    document = SimpleNamespace(
        datasets=[SimpleNamespace(name="eod", partitions=[manifest])]
    )
    reader = AsyncMock(read=AsyncMock(return_value=document))
    snapshot = SimpleNamespace(
        data_version=manifest.dataVersion,
        total_bytes=128,
        schema_hash="sha256:" + "b" * 64,
        inputs=(),
    )
    frame = (
        pd.DataFrame({"date": [date(2026, 11, 2)], "close": [10]})
        if frame is None
        else frame
    )
    parquet = AsyncMock(snapshot_bound_output=AsyncMock(return_value=(snapshot, frame)))
    requests = []

    def respond(request):
        requests.append(request)
        assert request.headers["Authorization"] == "Bearer reconciler"
        if request.url.path.endswith("/fence"):
            return httpx.Response(409 if failure == "begin" else 200, json={"fence": 7})
        return httpx.Response(
            409 if failure == "commit" else 200, json={"accepted": True}
        )

    owner = OutputReconciler(
        settings,
        reader,
        parquet,
        backfill=backfill,
        transport=httpx.MockTransport(respond),
    )
    return owner, parquet, requests


@pytest.mark.anyio
async def test_backfill_uses_fence_actual_dates_and_no_invented_execution():
    import json

    owner, parquet, requests = setup()
    result = await owner.sync(
        target=MetadataSyncTarget(
            dataset="eod", partition={"exchange": "upcom", "code": "hd6"}
        )
    )
    assert result.partitions_added == 1 and result.objects_seen == 1
    payload = json.loads(requests[-1].content)
    assert payload["fence"] == 7
    assert payload["output"]["coverageDates"] == ["2026-11-02"]
    assert "executionId" not in payload and "atomicAttemptId" not in payload
    parquet.snapshot_bound_output.assert_awaited_once()


@pytest.mark.anyio
@pytest.mark.parametrize("failure", ["begin", "commit"])
async def test_generation_conflict_aborts_without_hidden_recovery(failure):
    owner, parquet, requests = setup(failure=failure)
    with pytest.raises(httpx.HTTPStatusError):
        await owner.sync()
    assert len(requests) == (1 if failure == "begin" else 2)
    if failure == "begin":
        parquet.snapshot_bound_output.assert_not_awaited()


@pytest.mark.anyio
async def test_post_cutover_reconciliation_only_validates_registered_bytes():
    owner, parquet, requests = setup(backfill=False)
    result = await owner.sync()
    assert result.partitions_unchanged == 1 and result.partitions_added == 0
    parquet.read_bound_dataframe.assert_awaited_once()
    parquet.snapshot_bound_output.assert_not_awaited()
    assert not requests


@pytest.mark.anyio
@pytest.mark.parametrize(
    "target",
    [
        MetadataSyncTarget(dataset="indicators"),
        MetadataSyncTarget(
            dataset="eod", partition={"exchange": "hose", "code": "other"}
        ),
    ],
)
async def test_empty_scope_never_reports_a_successful_reconciliation(target):
    owner, parquet, requests = setup()
    with pytest.raises(ManifestInvalidError, match="selected no"):
        await owner.sync(target=target)
    parquet.snapshot_bound_output.assert_not_awaited()
    assert len(requests) == 1


@pytest.mark.anyio
@pytest.mark.parametrize(
    "frame", [pd.DataFrame({"close": [10]}), pd.DataFrame({"date": [None]})]
)
async def test_unproven_calendar_coverage_never_commits_baseline(frame):
    owner, parquet, requests = setup(frame=frame)
    with pytest.raises(ManifestInvalidError, match="coverage dates"):
        await owner.sync()
    assert len(requests) == 1
