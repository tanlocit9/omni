from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from py_common.runtime import create_fastapi_app

from app.api import router
from app.audit import StructuredLogAuditSink
from app.executor import DuckDBExecutor
from app.manager import QueryManager
from app.settings import settings
from app.storage import build_metadata_services, create_storage_registry

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    registry = create_storage_registry(settings)
    await registry.validate_all(fail_fast=True)
    resolver, catalog = build_metadata_services(registry, settings)
    app.state.catalog_service = catalog
    app.state.query_manager = QueryManager(
        resolver=resolver,
        executor=DuckDBExecutor(settings),
        settings=settings,
        audit_sink=StructuredLogAuditSink(),
    )
    yield


def create_app() -> FastAPI:
    app = create_fastapi_app(
        title="Omni Query Service",
        description="Private read-only server-side query API for READY datasets.",
        version="0.1.0",
        lifespan=lifespan,
    )
    logger.info("Configuring CORS allowed origins: %s", settings.query_cors_origins)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.query_cors_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Content-Type", "X-Omni-User"],
    )
    app.include_router(router)
    return app


app = create_app()
