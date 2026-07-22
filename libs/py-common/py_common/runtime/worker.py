"""Worker runtime helpers for Omni Python services."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

from fastapi import FastAPI

WorkerHandler = Callable[[], Awaitable[None]]


def configure_logging(
    *,
    level: int = logging.INFO,
    format: str = "%(asctime)s %(levelname)s %(name)s - %(message)s",
) -> None:
    """Configure common service logging."""
    logging.basicConfig(level=level, format=format)


def run_async_worker(worker: WorkerHandler) -> None:
    """Run an async worker with common logging and asyncio lifecycle handling."""
    configure_logging()
    asyncio.run(worker())


def create_worker_app(
    worker: WorkerHandler,
    *,
    title: str,
    description: str,
    version: str,
) -> FastAPI:
    """Create an ASGI wrapper app for an async worker.

    The worker starts during FastAPI startup and is cancelled during shutdown.
    This lets worker services use the same Uvicorn and Uvicorn-HMR run modes as
    HTTP services while still keeping the business worker logic app-local.
    """
    app = FastAPI(title=title, description=description, version=version)

    @app.on_event("startup")
    async def startup_event() -> None:
        app.state.worker_task = asyncio.create_task(worker())

    @app.on_event("shutdown")
    async def shutdown_event() -> None:
        task: asyncio.Task[None] | None = getattr(app.state, "worker_task", None)
        if task is None:
            return
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    @app.get("/health")
    async def health_check() -> dict[str, str]:
        return {"status": "ok"}

    return app
