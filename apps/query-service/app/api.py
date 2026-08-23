from __future__ import annotations

from dataclasses import asdict
from typing import Annotated

from fastapi import APIRouter, Header, HTTPException, Query, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, Response

from app.manager import QueryManager, QueryNotFoundError, QueryNotReadyError
from app.models import JsonQueryResult, QueryAccepted, QueryRequest, QueryStatusResponse
from app.storage import DatasetCatalogService

router = APIRouter(prefix="/v1")


def _manager(request: Request) -> QueryManager:
    return request.app.state.query_manager


def _catalog(request: Request) -> DatasetCatalogService:
    return request.app.state.catalog_service


@router.get("/datasets")
async def list_datasets(request: Request) -> list[dict[str, str | None]]:
    return await _catalog(request).list_datasets()


@router.get("/datasets/{dataset}/partitions")
async def list_partitions(request: Request, dataset: str) -> list[dict]:
    manifests = await _catalog(request).list_partitions(dataset)
    return jsonable_encoder([asdict(item) for item in manifests])


@router.post(
    "/queries",
    response_model=QueryAccepted,
    status_code=status.HTTP_202_ACCEPTED,
)
async def submit_query(
    request: Request,
    payload: QueryRequest,
    actor: Annotated[str | None, Header(alias="X-Omni-User")] = None,
) -> QueryAccepted:
    if actor is None or not actor.strip():
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authenticated operator identity is required",
        )
    try:
        record = await _manager(request).submit(payload, actor.strip())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return QueryAccepted(queryId=record.query_id, state=record.state)


@router.get("/queries/{query_id}", response_model=QueryStatusResponse)
async def get_query(request: Request, query_id: str) -> QueryStatusResponse:
    try:
        return _manager(request).status(query_id)
    except QueryNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Query not found") from exc


@router.get("/queries/{query_id}/result")
async def get_query_result(
    request: Request,
    query_id: str,
    result_format: Annotated[
        str, Query(alias="format", pattern="^(json|arrow)$")
    ] = "json",
) -> Response:
    try:
        record, payload = _manager(request).result(query_id)
    except QueryNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Query not found") from exc
    except QueryNotReadyError as exc:
        raise HTTPException(status_code=409, detail=f"Query result is {exc}") from exc
    if result_format == "arrow":
        return Response(
            content=payload.arrow,
            media_type="application/vnd.apache.arrow.stream",
            headers={"X-Omni-Query-Id": query_id},
        )
    result = JsonQueryResult(
        queryId=query_id,
        columns=payload.columns,
        rows=payload.rows,
        rowCount=payload.row_count,
        truncated=payload.truncated,
        dataVersions=record.data_versions,
    )
    return JSONResponse(content=jsonable_encoder(result))


@router.delete("/queries/{query_id}", response_model=QueryStatusResponse)
async def cancel_query(request: Request, query_id: str) -> QueryStatusResponse:
    try:
        await _manager(request).cancel(query_id)
        return _manager(request).status(query_id)
    except QueryNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Query not found") from exc
