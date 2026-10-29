import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pandas as pd
import pytest

from app.indicators import handler as indicator_module
from app.indicators.handler import IndicatorJobHandler


@pytest.mark.asyncio
async def test_indicator_handler_reads_lookback_but_not_future_and_preserves_other_days(
    monkeypatch,
):
    root = Path(__file__).resolve().parents[3]
    raw = json.loads(
        (root / "libs/contracts/fixtures/atomic-work-v1.json").read_text()
    )["message"]
    raw["metadata"] = {"approvedInputs": [{"dataset": "eod", "partition": {
        "exchange": "hose", "code": "hpg"}, "dataVersion": "v1"}]}
    frame = pd.DataFrame(
        {
            "date": pd.to_datetime(["2026-10-28", "2026-10-29", "2026-10-30"]),
            "ad_close": [10, 20, 999],
        }
    )
    storage = AsyncMock()
    storage.read_dataframe.return_value = frame
    storage.read_bound_dataframe.return_value = frame
    storage.read_optional_dataframe.return_value = pd.DataFrame(
        {
            "date": pd.to_datetime(["2026-10-28", "2026-10-29", "2026-10-30"]),
            "ma20": [1, 2, 3],
            "eod_data_version": ["old", "old", "future"],
        }
    )
    metadata = SimpleNamespace(
        read=AsyncMock(
            return_value=SimpleNamespace(
                resolve=lambda *args: SimpleNamespace(
                    path="logical-eod", status="READY", dataVersion="v1"
                )
            )
        )
    )
    calculate = Mock(
        side_effect=lambda input_frame, *args: pd.DataFrame(
            {"date": input_frame["date"], "ma20": input_frame["ad_close"]}
        )
    )
    monkeypatch.setattr(indicator_module, "calculate_supported_indicators", calculate)
    settings = SimpleNamespace(
        stock_data_paths=SimpleNamespace(indicators=lambda *args: "logical-indicators"),
        scheduler=SimpleNamespace(),
    )
    handler = IndicatorJobHandler(settings, storage, metadata)
    await handler.handle(raw)
    assert calculate.call_args.args[0]["ad_close"].tolist() == [10, 20]
    written = storage.write_dataframe.await_args.args[1]
    assert written["ma20"].tolist() == [1, 20, 3]
    assert written["eod_data_version"].tolist() == ["old", "v1", "future"]


@pytest.mark.asyncio
async def test_indicator_atomic_missing_day_does_not_publish_output(monkeypatch):
    root = Path(__file__).resolve().parents[3]
    raw = json.loads(
        (root / "libs/contracts/fixtures/atomic-work-v1.json").read_text()
    )["message"]
    raw["metadata"] = {"approvedInputs": [{"dataset": "eod", "partition": {
        "exchange": "hose", "code": "hpg"}, "dataVersion": "v1"}]}
    storage = AsyncMock()
    storage.read_dataframe.return_value = pd.DataFrame(
        {"date": ["2026-10-28"], "ad_close": [10]}
    )
    storage.read_bound_dataframe.return_value = storage.read_dataframe.return_value
    metadata = SimpleNamespace(
        read=AsyncMock(
            return_value=SimpleNamespace(
                resolve=lambda *args: SimpleNamespace(
                    path="logical-eod", status="READY", dataVersion="v1"
                )
            )
        )
    )
    settings = SimpleNamespace(
        stock_data_paths=SimpleNamespace(indicators=lambda *args: "logical-indicators"),
        scheduler=SimpleNamespace(),
    )
    with pytest.raises(ValueError, match="cover"):
        await IndicatorJobHandler(settings, storage, metadata).handle(raw)
    storage.write_dataframe.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("binding", [None, "invalid"])
async def test_atomic_indicator_requires_approved_eod_before_output(binding):
    root = Path(__file__).resolve().parents[3]
    raw = json.loads((root / "libs/contracts/fixtures/atomic-work-v1.json").read_text())["message"]
    raw["metadata"] = {} if binding is None else {"approvedInputs": binding}
    storage = AsyncMock()
    reader = SimpleNamespace(read=AsyncMock(return_value=SimpleNamespace(
        resolve=lambda *args: SimpleNamespace(path="eod", status="READY", dataVersion="v1"))))
    settings = SimpleNamespace(stock_data_paths=SimpleNamespace(indicators=lambda *args: "output"))
    with pytest.raises(ValueError, match="binding"):
        await IndicatorJobHandler(settings, storage, reader).handle(raw)
    storage.read_dataframe.assert_not_awaited()
    storage.read_bound_dataframe.assert_not_awaited()
    storage.write_dataframe.assert_not_awaited()
