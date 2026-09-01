from __future__ import annotations

from dataclasses import asdict
from typing import Annotated

from fastapi import APIRouter, Header, HTTPException, Query, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, Response

from app.dashboard import DashboardService, DashboardUnavailableError
from app.manager import QueryManager, QueryNotFoundError, QueryNotReadyError
from app.models import (
    DashboardFreshnessResponse,
    IchimokuSignalRow,
    IchimokuSignalsResponse,
    JsonQueryResult,
    MarketBreadthPayload,
    MarketBreadthResponse,
    QueryAccepted,
    QueryRequest,
    QueryStatusResponse,
    SignalHistoryResponse,
    SignalHistoryRow,
    TopMoverRow,
    TopMoversResponse,
)
from app.storage import DatasetCatalogService

router = APIRouter(prefix="/v1")


def _manager(request: Request) -> QueryManager:
    return request.app.state.query_manager


def _catalog(request: Request) -> DatasetCatalogService:
    return request.app.state.catalog_service


def _dashboard(request: Request) -> DashboardService:
    return request.app.state.dashboard_service


def _require_actor(actor: str | None) -> str:
    if actor is None or not actor.strip():
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authenticated operator identity is required",
        )
    return actor.strip()


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
    normalized_actor = _require_actor(actor)
    try:
        record = await _manager(request).submit(payload, normalized_actor)
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


@router.get("/dashboard/freshness", response_model=DashboardFreshnessResponse)
async def dashboard_freshness(
    request: Request,
    actor: Annotated[str | None, Header(alias="X-Omni-User")] = None,
) -> DashboardFreshnessResponse:
    _require_actor(actor)
    try:
        payload = await _dashboard(request).freshness()
    except DashboardUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return DashboardFreshnessResponse.model_validate(payload)


@router.get("/dashboard/market-breadth", response_model=MarketBreadthResponse)
async def dashboard_market_breadth(
    request: Request,
    exchange: Annotated[str, Query(pattern="^(?i:HOSE|HNX|UPCOM)$")],
    actor: Annotated[str | None, Header(alias="X-Omni-User")] = None,
) -> MarketBreadthResponse:
    _require_actor(actor)
    try:
        snapshot = await _dashboard(request).eod_snapshot(exchange)
    except DashboardUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    advancing = declining = unchanged = 0
    for row in snapshot.rows:
        previous = row.get("previous_close")
        close = row.get("close")
        if previous is None or close is None or previous == close:
            unchanged += 1
        elif close > previous:
            advancing += 1
        else:
            declining += 1
    return MarketBreadthResponse(
        effectiveDataDate=snapshot.effective_data_date,
        generatedAt=snapshot.generated_at,
        dataVersions=snapshot.data_versions,
        truncated=snapshot.truncated,
        metrics=MarketBreadthPayload(
            advancing=advancing,
            declining=declining,
            unchanged=unchanged,
            total=len(snapshot.rows),
        ),
    )


@router.get("/dashboard/top-movers", response_model=TopMoversResponse)
async def dashboard_top_movers(
    request: Request,
    exchange: Annotated[str, Query(pattern="^(?i:HOSE|HNX|UPCOM)$")],
    limit: Annotated[int, Query(ge=1, le=100)] = 10,
    actor: Annotated[str | None, Header(alias="X-Omni-User")] = None,
) -> TopMoversResponse:
    _require_actor(actor)
    if limit not in {5, 10, 20}:
        raise HTTPException(
            status_code=422,
            detail="Top movers limit must be one of: 5, 10, 20",
        )
    try:
        snapshot = await _dashboard(request).eod_snapshot(exchange)
    except DashboardUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    bounded_limit = min(limit, _dashboard(request).max_movers)
    movers = []
    for row in snapshot.rows:
        previous = row.get("previous_close")
        close = row.get("close")
        if previous in (None, 0) or close is None:
            continue
        movers.append(
            TopMoverRow(
                code=str(row["code"]).upper(),
                close=float(close),
                previousClose=float(previous),
                changePercent=((float(close) - float(previous)) / float(previous))
                * 100,
            )
        )
    gainers = sorted(
        (item for item in movers if item.change_percent > 0),
        key=lambda item: (-item.change_percent, item.code),
    )
    losers = sorted(
        (item for item in movers if item.change_percent < 0),
        key=lambda item: (item.change_percent, item.code),
    )
    return TopMoversResponse(
        effectiveDataDate=snapshot.effective_data_date,
        generatedAt=snapshot.generated_at,
        dataVersions=snapshot.data_versions,
        truncated=(
            snapshot.truncated
            or len(gainers) > bounded_limit
            or len(losers) > bounded_limit
        ),
        limit=bounded_limit,
        gainers=gainers[:bounded_limit],
        losers=losers[:bounded_limit],
    )


