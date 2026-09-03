from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pandas as pd
import pytest
from py_common.messaging import JobStatus
from py_common.storage.parquet import ParquetWriteResult

from app.handlers import stock_prices
from app.handlers.stock_prices import (
    normalize_stock_price_dataframe_columns,
    process_stock_price_message,
)


def test_normalize_stock_price_dataframe_columns_converts_minio_columns_to_snake_case():
    df = pd.DataFrame(
        [
            {
                "date": "2024-01-02",
                "totalVolume": 1000,
                "NMValue": 2000,
                "foreignBuyValue": 3000,
            }
        ]
    )

    normalized = normalize_stock_price_dataframe_columns(df)

    assert list(normalized.columns) == [
        "date",
        "total_volume",
        "nm_value",
        "foreign_buy_value",
    ]
    assert normalized.loc[0, "total_volume"] == 1000


def test_normalize_stock_price_dataframe_columns_supports_legacy_camel_case_snapshots():
    existing_df = pd.DataFrame([{"date": "2024-01-01", "totalVolume": 1000}])
    new_df = pd.DataFrame([{"date": "2024-01-02", "total_volume": 2000}])
    combined = pd.concat([existing_df, new_df], ignore_index=True)

    normalized = normalize_stock_price_dataframe_columns(combined)

    assert "totalVolume" not in normalized.columns
    assert "total_volume" in normalized.columns
    assert normalized["total_volume"].tolist() == [1000, 2000]


@pytest.mark.anyio
async def test_process_stock_price_completes_after_parquet_write(
    monkeypatch: pytest.MonkeyPatch,
):
    events: list[str] = []
    dataframe = pd.DataFrame([{"date": "2024-01-02", "close": 100.0}])
    client = AsyncMock()
    client.fetch_recent_stock.return_value = dataframe.to_dict("records")
    parquet_storage = AsyncMock()
    parquet_storage.read_optional_dataframe.return_value = None

    async def write_dataframe(*_args, **_kwargs):
        events.append("data")
        return ParquetWriteResult(
            object_name="eod/hose/hpg.parquet",
            checksum=f"sha256:{'a' * 64}",
            total_bytes=321,
        )

    parquet_storage.write_dataframe.side_effect = write_dataframe
    status_publisher = AsyncMock()
    monkeypatch.setattr(
        stock_prices,
        "settings",
        SimpleNamespace(get_eod_path=lambda _exchange, _code: "eod/hose/hpg.parquet"),
    )

    status = await process_stock_price_message(
        {
            "jobDefinitionId": "job-1",
            "executionId": "execution-1",
            "workType": "SYMBOL",
            "workKey": "HOSE-HPG",
            "symbolKey": "hose-hpg",
        },
        status_publisher,
        client,
        parquet_storage,
    )

    assert events == ["data"]
    assert status.status == JobStatus.SUCCESS
    status_publisher.publish.assert_awaited_once()


@pytest.mark.anyio
async def test_process_stock_price_normalizes_mixed_dates_before_sorting(
    monkeypatch: pytest.MonkeyPatch,
):
    client = AsyncMock()
    client.fetch_recent_stock.return_value = [
        {"date": "2024-01-02", "close": 102.0},
        {"date": "2024-01-01", "close": 101.0},
    ]
    parquet_storage = AsyncMock()
    parquet_storage.read_optional_dataframe.return_value = pd.DataFrame(
        [{"date": date(2023, 12, 29), "close": 100.0}]
    )
    status_publisher = AsyncMock()
    monkeypatch.setattr(
        stock_prices,
        "settings",
        SimpleNamespace(get_eod_path=lambda _exchange, _code: "eod/upcom/qtp.parquet"),
    )

    status = await process_stock_price_message(
        {
            "jobDefinitionId": "job-1",
            "executionId": "execution-1",
            "workType": "SYMBOL",
            "workKey": "UPCOM-QTP",
            "symbolKey": "upcom-qtp",
        },
        status_publisher,
        client,
        parquet_storage,
    )

    written = parquet_storage.write_dataframe.await_args.args[1]
    assert written["date"].tolist() == [
        date(2023, 12, 29),
        date(2024, 1, 1),
        date(2024, 1, 2),
    ]
    assert status.status == JobStatus.SUCCESS


@pytest.mark.anyio
async def test_process_stock_price_reports_error_when_parquet_write_fails(
    monkeypatch: pytest.MonkeyPatch,
):
    dataframe = pd.DataFrame([{"date": "2024-01-02", "close": 100.0}])
    client = AsyncMock()
    client.fetch_recent_stock.return_value = dataframe.to_dict("records")
    parquet_storage = AsyncMock()
    parquet_storage.read_optional_dataframe.return_value = None
    parquet_storage.write_dataframe.side_effect = RuntimeError("Parquet write failed")
    status_publisher = AsyncMock()
    monkeypatch.setattr(
        stock_prices,
        "settings",
        SimpleNamespace(get_eod_path=lambda _exchange, _code: "eod/hose/hpg.parquet"),
    )

    status = await process_stock_price_message(
        {
            "jobDefinitionId": "job-1",
            "executionId": "execution-1",
            "workType": "SYMBOL",
            "workKey": "HOSE-HPG",
            "symbolKey": "hose-hpg",
        },
        status_publisher,
        client,
        parquet_storage,
    )

    assert status.status == JobStatus.ERROR
    assert status.error_message == "Parquet write failed"
    status_publisher.publish.assert_awaited_once()
