"""Worker runtime helpers for Omni Python services."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import sys
from collections.abc import Awaitable, Callable

from fastapi import FastAPI

WorkerHandler = Callable[[], Awaitable[None]]


def configure_logging(
    *,
    level: int | str | None = None,
    format: str = "%(asctime)s %(levelname)s %(name)s - %(message)s",
) -> None:
    """Configure common service logging."""
    resolved_level = level or os.getenv("LOG_LEVEL", "INFO")
    if isinstance(resolved_level, str):
        resolved_level = logging.getLevelName(resolved_level.upper())
        if not isinstance(resolved_level, int):
            resolved_level = logging.INFO

    logging.basicConfig(
        level=resolved_level,
        format=format,
        stream=sys.stdout,
        force=True,
    )


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
    configure_logging()
    logger = logging.getLogger(__name__)
    app = FastAPI(title=title, description=description, version=version)

    def log_worker_task_result(task: asyncio.Task[None]) -> None:
        try:
            task.result()
        except asyncio.CancelledError:
            logger.info("Worker task cancelled for %s", title)
        except Exception:
            logger.exception("Worker task failed for %s", title)

    @app.on_event("startup")
    async def startup_event() -> None:
        logger.info("Starting worker app: %s", title)
        app.state.worker_task = asyncio.create_task(worker())
        app.state.worker_task.add_done_callback(log_worker_task_result)

    @app.on_event("shutdown")
    async def shutdown_event() -> None:
        logger.info("Stopping worker app: %s", title)
        task: asyncio.Task[None] | None = getattr(app.state, "worker_task", None)
        if task is None:
            return
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task

    @app.get("/health")
    async def health_check() -> dict[str, str]:
        return {"status": "ok"}

    return app
