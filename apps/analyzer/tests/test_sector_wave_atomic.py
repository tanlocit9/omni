from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pandas as pd
import pytest
from py_common.messaging.atomic_work import atomic_work_id

from app.sector_wave.calculations import (
    aggregate_sector_features,
    calculate_symbol_features,
)
from app.sector_wave.handler import SectorWaveJobHandler


@pytest.mark.anyio
@pytest.mark.parametrize(
    "job_type,unit,key,method,error",
    [
        (
            "PRECOMPUTE_SYMBOL_FEATURES",
            "SYMBOL",
            "HOSE-HPG",
            "handle_symbol_features",
            "Input does not cover business date",
        ),
        (
            "PRECOMPUTE_SECTOR_FEATURES",
            "SECTOR",
            "BANKS",
            "handle_sector_features",
            "Calculation produced no output for",
        ),
        (
            "SECTOR_ROTATION_BACKTEST",
            "GLOBAL",
            "SECTOR_WAVE_V1",
            "handle_sector_rotation_backtest",
            "Calculation produced no output for",
        ),
    ],
)
async def test_stale_sector_inputs_do_not_replace_outputs(
    job_type, unit, key, method, error
):
    """Reproduce both live failure classes with actual Sector Wave calculations."""
    day = date(2026, 10, 30)
    eod = pd.DataFrame(
        {
            "date": pd.bdate_range(end="2026-10-27", periods=60),
            "ad_close": range(100, 160),
            "nm_volume": range(1000, 1060),
        }
    )
    symbol = calculate_symbol_features(
        eod, symbol_key="HOSE-HPG", exchange="HOSE", code="HPG"
    )
    sector = aggregate_sector_features([symbol], sector_code="BANKS", sector_level=2)
    assert not symbol.empty and not sector.empty
    assert pd.to_datetime(sector["date"]).max().date() == date(2026, 10, 27)
    frames = {
        "eod": eod,
        "symbol": symbol,
        "sector": sector,
        "catalog": pd.DataFrame(
            {"code": ["HPG"], "exchange": ["HOSE"], "sectorLv2Code": ["BANKS"]}
        ),
    }
    storage = AsyncMock()
    storage.read_dataframe.return_value = eod
    storage.read_optional_dataframe.side_effect = lambda path: frames.get(path)
    settings = SimpleNamespace(
        sector_wave_symbol_exchanges=["HOSE"],
        stock_data_paths=SimpleNamespace(
            eod=lambda *args: "eod",
            symbols=lambda *args: "catalog",
            symbol_features=lambda *args: "symbol",
            sector_features=lambda *args: "sector",
            sector_rotation_backtest=lambda *args: "backtest",
        ),
    )
    parameters = {"source": "ANALYZER", "timeframe": "1d"}
    targets = {"symbolKey": key} if unit == "SYMBOL" else {"sectorCode": key}
    if unit != "SYMBOL":
        parameters["sectorLevel"] = 2
        targets["sectorLevel"] = 2
    if unit == "GLOBAL":
        targets = {"sectorCodes": ["BANKS"], "sectorLevel": 2, "strategy": key}
        parameters.update({"sectorCodes": ["BANKS"], "strategy": key})
    raw = {
        "jobDefinitionId": "definition",
        "executionId": "execution",
        "source": "ANALYZER",
        "workType": unit,
        "workKey": key,
        "timeframe": "1d",
        "jobType": job_type,
        "businessDate": day.isoformat(),
        "parameters": parameters,
        "atomicWorkId": atomic_work_id(job_type, unit, key, day, parameters),
        **targets,
    }

    with pytest.raises(ValueError, match=f"{error} 2026-10-30"):
        await getattr(SectorWaveJobHandler(settings, storage), method)(raw)

    storage.write_dataframe.assert_not_awaited()
    storage.replace_dataframe.assert_not_awaited()
