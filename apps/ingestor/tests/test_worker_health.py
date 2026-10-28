from types import SimpleNamespace

import pytest
from httpx import ASGITransport, AsyncClient

from main import app, service


@pytest.mark.anyio
async def test_ingestor_entrypoint_health_tracks_actual_routing_readiness(monkeypatch):
    # Exercise the production entrypoint's callback without starting infrastructure.
    monkeypatch.setattr(
        app.state, "worker_supervisor", SimpleNamespace(is_running=True), raising=False
    )
    monkeypatch.setattr(service, "_ready", False)
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        assert (await client.get("/health")).status_code == 503
        monkeypatch.setattr(service, "_ready", True)
        assert (await client.get("/health")).status_code == 200
