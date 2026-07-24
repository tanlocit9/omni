import pandas as pd

from app.handlers.stock_prices import normalize_stock_price_dataframe_columns


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
