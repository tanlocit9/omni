from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI
from py_common.storage.exceptions import ManifestNotFoundError

from app.api import router
from app.dashboard import DashboardService, DashboardSnapshot, DashboardUnavailableError
from app.settings import QueryServiceSettings

HASH_A = "sha256:" + "a" * 64
HASH_B = "sha256:" + "b" * 64


class FakeDashboardService:
    max_movers = 20

    async def freshness(self):
        return {
            "generatedAt": "2026-08-31T12:00:00+00:00",
            "datasets": [
                {
                    "dataset": "eod",
                    "status": "READY",
                    "generatedAt": "2026-08-31T11:00:00+00:00",
                    "effectiveDataDate": "2026-08-29",
                    "dataVersion": HASH_A,
                    "partitionCount": 2,
                }
            ],
        }

    async def eod_snapshot(self, exchange: str):
        if exchange.upper() == "UPCOM":
            raise DashboardUnavailableError("No EOD partitions are available for UPCOM")
        return DashboardSnapshot(
            effective_data_date="2026-08-29",
            generated_at="2026-08-31T11:00:00+00:00",
            data_versions={"AAA": HASH_A, "BBB": HASH_B},
            rows=[
                {
                    "code": "AAA",
                    "price_date": "2026-08-29",
                    "close": 110.0,
                    "previous_close": 100.0,
                },
                {
                    "code": "BBB",
                    "price_date": "2026-08-29",
                    "close": 90.0,
                    "previous_close": 100.0,
                },
            ],
            truncated=False,
        )

    async def latest_ichimoku_signals(self, exchange: str, limit: int):
        if exchange.upper() == "UPCOM":
            raise DashboardUnavailableError("No READY Ichimoku signals are available")
        return DashboardSnapshot(
            effective_data_date="2026-08-29",
            generated_at="2026-08-31T11:00:00+00:00",
            data_versions={"signals": HASH_A},
            rows=[
                {
                    "symbol_key": "HOSE-AAA",
                    "signal_date": "2026-08-29",
                    "signal": "BULLISH",
                    "signal_price": 110.0,
                    "score": 4,
                    "reason_codes": ["PRICE_ABOVE_CLOUD", "SCORE_4"],
                    "generated_at": "2026-08-31T11:00:00+00:00",
                }
            ][:limit],
            truncated=False,
        )

    async def signal_history(
        self, exchange: str | None, symbol: str | None, limit: int
    ):
        selected_exchange = exchange.upper() if exchange else "HNX"
        return DashboardSnapshot(
            effective_data_date="2026-08-29",
            generated_at="2026-08-31T11:00:00+00:00",
            data_versions={"signals": HASH_B},
            rows=[
                {
                    "symbol_key": (
                        f"{selected_exchange}-{symbol.upper() if symbol else 'AAA'}"
                    ),
                    "signal_date": "2026-08-29",
                    "signal": "BULLISH",
                    "signal_price": 110.0,
                    "score": 4,
                    "reason_codes": ["PRICE_ABOVE_MA50", "SCORE_4"],
                    "actual_return_t5": 3.5,
                    "actual_return_t10": 5.0,
                    "actual_return_t15": -1.25,
                    "actual_return_t20": None,
                    "generated_at": "2026-08-31T11:00:00+00:00",
                }
            ][:limit],
            truncated=False,
            available_exchanges=("HNX", "HOSE"),
            selected_exchange=selected_exchange,
        )


@pytest.fixture
def app() -> FastAPI:
    application = FastAPI()
    application.state.dashboard_service = FakeDashboardService()
    application.state.query_manager = SimpleNamespace()
    application.include_router(router)
    return application


@pytest.mark.asyncio
async def test_dashboard_endpoints_require_operator_identity(app: FastAPI) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/v1/dashboard/freshness")
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_freshness_exposes_date_and_version(app: FastAPI) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/v1/dashboard/freshness", headers={"X-Omni-User": "operator"}
        )
    assert response.status_code == 200
    item = response.json()["datasets"][0]
    assert item["effectiveDataDate"] == "2026-08-29"
    assert item["dataVersion"] == HASH_A


@pytest.mark.asyncio
async def test_market_breadth_uses_one_effective_date(app: FastAPI) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/v1/dashboard/market-breadth?exchange=HOSE",
            headers={"X-Omni-User": "operator"},
        )
    assert response.status_code == 200
    payload = response.json()
    assert payload["effectiveDataDate"] == "2026-08-29"
    assert payload["metrics"] == {
        "advancing": 1,
        "declining": 1,
        "unchanged": 0,
        "total": 2,
    }
    assert payload["dataVersions"] == {"AAA": HASH_A, "BBB": HASH_B}


@pytest.mark.asyncio
async def test_top_movers_return_independently_bounded_lists(app: FastAPI) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/v1/dashboard/top-movers?exchange=HOSE&limit=5",
            headers={"X-Omni-User": "operator"},
        )
    assert response.status_code == 200
    payload = response.json()
    assert payload["limit"] == 5
    assert [row["code"] for row in payload["gainers"]] == ["AAA"]
    assert [row["code"] for row in payload["losers"]] == ["BBB"]
    assert payload["truncated"] is False


