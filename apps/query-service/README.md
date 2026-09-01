# Omni Query Service

Private, read-only server-side query boundary for Omni Console. It resolves
logical dataset references through canonical `READY` manifests and executes
bounded SQL with native DuckDB. Object-storage credentials and physical paths
never cross the HTTP boundary.

## API

The fixed dashboard also exposes `GET /v1/dashboard/ichimoku-signals` with
bounded `exchange` and `limit` parameters. It reads the canonical READY
`signals` partition for `ICHIMOKU_V1`; signal scoring and reason codes are
precomputed by Analyzer and are never reconstructed in Query Service.

`GET /v1/dashboard/signal-history` reads the canonical READY
`TREND_MOMENTUM_V1` daily history partition. Available exchanges are discovered
from matching READY manifests and returned as `availableExchanges`; unrelated
strategies, timeframes, and non-READY partitions are excluded. When exchange is
omitted, the first available exchange in alphabetical order is selected. It also
accepts an optional exact symbol code and a row limit of at most 20. Rows are newest first
and include persisted T+5/T+10/T+15/T+20 returns when evaluation has populated
them; unavailable outcomes remain null. Legacy signal Parquet created before
signals manifest publication returns `503 unavailable` until `SYNC_SIGNALS` is
rerun for `TREND_MOMENTUM_V1`; that upsert preserves history and publishes the
first canonical READY pointer for the exchange partition.

- `GET /v1/datasets`
- `GET /v1/datasets/{dataset}/partitions`
- `POST /v1/queries`
- `GET /v1/queries/{query_id}`
- `GET /v1/queries/{query_id}/result?format=json|arrow`
- `DELETE /v1/queries/{query_id}`
- `GET /v1/dashboard/freshness`
- `GET /v1/dashboard/market-breadth?exchange=HOSE|HNX|UPCOM`
- `GET /v1/dashboard/top-movers?exchange=HOSE|HNX|UPCOM&limit=5|10|20`

Dashboard endpoints are server-owned, bounded reads. EOD market aggregation limits
partition count, total manifest scan bytes, result rows, timeout, exchange values,
and mover count. Top Movers returns independently ranked gainers and losers for
one allowlisted Top X value. Responses expose an effective data date, generation time, source
`dataVersions`, and truncation where applicable. Missing or over-bound sources
return unavailable semantics rather than zero-valued market metrics.

The service must remain private or sit behind identity-aware access. It accepts
only read-only SQL over logical aliases declared in each query request.
`POST /v1/queries` and dashboard endpoints require the trusted upstream identity
header `X-Omni-User`; anonymous or blank identities are rejected instead of being
recorded under a shared fallback actor. The identity-aware proxy must replace,
not append, this header before forwarding traffic to the private service.
