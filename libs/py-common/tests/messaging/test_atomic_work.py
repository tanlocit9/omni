import copy
import json
from datetime import date
from decimal import Decimal, localcontext
from pathlib import Path

import pandas as pd
import pytest
from pydantic import Field

from py_common.messaging.atomic_work import atomic_work_id, canonical
from py_common.messaging.job_messages import JobMessage
from py_common.storage.atomic_work import cutoff_frame, day_rows, write_day


class IndicatorJobMessage(JobMessage):
    symbol_key: str = Field(alias="symbolKey")
    timeframe: str
    indicator_source: str = Field(alias="indicatorSource")
    indicators: list[str]


def fixture():
    root = Path(__file__).resolve().parents[4]
    return json.loads(
        (root / "libs/contracts/fixtures/atomic-work-v1.json").read_text()
    )


def test_numeric_canonicalization_is_exact_and_independent_of_decimal_context():
    root = Path(__file__).resolve().parents[4]
    vectors = json.loads(
        (root / "libs/contracts/fixtures/atomic-work-numbers-v1.json").read_text()
    )
    for precision in (6, 28, 50):
        with localcontext() as context:
            context.prec = precision
            for vector in vectors:
                value = Decimal(vector["input"])
                assert canonical(value) == vector["canonical"]
                identity = [
                    "SYNC_STOCK_PRICE",
                    "SYMBOL",
                    "HOSE-HPG",
                    "2026-10-29",
                    {"number": value},
                ]
                assert canonical(identity) == (
                    '["SYNC_STOCK_PRICE","SYMBOL","HOSE-HPG","2026-10-29",'
                    '{"number":' + vector["canonical"] + "}]"
                )
                if value == value.to_integral_value():
                    assert canonical(json.loads(str(int(value)))) == vector["canonical"]


def test_shared_java_python_fixture_and_replay_identity():
    raw = fixture()["message"]
    parsed = IndicatorJobMessage.model_validate(raw)
    assert parsed.atomic_work_id == atomic_work_id(
        parsed.job_type,
        str(parsed.work_type),
        parsed.work_key,
        parsed.business_date,
        parsed.parameters,
    )
    assert (
        IndicatorJobMessage.model_validate(
            parsed.model_dump(by_alias=True)
        ).atomic_work_id
        == parsed.atomic_work_id
    )
    legacy = {
        k: v
        for k, v in raw.items()
        if k not in {"jobType", "businessDate", "parameters", "atomicWorkId"}
    }
    assert IndicatorJobMessage.model_validate(legacy).business_date is None


@pytest.mark.parametrize(
    "field,value",
    [
        ("businessDate", "2026-10-30"),
        ("jobType", "SYNC_SIGNALS"),
        ("symbolKey", "HOSE-VCB"),
        ("source", "VND"),
        ("timeframe", "1h"),
    ],
)
def test_tampered_atomic_fields_fail_before_handler(field, value):
    raw = copy.deepcopy(fixture()["message"])
    raw[field] = value
    with pytest.raises(ValueError):
        IndicatorJobMessage.model_validate(raw)


def test_partial_contract_rejects_and_day_cutoff_preserves_lookback():
    raw = fixture()["message"]
    raw.pop("parameters")
    with pytest.raises(ValueError):
        IndicatorJobMessage.model_validate(raw)
    frame = pd.DataFrame(
        {"date": ["2026-10-28", "2026-10-29", "2026-10-30"], "close": [1, 2, 999]}
    )
    cutoff = cutoff_frame(frame, date(2026, 10, 29), require_day=True)
    assert cutoff["close"].tolist() == [1, 2]
    assert day_rows(cutoff, date(2026, 10, 29))["close"].tolist() == [2]
    with pytest.raises(ValueError, match="cover"):
        cutoff_frame(frame, date(2026, 10, 27), require_day=True)


@pytest.mark.asyncio
async def test_day_write_preserves_other_dates_and_has_no_write_on_missing_day():
    from unittest.mock import AsyncMock

    storage = AsyncMock()
    storage.read_optional_dataframe.return_value = pd.DataFrame(
        {
            "date": pd.to_datetime(["2026-10-28", "2026-10-29", "2026-10-30"]),
            "close": [1, 2, 3],
        }
    )
    new = pd.DataFrame(
        {"date": pd.to_datetime(["2026-10-28", "2026-10-29"]), "close": [100, 20]}
    )
    await write_day(storage, "logical-test", new, date(2026, 10, 29))
    written = storage.write_dataframe.await_args.args[1]
    assert written["close"].tolist() == [1, 20, 3]
    storage.write_dataframe.reset_mock()
    with pytest.raises(ValueError):
        await write_day(storage, "logical-test", new, date(2026, 10, 27))
    storage.write_dataframe.assert_not_awaited()