@pytest.mark.asyncio
async def test_top_movers_reject_unknown_limit(app: FastAPI) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/v1/dashboard/top-movers?exchange=HOSE&limit=7",
            headers={"X-Omni-User": "operator"},
        )
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_missing_source_is_unavailable_not_zero(app: FastAPI) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/v1/dashboard/market-breadth?exchange=UPCOM",
            headers={"X-Omni-User": "operator"},
        )
    assert response.status_code == 503
    assert "No EOD partitions" in response.json()["detail"]


@pytest.mark.asyncio
async def test_ichimoku_feed_exposes_precomputed_reasons_and_provenance(
    app: FastAPI,
) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/v1/dashboard/ichimoku-signals?exchange=HOSE&limit=5",
            headers={"X-Omni-User": "operator"},
        )
    assert response.status_code == 200
    payload = response.json()
    assert payload["dataVersions"] == {"signals": HASH_A}
    assert payload["signals"][0] == {
        "code": "AAA",
        "signalDate": "2026-08-29",
        "signal": "BULLISH",
        "price": 110.0,
        "score": 4,
        "reasonCodes": ["PRICE_ABOVE_CLOUD", "SCORE_4"],
    }


@pytest.mark.asyncio
async def test_signal_history_returns_persisted_outcomes_when_available(
    app: FastAPI,
) -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/v1/dashboard/signal-history?exchange=HOSE&symbol=hpg&limit=5",
            headers={"X-Omni-User": "operator"},
        )
    assert response.status_code == 200
    payload = response.json()
    assert payload["symbol"] == "HPG"
    assert payload["availableExchanges"] == ["HNX", "HOSE"]
    assert payload["dataVersions"] == {"signals": HASH_B}
    assert payload["history"][0]["code"] == "HPG"
    assert payload["history"][0]["actualReturnT5"] == 3.5
    assert payload["history"][0]["actualReturnT20"] is None


@pytest.mark.asyncio
async def test_freshness_does_not_mark_large_discovered_dataset_unavailable() -> None:
    manifest = SimpleNamespace(
        status="READY",
        generatedAt="2026-08-31T11:00:00+00:00",
        minTimestamp="2026-08-01T00:00:00+00:00",
        maxTimestamp="2026-08-29T00:00:00+00:00",
        dataVersion=HASH_A,
    )

    class LargeCatalog:
        async def list_datasets(self):
            return [{"name": "eod"}]

        async def list_partitions(self, dataset: str):
            assert dataset == "eod"
            return [manifest] * 1001

    service = DashboardService(
        catalog=LargeCatalog(),
        resolver=SimpleNamespace(),
        executor=SimpleNamespace(),
        settings=QueryServiceSettings(dashboard_max_partitions=1000),
    )

    payload = await service.freshness()

    assert payload["datasets"][0]["status"] == "READY"
    assert payload["datasets"][0]["partitionCount"] == 1001


@pytest.mark.asyncio
async def test_signal_history_discovers_only_matching_ready_exchanges() -> None:
    def manifest(exchange: str, strategy: str, timeframe: str, status: str = "READY"):
        return SimpleNamespace(
            status=status,
            partition={
                "exchange": exchange,
                "strategy": strategy,
                "timeframe": timeframe,
            },
            dataVersion=HASH_A,
            columns=[],
        )

    class SignalCatalog:
        async def list_partitions(self, dataset: str):
            assert dataset == "signals"
            return [
                manifest("hose", "trend_momentum_v1", "1d"),
                manifest("hnx", "trend_momentum_v1", "1d"),
                manifest("upcom", "ichimoku_v1", "1d"),
                manifest("upcom", "trend_momentum_v1", "1h"),
            ]

    class CapturingResolver:
        async def resolve_many(self, refs):
            ref = refs[0]
            assert ref.partition["exchange"] == "hnx"
            raise ManifestNotFoundError(ref.dataset, ref.partition)

    service = DashboardService(
        catalog=SignalCatalog(),
        resolver=CapturingResolver(),
        executor=SimpleNamespace(),
        settings=QueryServiceSettings(),
    )

    with pytest.raises(DashboardUnavailableError, match="HNX"):
        await service.signal_history(None, None, 10)


@pytest.mark.asyncio
async def test_signal_history_maps_missing_ready_manifest_to_unavailable() -> None:
    class MissingManifestResolver:
        async def resolve_many(self, refs):
            ref = refs[0]
            raise ManifestNotFoundError(ref.dataset, ref.partition)

    manifest = SimpleNamespace(
        status="READY",
        partition={
            "exchange": "hose",
            "strategy": "trend_momentum_v1",
            "timeframe": "1d",
        },
        dataVersion=HASH_A,
    )

    class SignalCatalog:
        async def list_partitions(self, dataset: str):
            assert dataset == "signals"
            return [manifest]

    service = DashboardService(
        catalog=SignalCatalog(),
        resolver=MissingManifestResolver(),
        executor=SimpleNamespace(),
        settings=QueryServiceSettings(),
    )

    with pytest.raises(DashboardUnavailableError, match="No READY Trend Momentum"):
        await service.signal_history("HOSE", None, 10)


def test_dashboard_settings_keep_hard_bounds() -> None:
    settings = QueryServiceSettings()
    assert settings.dashboard_max_partitions <= 5000
    assert settings.dashboard_max_movers <= 100
