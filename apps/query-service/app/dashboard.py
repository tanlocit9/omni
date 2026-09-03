from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from py_common.storage.exceptions import ManifestNotFoundError

from app.executor import DuckDBExecutor
from app.models import DatasetRef
from app.security import validate_read_only_sql
from app.settings import QueryServiceSettings
from app.storage import DatasetCatalogService, DatasetResolver, ResolvedDataset

_ALLOWED_EXCHANGES = {"HOSE", "HNX", "UPCOM"}
_REQUIRED_EOD_COLUMNS = {"date", "close"}
_REQUIRED_SIGNAL_COLUMNS = {
    "symbol_key",
    "signal_date",
    "signal",
    "signal_price",
    "score",
    "reason_codes",
    "generated_at",
}
_SIGNAL_OUTCOME_COLUMNS = {
    "actual_return_t5",
    "actual_return_t10",
    "actual_return_t15",
    "actual_return_t20",
}


class DashboardUnavailableError(RuntimeError):
    """Raised when a dashboard source cannot truthfully answer a request."""


@dataclass(frozen=True)
class DashboardSnapshot:
    effective_data_date: str
    generated_at: str
    data_versions: dict[str, str]
    rows: list[dict[str, Any]]
    truncated: bool
    available_exchanges: tuple[str, ...] = ()
    selected_exchange: str | None = None


