import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pandas as pd
import pytest

from app.indicators import handler as indicator_module
from app.indicators.handler import IndicatorJobHandler


@pytest.mark.asyncio
@pytest.mark.parametrize("enrolled", [False, True])
@pytest.mark.parametrize("parquet_history", [False, True])
async def test_indicator_handler_reads_lookback_but_not_future_and_preserves_other_days(
    monkeypatch,
    enrolled,
    parquet_history,
):
    root = Path(__file__).resolve().parents[3]
    raw = json.loads(
        (root / "libs/contracts/fixtures/atomic-work-v1.json").read_text()
    )["message"]
    raw["metadata"] = {
        "approvedInputs": [
            {
                "dataset": "eod",
                "partition": {"exchange": "hose", "code": "hpg"},
                "dataVersion": "v1",
            }
        ]
    }
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
    if parquet_history:
        from py_common.storage.parquet import ParquetCodec

        history = storage.read_optional_dataframe.return_value
        storage.read_optional_dataframe.return_value = ParquetCodec.decode(
            ParquetCodec.encode(history)
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
    expected_version = "v1"
    if enrolled:
        from py_common.storage.output_readiness import PostgresMetadataReader

        expected_version = "sha256:" + "a" * 64
        binding = raw["metadata"]["approvedInputs"][0]
        binding.update(
            dataVersion=expected_version,
            atomicWorkId="work:" + "b" * 64,
            atomicAttemptId="00000000-0000-0000-0000-000000000001",
            businessDate="2026-10-29",
        )
        raw["metadata"]["outputCompletion"] = {}
        proof = SimpleNamespace(
            path="canonical-eod",
            status="READY",
            dataVersion=expected_version,
            workScope={"atomicWorkId": binding["atomicWorkId"]},
            workProofs=[],
        )
        metadata = PostgresMetadataReader(
            "http://platform/api/v1/jobs/output-readiness", "test"
        )
        metadata.read = AsyncMock(
            side_effect=AssertionError("Must resolve frozen work, not latest file")
        )
        metadata.read_for = AsyncMock(
            return_value=SimpleNamespace(resolve=lambda *args: proof)
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
    assert written["eod_data_version"].tolist() == ["old", expected_version, "future"]


@pytest.mark.asyncio
async def test_indicator_atomic_missing_day_does_not_publish_output(monkeypatch):
    root = Path(__file__).resolve().parents[3]
    raw = json.loads(
        (root / "libs/contracts/fixtures/atomic-work-v1.json").read_text()
    )["message"]
    raw["metadata"] = {
        "approvedInputs": [
            {
                "dataset": "eod",
                "partition": {"exchange": "hose", "code": "hpg"},
                "dataVersion": "v1",
            }
        ]
    }
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
    raw = json.loads(
        (root / "libs/contracts/fixtures/atomic-work-v1.json").read_text()
    )["message"]
    raw["metadata"] = {} if binding is None else {"approvedInputs": binding}
    storage = AsyncMock()
    reader = SimpleNamespace(
        read=AsyncMock(
            return_value=SimpleNamespace(
                resolve=lambda *args: SimpleNamespace(
                    path="eod", status="READY", dataVersion="v1"
                )
            )
        )
    )
    settings = SimpleNamespace(
        stock_data_paths=SimpleNamespace(indicators=lambda *args: "output")
    )
    with pytest.raises(ValueError, match="binding"):
        await IndicatorJobHandler(settings, storage, reader).handle(raw)
    storage.read_dataframe.assert_not_awaited()
    storage.read_bound_dataframe.assert_not_awaited()
    storage.write_dataframe.assert_not_awaited()
