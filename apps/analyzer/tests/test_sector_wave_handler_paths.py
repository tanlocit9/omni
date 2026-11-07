"""Actual calculations across atomic and retained undated handler routes."""

from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pandas as pd
import pytest
from py_common.messaging.atomic_work import atomic_work_id

from app.sector_wave.calculations import (
    aggregate_sector_features,
    calculate_sector_rotation_backtest,
    calculate_symbol_features,
)
from app.sector_wave.handler import SectorWaveJobHandler


@pytest.mark.anyio
@pytest.mark.parametrize("bound", [False, True, "changed-version"])
@pytest.mark.parametrize("atomic", [False, True])
@pytest.mark.parametrize(
    "job_type,unit,key,method,output",
    [
        (
            "PRECOMPUTE_SYMBOL_FEATURES",
            "SYMBOL",
            "HOSE-HPG",
            "handle_symbol_features",
            "symbol/HOSE/HPG",
        ),
        (
            "PRECOMPUTE_SECTOR_FEATURES",
            "SECTOR",
            "BANKS",
            "handle_sector_features",
            "sector/BANKS",
        ),
        (
            "SECTOR_ROTATION_BACKTEST",
            "GLOBAL",
            "SECTOR_WAVE_V1",
            "handle_sector_rotation_backtest",
            "backtest",
        ),
    ],
)
async def test_sector_handlers_keep_atomic_days_and_skip_unavailable_members(
    bound, atomic, job_type, unit, key, method, output
):
    day = date(2026, 10, 29)
    eod = pd.DataFrame(
        {
            "date": pd.bdate_range(end="2026-10-30", periods=60),
            "ad_close": range(100, 160),
            "nm_volume": range(1000, 1060),
        }
    )
    symbol = calculate_symbol_features(
        eod, symbol_key="HOSE-HPG", exchange="HOSE", code="HPG"
    )
    sector = aggregate_sector_features([symbol], sector_code="BANKS", sector_level=2)
    backtest = calculate_sector_rotation_backtest(
        [sector], strategy="SECTOR_WAVE_V1", sector_level=2
    )
    frames = {
        "catalog/HOSE": pd.DataFrame(
            {
                "exchange": ["HOSE"] * 3,
                "code": ["HPG", "MBB", "VCB"],
                "sectorLv2Code": ["BANKS"] * 3,
            }
        ),
        "catalog/HNX": pd.DataFrame(),
        "catalog/UPCOM": None,
        "symbol/HOSE/HPG": symbol,
        "symbol/HOSE/MBB": None,
        "symbol/HOSE/VCB": pd.DataFrame(),
        "sector/BANKS": sector,
        "sector/TECH": None,
        "sector/ENERGY": pd.DataFrame(),
        "backtest": backtest,
    }
    storage = AsyncMock()
    storage.read_dataframe.return_value = eod
    storage.read_optional_dataframe.side_effect = lambda path: frames.get(path)
    paths = SimpleNamespace(
        eod=lambda *args: "eod",
        symbols=lambda exchange: f"catalog/{exchange}",
        symbol_features=lambda timeframe, exchange, code: f"symbol/{exchange}/{code}",
        sector_features=lambda timeframe, level, code: f"sector/{code}",
        sector_rotation_backtest=lambda *args: "backtest",
    )
    settings = SimpleNamespace(
        stock_data_paths=paths
    )  # Default three-exchange catalog.
    parameters = {"source": "ANALYZER", "timeframe": "1d"}
    targets = {"symbolKey": key}
    if unit == "SECTOR":
        targets = {"sectorCode": key, "sectorLevel": 2}
        parameters["sectorLevel"] = 2
    elif unit == "GLOBAL":
        targets = {
            "sectorCodes": ["BANKS", "TECH", "ENERGY"],
            "sectorLevel": 2,
            "strategy": key,
        }
        parameters.update(targets)
    raw = {
        "jobDefinitionId": "definition",
        "executionId": "execution",
        "source": "ANALYZER",
        "workType": unit,
        "workKey": key,
        "timeframe": "1d",
        **targets,
    }
    if atomic:
        raw.update(
            jobType=job_type,
            businessDate=day.isoformat(),
            parameters=parameters,
            atomicWorkId=atomic_work_id(job_type, unit, key, day, parameters),
        )
    reader = None
    if bound:
        version = "sha256:" + "a" * 64
        if unit == "SYMBOL":
            dataset, partition, path, frame = "eod", {"exchange": "hose", "code": "hpg"}, "eod", eod
        elif unit == "SECTOR":
            dataset, partition, path, frame = "symbol-features", {"exchange": "hose", "code": "hpg", "timeframe": "1d"}, "symbol/HOSE/HPG", symbol
        else:
            dataset, partition, path, frame = "sector-features", {"sector_code": "banks", "sector_level": "2", "timeframe": "1d"}, "sector/BANKS", sector
            raw["sectorCodes"] = ["BANKS"]
            if atomic:
                raw["parameters"] = {**parameters, "sectorCodes": ["BANKS"]}
                raw["atomicWorkId"] = atomic_work_id(job_type, unit, key, day, raw["parameters"])
        proof = SimpleNamespace(path=path, dataVersion=version)
        document = SimpleNamespace(resolve=lambda requested, values: proof if requested == dataset and values == partition else None)
        reader = SimpleNamespace(read=AsyncMock(return_value=document))
        raw["metadata"] = {"approvedInputs": [{"dataset": dataset, "partition": partition, "dataVersion": version}]}
        storage.read_bound_dataframe.return_value = frame
        if bound == "changed-version":
            from py_common.storage.exceptions import ManifestInvalidError
            storage.read_bound_dataframe.side_effect = ManifestInvalidError("Parquet bytes differ from approved input version")
            with pytest.raises(ManifestInvalidError, match="approved input version"):
                await getattr(SectorWaveJobHandler(settings, storage, reader), method)(raw)
            storage.write_dataframe.assert_not_awaited()
            return
    count = await getattr(SectorWaveJobHandler(settings, storage, reader), method)(raw)
    # The backtest needs a preceding session to compute its first return.
    history_rows = 59 if unit == "GLOBAL" else 60
    assert count == (1 if atomic else history_rows)
    path, written = storage.write_dataframe.await_args.args
    assert path == output
    assert len(written) == history_rows
    if atomic:
        other = pd.to_datetime(written["date"]).dt.date != day
        original = frames[output]
        original = original.loc[pd.to_datetime(original["date"]).dt.date != day]
        pd.testing.assert_frame_equal(
            written.loc[other].reset_index(drop=True),
            original.reset_index(drop=True),
            check_dtype=False,
        )
    if bound:
        reader.read.assert_awaited_once()
        storage.read_bound_dataframe.assert_awaited_once_with(dataset, proof, version)
        storage.read_dataframe.assert_not_awaited()
    if unit == "SECTOR" and not bound:
        calls = [
            call.args[0] for call in storage.read_optional_dataframe.await_args_list
        ]
        assert all(
            f"catalog/{exchange}" in calls for exchange in ["HOSE", "HNX", "UPCOM"]
        )
        assert "symbol/HOSE/MBB" in calls and "symbol/HOSE/VCB" in calls
        target_row = written.loc[pd.to_datetime(written["date"]).dt.date == day].iloc[0]
        assert [x["symbol"] for x in target_row["contributors"]] == ["HPG"]
