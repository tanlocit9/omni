import logging
from typing import Any

from fastapi import FastAPI, HTTPException, Request, status
from pydantic import ValidationError
from fastapi.responses import JSONResponse
from py_common.runtime import create_fastapi_app, run_asgi_app
from py_common.storage.exceptions import StorageError
from py_common.storage.parquet import ParquetStorage
from py_common.storage.ports import StorageProviderInfo
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry

from app.indicators.handler import IndicatorJobHandler
from app.indicators.kafka import IndicatorKafkaService
from app.indicators.messages import IndicatorJobMessage
from app.settings import settings
from app.storage.factory import create_storage_registry

_logger = logging.getLogger(__name__)


async def startup_event(app: FastAPI) -> None:
    _logger.info(
        "Starting up Analyzer service (indicatorKafkaEnabled=%s bootstrap=%s "
        "syncIndicatorsTopic=%s statusTopic=%s bucket=%s)",
        settings.indicator_kafka_enabled,
        settings.kafka.bootstrap_servers,
        settings.topic_sync_indicators,
        settings.sync_job_status_topic,
        settings.minio.bucket,
    )
    # Store settings in app state for access in dependencies
    app.state.settings = settings
    # Initialize storage registry and validate providers
    app.state.storage_registry = create_storage_registry(settings)
    await app.state.storage_registry.validate_all(fail_fast=True)
    app.state.parquet_storage = ParquetStorage(
        registry=app.state.storage_registry,
        provider=StorageProvider.MINIO,
        bucket=settings.minio.bucket,
    )
    app.state.indicator_handler = IndicatorJobHandler(
        settings,
        app.state.parquet_storage,
    )
    _logger.info("Analyzer storage providers validated")
    if settings.indicator_kafka_enabled:
        app.state.indicator_kafka_service = IndicatorKafkaService(
            settings,
            app.state.indicator_handler,
        )
        await app.state.indicator_kafka_service.start()
        _logger.info("Indicator Kafka service started")
    else:
        _logger.info("Storage providers validated. Indicator Kafka service disabled.")


async def shutdown_event(app: FastAPI) -> None:
    _logger.info("Shutting down Analyzer service...")
    if hasattr(app.state, "indicator_kafka_service"):
        await app.state.indicator_kafka_service.stop()


def create_app() -> FastAPI:
    """Create the FastAPI application."""
    app = create_fastapi_app(
        title="Omni Analyzer",
        description="Analytical REST API for market data and indicators.",
        version="0.1.0",
        startup=startup_event,
        shutdown=shutdown_event,
    )

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


@app.post("/v1/indicators/sync")
async def sync_indicators(
    payload: dict[str, Any],
    request: Request,
) -> dict[str, Any]:
    """Synchronously calculate indicators using the Kafka job payload contract."""
    try:
        message = IndicatorJobMessage.model_validate(payload)
        records_processed = await request.app.state.indicator_handler.handle(payload)
    except ValidationError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=exc.errors(include_context=False),
        ) from exc
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        ) from exc
    except StorageError:
        raise
    except Exception as exc:
        _logger.exception("Direct indicator sync failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Indicator sync failed: {exc}",
        ) from exc

    return {
        "accepted": True,
        "symbolKey": message.symbol_key,
        "indicatorSource": message.indicator_source,
        "timeframe": message.timeframe,
        "recordsProcessed": records_processed,
    }


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
    run_asgi_app("main:app", host="0.0.0.0", port=8000)