class DashboardService:
    def __init__(
        self,
        catalog: DatasetCatalogService,
        resolver: DatasetResolver,
        executor: DuckDBExecutor,
        settings: QueryServiceSettings,
    ) -> None:
        self._catalog = catalog
        self._resolver = resolver
        self._executor = executor
        self._settings = settings

    @property
    def max_movers(self) -> int:
        return self._settings.dashboard_max_movers

    async def freshness(self) -> dict[str, Any]:
        datasets = await self._catalog.list_datasets()
        if len(datasets) > self._settings.dashboard_max_datasets:
            raise DashboardUnavailableError(
                "Dataset catalog exceeds dashboard freshness bounds"
            )
        items: list[dict[str, Any]] = []
        for dataset in datasets:
            name = str(dataset["name"])
            manifests = await self._list_partitions(name)
            if not manifests:
                items.append({"dataset": name, "status": "UNAVAILABLE"})
                continue
            latest = max(manifests, key=lambda item: item.generatedAt)
            items.append(
                {
                    "dataset": name,
                    "status": latest.status,
                    "generatedAt": latest.generatedAt,
                    "effectiveDataDate": _manifest_effective_date(latest),
                    "dataVersion": latest.dataVersion,
                    "partitionCount": len(manifests),
                }
            )
        return {"generatedAt": datetime.now(UTC).isoformat(), "datasets": items}

    async def eod_snapshot(self, exchange: str) -> DashboardSnapshot:
        normalized_exchange = exchange.upper()
        if normalized_exchange not in _ALLOWED_EXCHANGES:
            raise ValueError(f"Unsupported exchange: {exchange}")

        manifests = [
            item
            for item in await self._list_partitions("eod")
            if item.partition.get("exchange", "").upper() == normalized_exchange
            and item.partition.get("code")
        ]
        if not manifests:
            raise DashboardUnavailableError(
                f"No EOD partitions are available for {normalized_exchange}"
            )
        if len(manifests) > self._settings.dashboard_max_partitions:
            raise DashboardUnavailableError(
                "EOD partition count exceeds dashboard bounds"
            )
        scan_bytes = sum(item.totalBytes for item in manifests)
        if scan_bytes > self._settings.query_max_scan_bytes:
            raise DashboardUnavailableError("EOD data exceeds the dashboard scan bound")

        columns = {column.name for column in manifests[0].columns}
        if not _REQUIRED_EOD_COLUMNS.issubset(columns):
            raise DashboardUnavailableError(
                "EOD source is missing required date/close columns"
            )

        refs = [
            DatasetRef(dataset="eod", partition=item.partition) for item in manifests
        ]
        resolved = await self._resolver.resolve_many(refs)
        combined = ResolvedDataset(
            view_name="eod_market",
            manifest=manifests[0],
            paths=[path for item in resolved for path in item.paths],
            include_filename=True,
        )
        sql = validate_read_only_sql(
            """
            WITH sequenced AS (
              SELECT
                regexp_extract(filename, '([^/\\\\]+)\\.parquet$', 1) AS code,
                CAST(date AS DATE) AS price_date,
                CAST(close AS DOUBLE) AS close,
                LAG(CAST(close AS DOUBLE)) OVER (
                  PARTITION BY filename
                  ORDER BY CAST(date AS DATE)
                ) AS previous_close
              FROM eod_market
            ), latest_date AS (
              SELECT MAX(price_date) AS effective_date FROM sequenced
            )
            SELECT code, price_date, close, previous_close
            FROM sequenced, latest_date
            WHERE price_date = effective_date
            ORDER BY code
            """,
            {"eod_market"},
        )
        query_id = str(uuid.uuid4())
        try:
            payload = await asyncio.wait_for(
                self._executor.execute(
                    query_id,
                    sql,
                    [combined],
                    {},
                    min(len(manifests), self._settings.query_max_row_limit),
                ),
                timeout=self._settings.query_timeout_seconds,
            )
        except TimeoutError as exc:
            self._executor.cancel(query_id)
            raise DashboardUnavailableError(
                "EOD dashboard query exceeded the configured timeout"
            ) from exc
        if not payload.rows:
            raise DashboardUnavailableError("EOD source contains no market rows")
        effective_date = str(payload.rows[0]["price_date"])
        return DashboardSnapshot(
            effective_data_date=effective_date,
            generated_at=max(item.generatedAt for item in manifests),
            data_versions={
                item.partition["code"]: item.dataVersion for item in manifests
            },
            rows=payload.rows,
            truncated=payload.truncated,
        )

    async def latest_ichimoku_signals(
        self, exchange: str, limit: int
    ) -> DashboardSnapshot:
        normalized_exchange = exchange.upper()
        if normalized_exchange not in _ALLOWED_EXCHANGES:
            raise ValueError(f"Unsupported exchange: {exchange}")
        partition = {
            "strategy": "ichimoku_v1",
            "timeframe": "1d",
            "exchange": normalized_exchange.lower(),
        }
        try:
            resolved = (
                await self._resolver.resolve_many(
                    [
                        DatasetRef(
                            dataset="signals", partition=partition, alias="signals_feed"
                        )
                    ]
                )
            )[0]
        except (FileNotFoundError, ManifestNotFoundError, ValueError) as exc:
            raise DashboardUnavailableError(
                f"No READY Ichimoku signals are available for {normalized_exchange}"
            ) from exc
        columns = {column.name for column in resolved.manifest.columns}
        if not _REQUIRED_SIGNAL_COLUMNS.issubset(columns):
            raise DashboardUnavailableError(
                "Ichimoku signals are missing required columns"
            )
        sql = validate_read_only_sql(
            """
            WITH ranked AS (
              SELECT *, ROW_NUMBER() OVER (
                PARTITION BY symbol_key
                ORDER BY CAST(signal_date AS DATE) DESC, generated_at DESC
              ) AS position
              FROM signals_feed
            )
            SELECT symbol_key, signal_date, signal, signal_price, score,
                   reason_codes, generated_at
            FROM ranked WHERE position = 1
            ORDER BY ABS(score) DESC, symbol_key
            """,
            {"signals_feed"},
        )
        payload = await self._executor.execute(
            str(uuid.uuid4()), sql, [resolved], {}, limit
        )
        if not payload.rows:
            raise DashboardUnavailableError("Ichimoku signal source contains no rows")
        return DashboardSnapshot(
            effective_data_date=max(str(row["signal_date"]) for row in payload.rows),
            generated_at=resolved.manifest.generatedAt,
            data_versions={"signals": resolved.manifest.dataVersion},
            rows=payload.rows,
            truncated=payload.truncated,
        )

    async def signal_history(
        self, exchange: str | None, symbol: str | None, limit: int
    ) -> DashboardSnapshot:
        manifests = [
            item
            for item in await self._list_partitions("signals")
            if item.status == "READY"
            and item.partition.get("strategy") == "trend_momentum_v1"
            and item.partition.get("timeframe") == "1d"
            and item.partition.get("exchange", "").upper() in _ALLOWED_EXCHANGES
        ]
        available_exchanges = tuple(
            sorted({item.partition["exchange"].upper() for item in manifests})
        )
        if not available_exchanges:
            raise DashboardUnavailableError(
                "No READY Trend Momentum signal history partitions are available"
            )
        normalized_exchange = exchange.upper() if exchange else available_exchanges[0]
        if normalized_exchange not in available_exchanges:
            raise DashboardUnavailableError(
                "Trend Momentum signal history is not available for "
                f"{normalized_exchange}"
            )
        manifest = next(
            item
            for item in manifests
            if item.partition["exchange"].upper() == normalized_exchange
        )
        try:
            resolved = (
                await self._resolver.resolve_many(
                    [
                        DatasetRef(
                            dataset="signals",
                            partition=manifest.partition,
                            data_version=manifest.dataVersion,
                            alias="signal_history",
                        )
                    ]
                )
            )[0]
        except (FileNotFoundError, ManifestNotFoundError, ValueError) as exc:
            raise DashboardUnavailableError(
                "No READY Trend Momentum signal history is available for "
                f"{normalized_exchange}"
            ) from exc
        columns = {column.name for column in resolved.manifest.columns}
        if not _REQUIRED_SIGNAL_COLUMNS.issubset(columns):
            raise DashboardUnavailableError(
                "Signal history is missing required columns"
            )
        outcome_select = [
            column if column in columns else f"NULL AS {column}"
            for column in sorted(_SIGNAL_OUTCOME_COLUMNS)
        ]
        normalized_symbol = symbol.upper() if symbol else None
        where_clause = "WHERE symbol_key = $symbol_key" if normalized_symbol else ""
        sql = validate_read_only_sql(
            f"""
            SELECT symbol_key, signal_date, signal, signal_price, score, reason_codes,
                   {", ".join(outcome_select)}, generated_at
            FROM signal_history
            {where_clause}
            ORDER BY CAST(signal_date AS DATE) DESC, generated_at DESC, symbol_key
            """,
            {"signal_history"},
        )
        parameters = (
            {"symbol_key": f"{normalized_exchange}-{normalized_symbol}"}
            if normalized_symbol
            else {}
        )
        payload = await self._executor.execute(
            str(uuid.uuid4()), sql, [resolved], parameters, limit
        )
        return DashboardSnapshot(
            effective_data_date=(
                max(str(row["signal_date"]) for row in payload.rows)
                if payload.rows
                else _manifest_effective_date(resolved.manifest) or ""
            ),
            generated_at=resolved.manifest.generatedAt,
            data_versions={"signals": resolved.manifest.dataVersion},
            rows=payload.rows,
            truncated=payload.truncated,
            available_exchanges=available_exchanges,
            selected_exchange=normalized_exchange,
        )

    async def _list_partitions(self, dataset: str) -> list[Any]:
        page = await self._catalog.list_partitions(
            dataset,
            limit=self._settings.dashboard_max_partitions + 1,
        )
        items = page.get("items")
        if not isinstance(items, list):
            raise DashboardUnavailableError(
                f"Invalid metadata partition page for {dataset}"
            )
        return items


def _manifest_effective_date(manifest: Any) -> str | None:
    value = manifest.maxTimestamp or manifest.minTimestamp
    return value[:10] if value else None
