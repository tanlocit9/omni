from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import pandas as pd
import pytest

from app.handlers.intraday_eod import normalize_intraday_trades, reconcile_against_eod
from app.messaging.messages import IntradayEodJobMessage
from app.stocks.clients.vci_intraday import VCIIntradayQuoteAdapter


@pytest.fixture
def message() -> IntradayEodJobMessage:
    return IntradayEodJobMessage.model_validate(
        {
            "jobDefinitionId": "job-1",
            "executionId": "exec-1",
            "parentExecutionId": "parent-1",
            "source": "VCI",
            "workType": "SYMBOL",
            "workKey": "HOSE-HPG",
            "symbolKey": "HOSE-HPG",
            "exchange": "HOSE",
            "tradingDate": "2026-09-09",
            "provider": "VCI",
        }
    )


def test_normalizes_redacted_vci_fixture(message: IntradayEodJobMessage) -> None:
    fixture = Path(__file__).parent / "fixtures" / "vci_intraday_normal.json"
    frame = pd.DataFrame(json.loads(fixture.read_text(encoding="utf-8")))

    normalized = normalize_intraday_trades(frame, message)

    assert normalized["provider_id"].tolist() == ["redacted-1", "redacted-2"]
    assert normalized["trade_value"].tolist() == [2660000.0, 5330000.0]
    assert str(normalized["timestamp"].dtype) == "datetime64[us, UTC]"


def test_rejects_provider_timestamp_on_different_local_date(
    message: IntradayEodJobMessage,
) -> None:
    frame = pd.DataFrame(
        [
            {
                "time": "2026-09-08T23:59:59+07:00",
                "price": 10,
                "volume": 1,
                "match_type": "LO",
                "id": "previous-day",
            }
        ]
    )

    with pytest.raises(ValueError, match="local date differs"):
        normalize_intraday_trades(frame, message)


def test_rejects_conflicting_duplicate_provider_id(
    message: IntradayEodJobMessage,
) -> None:
    frame = pd.DataFrame(
        [
            {
                "time": "2026-09-09T09:15:00+07:00",
                "price": 10,
                "volume": 1,
                "match_type": "LO",
                "id": "same",
            },
            {
                "time": "2026-09-09T09:15:00+07:00",
                "price": 11,
                "volume": 1,
                "match_type": "LO",
                "id": "same",
            },
        ]
    )

    with pytest.raises(ValueError, match="Conflicting duplicate"):
        normalize_intraday_trades(frame, message)


def test_reconciliation_ready_against_canonical_eod(
    message: IntradayEodJobMessage,
) -> None:
    normalized = pd.DataFrame(
        {
            "price": [10.0, 10.0],
            "volume": [100.0, 200.0],
            "trade_value": [1000.0, 2000.0],
        }
    )
    eod = pd.DataFrame(
        [
            {
                "date": "2026-09-09",
                "ad_close": 10.0,
                "total_volume": 300.0,
                "nm_value": 3000.0,
            }
        ]
    )

    result = reconcile_against_eod(normalized, eod, message)

    assert result.status.value == "READY"


def test_reconciliation_rejects_large_difference(
    message: IntradayEodJobMessage,
) -> None:
    normalized = pd.DataFrame(
        {
            "price": [10.0],
            "volume": [100.0],
            "trade_value": [1000.0],
        }
    )
    eod = pd.DataFrame(
        [
            {
                "date": "2026-09-09",
                "ad_close": 12.0,
                "total_volume": 300.0,
                "nm_value": 3600.0,
            }
        ]
    )

    result = reconcile_against_eod(normalized, eod, message)

    assert result.status.value == "REJECTED"


def test_reconciliation_requires_exact_eod_trading_date(
    message: IntradayEodJobMessage,
) -> None:
    normalized = pd.DataFrame({"price": [10.0], "volume": [1.0], "trade_value": [10.0]})
    eod = pd.DataFrame(
        [
            {
                "date": "2026-09-08",
                "ad_close": 10.0,
                "total_volume": 1.0,
                "nm_value": 10.0,
            }
        ]
    )

    with pytest.raises(ValueError, match="unavailable or ambiguous"):
        reconcile_against_eod(normalized, eod, message)


@pytest.mark.anyio
async def test_cursor_adapter_rejects_repeated_full_page_cursor() -> None:
    class Quote:
        def intraday(self, **kwargs):
            return pd.DataFrame(
                [{"time": "2026-09-09T09:15:00+07:00", "truncTime": "same"}]
            )

    adapter = VCIIntradayQuoteAdapter(lambda **kwargs: Quote(), page_size=1)

    with pytest.raises(ValueError, match="did not advance"):
        await adapter.fetch_session("HPG", date(2026, 9, 9))
