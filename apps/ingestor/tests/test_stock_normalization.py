import pytest

from app.stocks.normalization import normalize_record_keys, to_snake_case


def test_to_snake_case_normalizes_common_provider_field_styles():
    assert to_snake_case("totalPages") == "total_pages"
    assert to_snake_case("TradingDate") == "trading_date"
    assert to_snake_case("NMValue") == "nm_value"
    assert to_snake_case("foreign-buy-value") == "foreign_buy_value"
    assert to_snake_case(" foreign Buy Value ") == "foreign_buy_value"


def test_normalize_record_keys_keeps_equal_duplicate_values():
    assert normalize_record_keys({"totalVolume": 100, "total_volume": 100}) == {
        "total_volume": 100
    }


def test_normalize_record_keys_rejects_conflicting_duplicate_values():
    with pytest.raises(ValueError, match="both map to 'total_volume'"):
        normalize_record_keys({"totalVolume": 100, "total_volume": 200})
