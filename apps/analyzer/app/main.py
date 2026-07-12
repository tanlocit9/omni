import asyncio
import logging

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse
from py_common.storage.exceptions import StorageError
from py_common.storage.ports import StorageProviderInfo
from py_common.storage.registry import StorageProviderRegistry

from app.controllers.v1 import stock
from app.settings import settings
from app.storage.factory import create_storage_registry


_logger = logging.getLogger(__name__)


def create_app() -> FastAPI:
    """Create the FastAPI application."""
    app = FastAPI(
        title="Omni Analyzer",
        description="Analytical REST API for market data and indicators.",
        version="0.1.0",
    )

    # Register API routers
    app.include_router(stock.router, prefix="/v1")

    @app.on_event("startup")
    async def startup_event() -> None:
        _logger.info("Starting up Analyzer service...")
        # Store settings in app state for access in dependencies
        app.state.settings = settings
        # Initialize storage registry and validate providers
        app.state.storage_registry = create_storage_registry(settings)
        await app.state.storage_registry.validate_all(fail_fast=True)
        _logger.info("Storage providers validated.")

    @app.on_event("shutdown")
    async def shutdown_event() -> None:
        _logger.info("Shutting down Analyzer service...")

    @app.exception_handler(StorageError)
    async def storage_exception_handler(
        request: Request,
        exc: StorageError,
    ) -> JSONResponse:
        _logger.error(f"Storage error: {exc}", exc_info=True)
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"message": f"Storage operation failed: {exc}"},
        )

    return app


app = create_app()


@app.get("/health")
async def health_check(request: Request) -> dict[str, Any]:
    """Health check endpoint."""
    storage_status: dict[str, Any] = {}
    registry: StorageProviderRegistry = request.app.state.storage_registry
    for provider_name, adapter in registry._adapters.items():
        info: StorageProviderInfo = adapter
        storage_status[provider_name.value] = {
            "is_active": info.is_active,
            "last_error": info.last_error,
        }

    return {
        "status": "ok",
        "storage": storage_status,
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)