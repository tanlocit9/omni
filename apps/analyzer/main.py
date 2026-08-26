import logging
from typing import Any

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse
from py_common.runtime import create_fastapi_app, run_asgi_app
from py_common.storage.exceptions import StorageError
from py_common.storage.manifest import ManifestReader, ManifestWriter
from py_common.storage.metadata_sync import EodMetadataSynchronizer
from py_common.storage.parquet import ParquetStorage
from py_common.storage.ports import (
    ListableStorage,
    ReadableStorage,
    StorageProviderInfo,
)
from py_common.storage.providers import StorageProvider
from py_common.storage.registry import StorageProviderRegistry
from pydantic import ValidationError

from app.indicators.handler import IndicatorJobHandler
from app.indicators.kafka import IndicatorKafkaService
from app.indicators.messages import IndicatorJobMessage
from app.metadata.handler import MetadataSyncJobHandler
from app.metadata.kafka import MetadataSyncKafkaService
from app.sector_transition.handler import SectorTransitionJobHandler
from app.sector_transition.kafka import (
    SectorTransitionAnalyzeKafkaService,
    SectorTransitionOutcomeEvaluationKafkaService,
)
from app.sector_transition.messages import (
    SectorTransitionAnalyzeJobMessage,
    SectorTransitionOutcomeEvaluationJobMessage,
)
from app.sector_wave.handler import SectorWaveJobHandler
from app.sector_wave.kafka import (
    SectorRotationBacktestKafkaService,
    SectorWaveSectorFeatureKafkaService,
    SectorWaveSymbolFeatureKafkaService,
)
from app.settings import settings
from app.signals.evaluation_kafka import SignalEvaluationKafkaService
from app.signals.evaluator import SignalOutcomeEvaluator
from app.signals.handler import SignalJobHandler
from app.signals.kafka import SignalKafkaService
from app.signals.messages import SignalEvaluationJobMessage, SignalJobMessage
from app.storage.factory import create_storage_registry

_logger = logging.getLogger(__name__)