@router.get("/dashboard/ichimoku-signals", response_model=IchimokuSignalsResponse)
async def dashboard_ichimoku_signals(
    request: Request,
    exchange: Annotated[str, Query(pattern="^(?i:HOSE|HNX|UPCOM)$")],
    limit: Annotated[int, Query(ge=1, le=20)] = 10,
    actor: Annotated[str | None, Header(alias="X-Omni-User")] = None,
) -> IchimokuSignalsResponse:
    _require_actor(actor)
    try:
        snapshot = await _dashboard(request).latest_ichimoku_signals(exchange, limit)
    except DashboardUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    rows = [
        IchimokuSignalRow(
            code=str(row["symbol_key"]).split("-", maxsplit=1)[-1].upper(),
            signalDate=str(row["signal_date"]),
            signal=str(row["signal"]).upper(),
            price=float(row["signal_price"]),
            score=int(row["score"]),
            reasonCodes=list(row["reason_codes"]),
        )
        for row in snapshot.rows
    ]
    return IchimokuSignalsResponse(
        effectiveDataDate=snapshot.effective_data_date,
        generatedAt=snapshot.generated_at,
        dataVersions=snapshot.data_versions,
        truncated=snapshot.truncated,
        exchange=exchange.upper(),
        limit=limit,
        signals=rows,
    )


@router.get("/dashboard/signal-history", response_model=SignalHistoryResponse)
async def dashboard_signal_history(
    request: Request,
    exchange: Annotated[str | None, Query(pattern="^(?i:HOSE|HNX|UPCOM)$")] = None,
    symbol: Annotated[str | None, Query(pattern="^[A-Za-z0-9]+$")] = None,
    limit: Annotated[int, Query(ge=1, le=20)] = 10,
    actor: Annotated[str | None, Header(alias="X-Omni-User")] = None,
) -> SignalHistoryResponse:
    _require_actor(actor)
    try:
        snapshot = await _dashboard(request).signal_history(exchange, symbol, limit)
    except DashboardUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    rows = [
        SignalHistoryRow(
            code=str(row["symbol_key"]).split("-", maxsplit=1)[-1].upper(),
            signalDate=str(row["signal_date"]),
            signal=str(row["signal"]).upper(),
            price=float(row["signal_price"]),
            score=int(row["score"]),
            reasonCodes=list(row["reason_codes"]),
            actualReturnT5=row.get("actual_return_t5"),
            actualReturnT10=row.get("actual_return_t10"),
            actualReturnT15=row.get("actual_return_t15"),
            actualReturnT20=row.get("actual_return_t20"),
        )
        for row in snapshot.rows
    ]
    normalized_symbol = symbol.upper() if symbol else None
    if snapshot.selected_exchange is None:
        raise HTTPException(
            status_code=503,
            detail="Signal history exchange is unavailable",
        )
    return SignalHistoryResponse(
        effectiveDataDate=snapshot.effective_data_date,
        generatedAt=snapshot.generated_at,
        dataVersions=snapshot.data_versions,
        truncated=snapshot.truncated,
        exchange=snapshot.selected_exchange,
        availableExchanges=list(snapshot.available_exchanges),
        symbol=normalized_symbol,
        limit=limit,
        history=rows,
    )


@router.delete("/queries/{query_id}", response_model=QueryStatusResponse)
async def cancel_query(request: Request, query_id: str) -> QueryStatusResponse:
    try:
        await _manager(request).cancel(query_id)
        return _manager(request).status(query_id)
    except QueryNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Query not found") from exc
