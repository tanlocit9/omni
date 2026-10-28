from unittest.mock import AsyncMock

import pandas as pd
import pytest
from py_common.messaging import JobStatus

from app.handlers import symbols


@pytest.mark.anyio
@pytest.mark.parametrize(
    "mode", ["healthy", "coverage", "fallback", "missing", "delisted"]
)
async def test_symbol_warnings_complete_without_hiding_real_failure(
    monkeypatch, caplog, mode
):
    frame = pd.DataFrame([{"code": "HPG", "exchange": "HOSE", "floor": "HOSE"}])
    if mode == "delisted":
        frame["delistedDate"] = "2026-01-01"
    client = AsyncMock()
    client.fetch_symbols.return_value = frame.to_dict("records")
    storage, publisher, producer = AsyncMock(), AsyncMock(), AsyncMock()
    storage.read_optional_dataframe.return_value = (
        pd.DataFrame() if mode == "missing" else frame
    )
    sector = AsyncMock(return_value={"test": {}})
    if mode in ("fallback", "missing"):
        sector.side_effect = RuntimeError("provider unavailable")
    monkeypatch.setattr(symbols, "get_or_create_client", lambda source: client)
    monkeypatch.setattr(symbols, "get_cached_sectors", sector)
    monkeypatch.setattr(symbols, "validate_symbols_snapshot", lambda df, exchange: df)
    monkeypatch.setattr(symbols, "normalize_symbols_dataframe", lambda *a, **kw: frame)
    monkeypatch.setattr(symbols, "calculate_classification_metrics", lambda *a: {})
    monkeypatch.setattr(
        symbols,
        "validate_classification_coverage",
        lambda *a: "Low coverage" if mode == "coverage" else None,
    )
    monkeypatch.setattr(symbols, "publish_sector_upsert_batch", AsyncMock())
    monkeypatch.setattr(symbols, "publish_symbol_upsert_batch", AsyncMock())
    payload = {
        "jobDefinitionId": "00000000-0000-0000-0000-000000000001",
        "executionId": "00000000-0000-0000-0000-000000000002",
        "source": "VCI",
        "exchange": "HOSE",
        "workType": "EXCHANGE",
        "workKey": "HOSE",
        "metadata": {
            "includeSectorClassification": mode != "healthy",
            "symbolCount": 1,
        },
    }
    status = await symbols.process_sync_symbols_message(
        payload, producer, publisher, client, storage
    )
    assert status.status == (
        JobStatus.ERROR if mode == "missing" else JobStatus.SUCCESS
    )
    publisher.publish.assert_awaited_once_with(status, key="HOSE")
    if mode in ("fallback", "coverage"):
        assert status.meta_json["result"] == "PARTIAL_SUCCESS"
        assert status.meta_json["warnings"]
        assert "Symbol synchronization warnings" in caplog.text
        assert status.error_message is None
    elif mode != "missing":
        assert "result" not in status.meta_json
        assert status.meta_json["classificationSource"] in ("NONE", "FRESH")
    else:
        storage.write_dataframe.assert_not_awaited()
        assert "no previous" in status.error_message
