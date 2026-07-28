"""ASGI runtime helpers for Omni Python HTTP services."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

import uvicorn
from fastapi import FastAPI

from py_common.runtime.worker import configure_logging

LifecycleHandler = Callable[[FastAPI], Awaitable[None]]


def create_fastapi_app(
    *,
    title: str,
    description: str,
    version: str,
    startup: LifecycleHandler | None = None,
    shutdown: LifecycleHandler | None = None,
    **kwargs: Any,
) -> FastAPI:
    """Create a FastAPI app with optional shared lifecycle hooks."""
    configure_logging()
    app = FastAPI(
        title=title,
        description=description,
        version=version,
        **kwargs,
    )

    if startup is not None:

        @app.on_event("startup")
        async def startup_event() -> None:
            await startup(app)

    if shutdown is not None:

        @app.on_event("shutdown")
        async def shutdown_event() -> None:
            await shutdown(app)

    return app


def run_asgi_app(
    app: str,
    *,
    host: str = "0.0.0.0",
    port: int = 8000,
    reload: bool = False,
    **kwargs: Any,
) -> None:
    """Run an ASGI app through Uvicorn."""
    configure_logging()
    uvicorn.run(app, host=host, port=port, reload=reload, **kwargs)
