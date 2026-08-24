import httpx
import pytest

from main import create_app


@pytest.mark.asyncio
async def test_console_origin_is_allowed_for_dataset_preflight() -> None:
    transport = httpx.ASGITransport(app=create_app())

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.options(
            "/v1/datasets",
            headers={
                "Origin": "http://localhost:5173",
                "Access-Control-Request-Method": "GET",
            },
        )

    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == ("http://localhost:5173")
