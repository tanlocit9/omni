from __future__ import annotations

import io
from datetime import date

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
from py_common.storage.parquet import ParquetCodec


def _round_trip(frame: pd.DataFrame) -> pd.DataFrame:
    return ParquetCodec.decode(ParquetCodec.encode(frame))


def test_eod_indicators_and_signals_join_on_business_date() -> None:
    eod = _round_trip(
        pd.DataFrame({"date": [pd.Timestamp("2026-08-25")], "close": [100.0]})
    )
    indicators = _round_trip(pd.DataFrame({"date": ["2026-08-25"], "rsi14": [55.0]}))
    signals = _round_trip(
        pd.DataFrame(
            {
                "signal_date": ["2026-08-25"],
                "signal": ["BUY"],
                "generated_at": ["2026-08-25T03:00:00+07:00"],
            }
        )
    ).rename(columns={"signal_date": "date"})

    joined = eod.merge(indicators, on="date").merge(signals, on="date")

    assert len(joined) == 1
    assert joined.loc[0, "date"] == date(2026, 8, 25)
    assert joined.loc[0, "signal"] == "BUY"


def test_sector_wave_and_transition_use_date32_contracts() -> None:
    wave = pd.DataFrame({"date": [pd.Timestamp("2026-08-25")], "sector_code": ["BANK"]})
    transition = pd.DataFrame(
        {
            "evaluation_date": ["2026-08-25"],
            "target_date": [date(2026, 9, 1)],
            "resolved_date": [pd.Timestamp("2026-09-01")],
            "generated_from_date": ["2026-08-22"],
        }
    )

    wave_schema = pq.read_schema(io.BytesIO(ParquetCodec.encode(wave)))
    transition_schema = pq.read_schema(io.BytesIO(ParquetCodec.encode(transition)))

    assert wave_schema.field("date").type == pa.date32()
    for column in transition.columns:
        assert transition_schema.field(column).type == pa.date32()