@pytest.mark.parametrize("value", [float("inf"), float("-inf"), Decimal("NaN")])
def test_nonfinite_identity_numbers_are_rejected(value):
    with pytest.raises(ValueError, match="finite"):
        canonical({"threshold": value})


def test_canonical_nested_values_preserve_types_and_list_order():
    assert canonical({"z": [True, False, None], "a": "Việt Nam"}) == (
        '{"a":"Việt Nam","z":[true,false,null]}'
    )
    assert canonical([1, 2]) != canonical([2, 1])


@pytest.mark.parametrize(
    "change",
    [
        {"businessDate": "2026-10-31"},
        {"jobType": "UNKNOWN"},
        {"workType": "EXCHANGE"},
        {"atomicWorkId": "work:" + "0" * 64},
        {"parameters": {"timeframe": "1d"}},
    ],
)
def test_atomic_validation_rejects_invalid_unit_day_hash_and_missing_parameters(change):
    raw = fixture()["message"]
    raw.update(change)
    with pytest.raises(ValueError):
        IndicatorJobMessage.model_validate(raw)


def test_snapshot_day_validation_is_explicit_and_legacy_remains_readable(monkeypatch):
    from datetime import datetime
    from types import SimpleNamespace

    from py_common.messaging import atomic_work

    class Clock:
        @staticmethod
        def now(zone):
            return datetime(2026, 10, 29, tzinfo=zone)

    monkeypatch.setattr(atomic_work, "datetime", Clock)
    parsed = IndicatorJobMessage.model_validate(fixture()["message"])
    parsed.require_current_snapshot()
    with pytest.raises(ValueError, match="Historical"):
        historical = parsed.model_copy(update={"business_date": date(2026, 10, 28)})
        historical.require_current_snapshot()
    parsed.model_copy(update={"business_date": None}).require_current_snapshot()
    status = SimpleNamespace(
        model_copy=lambda **kwargs: kwargs["update"], meta_json={"trace": "kept"}
    )
    assert atomic_work.carry_work_status(status, {}) is status
    meta = atomic_work.carry_work_status(status, fixture()["message"])["meta_json"]
    assert meta["trace"] == "kept"
    assert meta["workBusinessDate"] == "2026-10-29"
    assert meta["atomicWorkId"] == parsed.atomic_work_id


@pytest.mark.parametrize("helper", [cutoff_frame, day_rows])
def test_legacy_frames_bypass_atomic_requirements(helper):
    frame = pd.DataFrame({"unrelated": [1]})
    assert helper(frame, None) is frame
    with pytest.raises(ValueError, match="requires date"):
        helper(frame, date(2026, 10, 29))


@pytest.mark.parametrize("dates", [[None], ["invalid-date"]])
def test_invalid_atomic_input_dates_are_rejected(dates):
    with pytest.raises(ValueError):
        cutoff_frame(pd.DataFrame({"date": dates}), date(2026, 10, 29))


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "history",
    [None, pd.DataFrame(), pd.DataFrame({"other": [1]}), pd.DataFrame({"date": [None]})],
)
async def test_day_write_initializes_output_and_rejects_invalid_existing_history(history):
    from unittest.mock import AsyncMock

    storage = AsyncMock()
    storage.read_optional_dataframe.return_value = history
    frame = pd.DataFrame({"date": [date(2026, 10, 29)], "close": [10]})
    if history is None or history.empty:
        await write_day(storage, "logical", frame, date(2026, 10, 29))
        storage.write_dataframe.assert_awaited_once()
    else:
        with pytest.raises(ValueError):
            await write_day(storage, "logical", frame, date(2026, 10, 29))
        storage.write_dataframe.assert_not_awaited()


@pytest.mark.asyncio
async def test_legacy_write_never_reads_or_merges_history():
    from unittest.mock import AsyncMock

    storage = AsyncMock()
    frame = pd.DataFrame({"close": [1]})
    await write_day(storage, "logical", frame, None, compression="snappy")
    storage.read_optional_dataframe.assert_not_awaited()
    storage.write_dataframe.assert_awaited_once_with(
        "logical", frame, compression="snappy"
    )
