import asyncio
from contextlib import asynccontextmanager
from unittest.mock import Mock, patch

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from py_common.runtime import create_fastapi_app, create_worker_app, run_asgi_app
from py_common.runtime.supervision import WorkerSupervisor
from py_common.runtime.worker import run_async_worker


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

    async with app_lifespan(app):
        assert startup_calls == ["Test Service"]
        assert shutdown_calls == []

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

    async with app_lifespan(app):
        await asyncio.sleep(0)
        worker.assert_called_once_with()


@asynccontextmanager
async def app_lifespan(app: FastAPI):
    async with app.router.lifespan_context(app):
        yield


@pytest.mark.asyncio
async def test_supervisor_marks_failure_unhealthy_recovers_and_cancels():
    entered = asyncio.Event()
    calls = 0

    async def session():
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("broker outage")
        entered.set()
        await asyncio.Future()

    supervisor = WorkerSupervisor(
        session, name="test", retry_delay=0.02, max_delay=0.03
    )
    assert not supervisor.is_running
    task = asyncio.create_task(supervisor.run())
    await asyncio.sleep(0)
    assert not supervisor.is_running
    await asyncio.wait_for(entered.wait(), 1)
    assert supervisor.is_running
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not supervisor.is_running
    assert calls == 2


@pytest.mark.asyncio
async def test_worker_health_rejects_missing_exited_or_not_ready_worker():
    async def finished():
        return

    app = create_worker_app(finished, title="test", description="test", version="1")
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        assert (await client.get("/health")).status_code == 503
        async with app_lifespan(app):
            await asyncio.sleep(0)
            assert (await client.get("/health")).status_code == 503

    ready = False

    async def running():
        await asyncio.Future()

    app = create_worker_app(
        running, title="test", description="test", version="1", readiness=lambda: ready
    )
    async with app_lifespan(app):
        await asyncio.sleep(0)
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            assert (await client.get("/health")).status_code == 503
            ready = True
            assert (await client.get("/health")).status_code == 200


@pytest.mark.asyncio
async def test_shutdown_cleanup_failure_does_not_restart_worker():
    entered = asyncio.Event()

    async def session():
        entered.set()
        try:
            await asyncio.Future()
        finally:
            raise RuntimeError("cleanup failed")

    supervisor = WorkerSupervisor(session, name="test", retry_delay=0.01)
    task = asyncio.create_task(supervisor.run())
    await entered.wait()
    task.cancel()
    with pytest.raises(RuntimeError, match="cleanup failed"):
        await asyncio.wait_for(task, 1)
    assert not supervisor.is_running


def test_cli_worker_recovers_failure_then_honors_cancellation():
    calls = []

    async def worker():
        calls.append("attempt")
        if len(calls) == 1:
            raise RuntimeError("transient session failure")
        raise asyncio.CancelledError()

    with pytest.raises(asyncio.CancelledError):
        run_async_worker(worker)
    assert len(calls) == 2
