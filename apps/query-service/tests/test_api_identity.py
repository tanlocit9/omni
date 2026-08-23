from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI

from app.api import router
from app.models import QueryState


class CapturingQueryManager:
    def __init__(self) -> None:
        self.actor: str | None = None

    async def submit(self, payload, actor: str):
        self.actor = actor
        return SimpleNamespace(query_id="query-1", state=QueryState.QUEUED)


@pytest.fixture
def app_and_manager() -> tuple[FastAPI, CapturingQueryManager]:
    app = FastAPI()
    manager = CapturingQueryManager()
    app.state.query_manager = manager
    app.include_router(router)
    return app, manager


@pytest.mark.asyncio
async def test_submit_query_rejects_anonymous_operator(
    app_and_manager: tuple[FastAPI, CapturingQueryManager],
) -> None:
    app, manager = app_and_manager
    transport = httpx.ASGITransport(app=app)

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/v1/queries",
            json={"sql": "SELECT * FROM eod", "datasets": [{"dataset": "eod"}]},
        )

    assert response.status_code == 401
    assert response.json() == {"detail": "Authenticated operator identity is required"}
    assert manager.actor is None


@pytest.mark.asyncio
async def test_submit_query_propagates_authenticated_operator(
    app_and_manager: tuple[FastAPI, CapturingQueryManager],
) -> None:
    app, manager = app_and_manager
    transport = httpx.ASGITransport(app=app)

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/v1/queries",
            headers={"X-Omni-User": "  sod  "},
            json={"sql": "SELECT * FROM eod", "datasets": [{"dataset": "eod"}]},
        )

    assert response.status_code == 202
    assert response.json() == {"queryId": "query-1", "state": "QUEUED"}
    assert manager.actor == "sod"
