# Omni Query Service

Graceful shutdown waits for an already started terminal DB completion and its
audit to finish; query execution remains cancellable. This prevents cancelling
the audit after polling observes a committed terminal state. It is not durable
crash recovery. See the [I10 review and source/test evidence](../../docs/reference/019-i10-review-20261111.md).

I10 [versioned proofs](../../docs/reference/017-i10-versioned-storage.md) carry an
optional storageVersionId. Shared Parquet readers fetch and validate exact backend
versions, combine approved rows and pass the checked buffer to DuckDB. No mutable
fallback or UI/public discovery identity change is introduced.

Shadow catalog comparison uses the Query Parquet validator to compare registered
rows and input lineage against legacy files while returning manifest discovery.
See [comparison scope](../../docs/reference/017-i10-versioned-storage.md#shadow-comparison-of-registered-rows).

I10 scoped-data revision (2026-11-03): READY covers exact work rows in canonical
Parquet, with row-scoped versions and frozen work-proof input reads. Other days or
symbols in the same file are independent; whole-file snapshots are not required
for new enrollment. Query executes validated READY rows from the checked buffer.
Deploy matched Platform/shared Python adapters; defaults remain manifest/off.
See [current scope and gates](../../docs/reference/007-p14-i10-work-identity.md).

Owner identity revision (2026-11-03): enrolled work READY and direct upstream
approvals use canonical atomicWorkId (type/key/day/parameters), with attempt and
output version kept separate. Partition pointers remain physical/catalog projections;
they cannot substitute for work identity. Consolidated V19 and the private work lookup
are documented in the [current identity handoff](../../docs/reference/007-p14-i10-work-identity.md). I10 remains in_progress;
old source/checkpoint statements below are historical for this revised scope.

Private PostgreSQL catalog reads enforce both item and byte limits, follow actual
returned cursors under one revision, and derive snapshot addresses from configured
logical identities. Mutable/mismatched addresses fail closed before DuckDB binding;
public catalog DTOs retain their existing shape. See the
[current paired contract tests](../../docs/reference/006-p14-i10-ready-integration.md#contract-review--2026-11-03-utc7).

I10 READY integration (2026-11-02): **in_progress; local checks recorded, release gate unresolved**. [Current source, baseline decision and release gates](../../docs/reference/006-p14-i10-ready-integration.md). Enrollment/worker registration, PostgreSQL READY/provider/readers, fenced baseline backfill and shadow/reconciliation now have opt-in source. Exact-date admission defers missing inputs; failed attempts remain terminal. Runtime remains manifest-authoritative by default; no flags/deployment or retained migrations were activated. Durable worker recovery, tracing and P12 remain deferred.

Main-source reconciliation (2026-11-01, `4cc0011`): [commit/source mapping and remaining gates](../../docs/reference/003-p14-main-source-reconciliation.md). I10 remains planned/pending; I11 has no confirmed registry/plan scope on this baseline. Existing diagnostics do not implement PostgreSQL readiness, a watchdog or tracing.

Private, read-only server-side query boundary for Omni Console. It resolves
logical dataset references through canonical `READY` manifests and executes
bounded SQL with native DuckDB. Object-storage credentials and physical paths
never cross the HTTP boundary.

## Planned Phase 13 Data Health role

[Plan 029](../../docs/plans/029-operator-trust-console.md) plans for Query Service to
execute operator-started, bounded, read-only EOD Parquet scans using the existing
logical resolver and DuckDB boundary. Planned checks cover expected-date evidence,
duplicates/conflicts, required fields, OHLCV validity, corruption, and schema mismatch,
with exact dataset/partition/`dataVersion`/file/check/time provenance. Scans do not
repair data, trigger backfill, or mutate manifests/READY.

Owner decision (2026-10-07): planned scan runs/results use a bounded Query Service process-local memory cache, independent of the existing SQLite query queue. Keys cover logical dataset/partition/dataVersion, requested scope/bounds, and rule/scanner/calendar-evidence versions. Enforce authorization, TTL, entry/byte/finding limits, in-flight deduplication and explicit refresh. Cache loss, eviction, expired IDs and service restart require a new manual scan. Initial scan deployment uses one process; no cross-process/replica continuity or durable history is promised. Do not cache incomplete scans as healthy or reuse findings after inspected objects change. See Plan 029 for the full cache contract. Calendar/lifecycle sources still require approval or explicit narrowing; this section describes planned behavior only.

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
`POST /v1/queries` commits a `QUEUED` record to SQLite before returning `202`.
Bounded background workers use leased, token-fenced claims; expired `RUNNING`
claims are recovered after restart. Status, cancellation, and bounded terminal
result payloads therefore survive process restarts. SQLite runs in WAL mode.

Durable query settings use the standard settings environment-name conversion:

- `QUERY_DB_PATH` (default `data/query-service.sqlite3`)
- `QUERY_CLAIM_LEASE_SECONDS` (default `60`)
- `QUERY_POLL_INTERVAL_SECONDS` (default `0.25`)
- `QUERY_MAX_RESULT_BYTES` (default `16777216`)

The service rejects configurations where the claim lease does not exceed the
query timeout, preventing healthy work from being reclaimed while executing.
Results larger than the durable payload limit finish as `FAILED` rather than
creating an unbounded database record. Production deployments must place
`QUERY_DB_PATH` on a persistent volume; the current Compose files do not yet
include Query Service, so that mount must be added when the service is deployed.

`POST /v1/queries` and dashboard endpoints require the trusted upstream identity
header `X-Omni-User`; anonymous or blank identities are rejected instead of being
recorded under a shared fallback actor. The identity-aware proxy must replace,
not append, this header before forwarding traffic to the private service.
