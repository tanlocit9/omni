from types import SimpleNamespace

import pytest
from fastapi import FastAPI, HTTPException

from main import health_check


@pytest.mark.anyio
@pytest.mark.parametrize("running", [True, False])
async def test_analyzer_health_checks_each_enabled_consumer(running):
    app = FastAPI()
    app.state.storage_registry = SimpleNamespace(_adapters={})
    app.state.indicator_kafka_service = SimpleNamespace(is_running=True)
    app.state.metadata_sync_kafka_service = SimpleNamespace(is_running=running)
    request = SimpleNamespace(app=app)
    if running:
        response = await health_check(request)
        assert response["status"] == "ok"
        assert all(response["workers"].values())
    else:
        with pytest.raises(HTTPException) as error:
            await health_check(request)
        assert error.value.status_code == 503
        assert error.value.detail["workers"]["metadata_sync_kafka_service"] is False
