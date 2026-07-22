from unittest.mock import Mock, patch

import pytest
from fastapi import FastAPI

from py_common.runtime import create_fastapi_app, create_worker_app, run_asgi_app


@pytest.mark.anyio
async def test_create_fastapi_app_runs_lifecycle_handlers():
    startup_calls = []
    shutdown_calls = []

    async def startup(app: FastAPI) -> None:
        startup_calls.append(app.title)

    async def shutdown(app: FastAPI) -> None:
        shutdown_calls.append(app.title)

    app = create_fastapi_app(
        title="Test Service",
        description="Test description.",
        version="1.0.0",
        startup=startup,
        shutdown=shutdown,
    )

    await app.router.startup()
    await app.router.shutdown()

    assert startup_calls == ["Test Service"]
    assert shutdown_calls == ["Test Service"]


def test_run_asgi_app_delegates_to_uvicorn():
    with patch("py_common.runtime.asgi.uvicorn.run") as run:
        run_asgi_app("main:app", host="127.0.0.1", port=9000, reload=True)

    run.assert_called_once_with("main:app", host="127.0.0.1", port=9000, reload=True)


@pytest.mark.anyio
async def test_create_worker_app_starts_and_stops_worker_task():
    worker = Mock()

    async def worker_handler() -> None:
        worker()

    app = create_worker_app(
        worker_handler,
        title="Worker Service",
        description="Worker description.",
        version="1.0.0",
    )

    await app.router.startup()
    await app.router.shutdown()

    worker.assert_called_once_with()