async def startup_event(app: FastAPI) -> None:
    _logger.info(
        "Starting up Analyzer service (indicatorKafkaEnabled=%s "
        "metadataKafkaEnabled=%s signalKafkaEnabled=%s "
        "signalEvaluationKafkaEnabled=%s sectorWaveSymbolFeaturesKafkaEnabled=%s "
        "sectorWaveSectorFeaturesKafkaEnabled=%s sectorRotationBacktestKafkaEnabled=%s "
        "sectorTransitionAnalyzeKafkaEnabled=%s sectorTransitionOutcomeKafkaEnabled=%s "
        "bootstrap=%s syncIndicatorsTopic=%s syncSignalsTopic=%s "
        "evaluateSignalsTopic=%s "
        "precomputeSymbolFeaturesTopic=%s precomputeSectorFeaturesTopic=%s "
        "sectorRotationBacktestTopic=%s sectorTransitionAnalyzeTopic=%s "
        "sectorTransitionOutcomeTopic=%s metadataTopic=%s statusTopic=%s bucket=%s)",
        settings.indicator_kafka_enabled,
        settings.metadata_kafka_enabled,
        settings.signal_kafka_enabled,
        settings.signal_evaluation_kafka_enabled,
        settings.sector_wave_symbol_features_kafka_enabled,
        settings.sector_wave_sector_features_kafka_enabled,
        settings.sector_rotation_backtest_kafka_enabled,
        settings.sector_transition_analyze_kafka_enabled,
        settings.sector_transition_outcome_kafka_enabled,
        settings.kafka.bootstrap_servers,
        settings.topic_sync_indicators,
        settings.topic_sync_signals,
        settings.topic_evaluate_signals,
        settings.topic_precompute_symbol_features,
        settings.topic_precompute_sector_features,
        settings.topic_sector_rotation_backtest,
        settings.topic_sector_transition_analyze,
        settings.topic_sector_transition_evaluate_outcomes,
        settings.topic_sync_metadata,
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
    # Initialize manifest reader and writer for dataset metadata and lineage.
    app.state.manifest_reader = ManifestReader(
        registry=app.state.storage_registry,
        provider=StorageProvider.MINIO,
        bucket=settings.minio.bucket,
    )
    app.state.manifest_writer = ManifestWriter(
        registry=app.state.storage_registry,
        provider=StorageProvider.MINIO,
        bucket=settings.minio.bucket,
    )
    app.state.metadata_sync_handler = MetadataSyncJobHandler(
        EodMetadataSynchronizer(
            readable=app.state.storage_registry.get_port(
                StorageProvider.MINIO, ReadableStorage
            ),
            listable=app.state.storage_registry.get_port(
                StorageProvider.MINIO, ListableStorage
            ),
            reader=app.state.manifest_reader,
            writer=app.state.manifest_writer,
            bucket=settings.minio.bucket,
        )
    )
    app.state.indicator_handler = IndicatorJobHandler(
        settings,
        app.state.parquet_storage,
        app.state.manifest_reader,
        app.state.manifest_writer,
    )
    app.state.signal_handler = SignalJobHandler(
        settings,
        app.state.parquet_storage,
    )
    app.state.signal_outcome_evaluator = SignalOutcomeEvaluator(
        settings,
        app.state.parquet_storage,
    )
    app.state.sector_wave_handler = SectorWaveJobHandler(
        settings,
        app.state.parquet_storage,
    )
    app.state.sector_transition_handler = SectorTransitionJobHandler(
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
        _logger.info("Indicator Kafka service disabled")
    if settings.metadata_kafka_enabled:
        app.state.metadata_sync_kafka_service = MetadataSyncKafkaService(
            settings,
            app.state.metadata_sync_handler,
        )
        await app.state.metadata_sync_kafka_service.start()
        _logger.info("Metadata sync Kafka service started")
    else:
        _logger.info("Metadata sync Kafka service disabled")
    if settings.signal_kafka_enabled:
        app.state.signal_kafka_service = SignalKafkaService(
            settings,
            app.state.signal_handler,
        )
        await app.state.signal_kafka_service.start()
        _logger.info("Signal Kafka service started")
    else:
        _logger.info("Signal Kafka service disabled")
    if settings.signal_evaluation_kafka_enabled:
        app.state.signal_evaluation_kafka_service = SignalEvaluationKafkaService(
            settings,
            app.state.signal_outcome_evaluator,
        )
        await app.state.signal_evaluation_kafka_service.start()
        _logger.info("Signal evaluation Kafka service started")
    else:
        _logger.info("Signal evaluation Kafka service disabled")
    if settings.sector_wave_symbol_features_kafka_enabled:
        app.state.sector_wave_symbol_features_kafka_service = (
            SectorWaveSymbolFeatureKafkaService(
                settings,
                app.state.sector_wave_handler,
            )
        )
        await app.state.sector_wave_symbol_features_kafka_service.start()
        _logger.info("Sector Wave symbol features Kafka service started")
    else:
        _logger.info("Sector Wave symbol features Kafka service disabled")
    if settings.sector_wave_sector_features_kafka_enabled:
        app.state.sector_wave_sector_features_kafka_service = (
            SectorWaveSectorFeatureKafkaService(
                settings,
                app.state.sector_wave_handler,
            )
        )
        await app.state.sector_wave_sector_features_kafka_service.start()
        _logger.info("Sector Wave sector features Kafka service started")
    else:
        _logger.info("Sector Wave sector features Kafka service disabled")
    if settings.sector_rotation_backtest_kafka_enabled:
        app.state.sector_rotation_backtest_kafka_service = (
            SectorRotationBacktestKafkaService(
                settings,
                app.state.sector_wave_handler,
            )
        )
        await app.state.sector_rotation_backtest_kafka_service.start()
        _logger.info("Sector rotation backtest Kafka service started")
    else:
        _logger.info("Sector rotation backtest Kafka service disabled")
    if settings.sector_transition_analyze_kafka_enabled:
        app.state.sector_transition_analyze_kafka_service = (
            SectorTransitionAnalyzeKafkaService(
                settings,
                app.state.sector_transition_handler,
            )
        )
        await app.state.sector_transition_analyze_kafka_service.start()
        _logger.info("Sector Transition analyze Kafka service started")
    else:
        _logger.info("Sector Transition analyze Kafka service disabled")
    if settings.sector_transition_outcome_kafka_enabled:
        app.state.sector_transition_outcome_kafka_service = (
            SectorTransitionOutcomeEvaluationKafkaService(
                settings,
                app.state.sector_transition_handler,
            )
        )
        await app.state.sector_transition_outcome_kafka_service.start()
        _logger.info("Sector Transition outcome Kafka service started")
    else:
        _logger.info("Sector Transition outcome Kafka service disabled")


async def shutdown_event(app: FastAPI) -> None:
    _logger.info("Shutting down Analyzer service...")
    if hasattr(app.state, "indicator_kafka_service"):
        await app.state.indicator_kafka_service.stop()
    if hasattr(app.state, "metadata_sync_kafka_service"):
        await app.state.metadata_sync_kafka_service.stop()
    if hasattr(app.state, "signal_kafka_service"):
        await app.state.signal_kafka_service.stop()
    if hasattr(app.state, "signal_evaluation_kafka_service"):
        await app.state.signal_evaluation_kafka_service.stop()
    if hasattr(app.state, "sector_wave_symbol_features_kafka_service"):
        await app.state.sector_wave_symbol_features_kafka_service.stop()
    if hasattr(app.state, "sector_wave_sector_features_kafka_service"):
        await app.state.sector_wave_sector_features_kafka_service.stop()
    if hasattr(app.state, "sector_rotation_backtest_kafka_service"):
        await app.state.sector_rotation_backtest_kafka_service.stop()
    if hasattr(app.state, "sector_transition_analyze_kafka_service"):
        await app.state.sector_transition_analyze_kafka_service.stop()
    if hasattr(app.state, "sector_transition_outcome_kafka_service"):
        await app.state.sector_transition_outcome_kafka_service.stop()


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


@app.post("/v1/signals/sync")
async def sync_signals(
    payload: dict[str, Any],
    request: Request,
) -> dict[str, Any]:
    """Synchronously calculate Market Signal V1 using the Kafka job payload contract."""
    try:
        message = SignalJobMessage.model_validate(payload)
        transition = await request.app.state.signal_handler.handle(payload)
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
        _logger.exception("Direct signal sync failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Signal sync failed: {exc}",
        ) from exc

    return {
        "accepted": True,
        "symbolKey": message.symbol_key,
        "strategy": message.strategy,
        "timeframe": message.timeframe,
        "signalChanged": transition.signal_changed,
        "previousSignal": (
            transition.previous_signal.value if transition.previous_signal else None
        ),
        "newSignal": transition.new_signal.value,
    }


@app.post("/v1/signals/evaluate")
async def evaluate_signals(
    payload: dict[str, Any],
    request: Request,
) -> dict[str, Any]:
    """Synchronously update actual outcomes using the Kafka job payload contract."""
    try:
        message = SignalEvaluationJobMessage.model_validate(payload)
        evaluation = await request.app.state.signal_outcome_evaluator.evaluate(payload)
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
        _logger.exception("Direct signal evaluation failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Signal evaluation failed: {exc}",
        ) from exc

    return {
        "accepted": True,
        "exchange": message.exchange,
        "strategy": message.strategy,
        "timeframe": message.timeframe,
        "recordsScanned": evaluation.records_scanned,
        "recordsUpdated": evaluation.records_updated,
    }


@app.post("/v1/sector-transition/analyze")
async def analyze_sector_transition(
    payload: dict[str, Any],
    request: Request,
) -> dict[str, Any]:
    """Synchronously run Sector Transition analysis using the Kafka payload contract."""
    try:
        message = SectorTransitionAnalyzeJobMessage.model_validate(payload)
        handler = request.app.state.sector_transition_handler
        records_processed = await handler.handle_analyze(payload)
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
        _logger.exception("Direct Sector Transition analysis failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Sector Transition analysis failed: {exc}",
        ) from exc

    return {
        "accepted": True,
        "evaluationDate": message.evaluation_date.isoformat(),
        "strategy": message.strategy,
        "timeframe": message.timeframe,
        "sectorCodes": message.sector_codes,
        "predictionHorizons": message.prediction_horizons,
        "recordsProcessed": records_processed,
    }


@app.post("/v1/sector-transition/evaluate-outcomes")
async def evaluate_sector_transition_outcomes_endpoint(
    payload: dict[str, Any],
    request: Request,
) -> dict[str, Any]:
    """Synchronously evaluate Sector Transition outcomes using the Kafka payload."""
    try:
        message = SectorTransitionOutcomeEvaluationJobMessage.model_validate(payload)
        records_processed = (
            await request.app.state.sector_transition_handler.handle_evaluate_outcomes(
                payload
            )
        )
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
        _logger.exception("Direct Sector Transition outcome evaluation failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Sector Transition outcome evaluation failed: {exc}",
        ) from exc

    return {
        "accepted": True,
        "evaluationDate": message.evaluation_date.isoformat(),
        "strategy": message.strategy,
        "timeframe": message.timeframe,
        "sectorCodes": message.sector_codes,
        "predictionHorizons": message.prediction_horizons,
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
