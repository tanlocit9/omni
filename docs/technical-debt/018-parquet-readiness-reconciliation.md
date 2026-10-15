# TD-018 — Date-Range Readiness Projection and Parquet Reconciliation

Status: **DEFERRED / owner-gated technical debt**. Date: 2026-10-15. Priority: **P2 conditional**, or P1 only if a demonstrated readiness correctness defect requires remediation. This record proposes no running process, migration or activation.

## Motivation and boundary

Historical EOD/indicator/signal Parquet can exist without corresponding current `JobRun` and `JobExecutionHistory` records. Reading MinIO/manifests for every dependency check may eventually be expensive. Provide an **evidence-backed PostgreSQL dataset readiness projection**, optionally seeded via one-time/backfill reconciliation, so future `DependencyGuard` checks *may* query PostgreSQL efficiently.

**Never invent historical successful JobRuns or executions from file existence.** A data artifact's existence is not proof of a job's execution history, processing SUCCESS, dataset completeness, exact version/lineage, or READY. JobRun is a real logical target-run and path; reconstructed dataset availability is a separate concern. `Dataset READY` and `JobRun COMPLETE` remain distinct facts.

## Proposed model (candidate, not authorized schema)

A normalized bounded read projection keyed by **dataset + logical partition + scoped identity + verified business-date coverage (single date or range) + immutable dataVersion**, with relevant optional scope (SYMBOL / SECTOR / EXCHANGE / GLOBAL). Potential fields: `status` (`VERIFIED_READY`, `NOT_READY`, `UNKNOWN`, `STALE` / `INVALID`), manifest identity/etag or object generation, recorded version and exact upstream input versions/lineage, producer/writer type where evidenced, evidence provenance, `verifiedAt`, `lastReconciledAt`, verification rule/schema version and explicit completeness/uncertainty. Do not use `jobRunId` as a mandatory key: imported/historical datasets may have no originating JobRun. An optional nullable source `jobRunId` may link **only when persisted evidence actually proves it**.

Use logical dataset/path resolution from existing contracts; do not store arbitrary user-controlled object paths or copy analytical data/entire Parquet payloads into PostgreSQL. Cardinality and indexes require dataset-size measurements.

## Range-aware READY semantics — owner direction 2026-10-15

**READY is a query verdict, not a global dataset boolean.** A requester (e.g. a JobRun step) asks `isReady(dataset, partition, scope, requiredDateOrRange, versionConstraint, lineageConstraint)`. Its answer must distinguish `READY`, `PARTIAL`, `MISSING`, `STALE`, `INVALID`, `UNKNOWN` (optionally `WAITING` in DependencyGuard policy), with missing dates/partitions and matching immutable `dataVersion`/lineage evidence. A valid single date is the degenerate range `[date,date]`. Querying a longer period must **not** be satisfied by an arbitrary READY pointer for the latest single date.

- Represent **verified coverage** (candidate: bounded inclusive `fromDate/toDate` ranges tied to exact logical dataset/partition/scope + immutable version, provenance and validation policy), and/or verified dated partition sets. `READY_RANGE` is a **coverage assertion** only for dates actually proven complete, not a blind min/max envelope. Avoid one PostgreSQL row per symbol/date if interval compression is safe; do not introduce lossy contiguous ranges over gaps.
- **Calendar semantics:** dates in a range do not imply every calendar day is required. Resolve expected dates from the approved trading calendar, exchange, instrument/listing lifecycle and dataset-specific frequency; version this calendar/policy evidence. A weekend/holiday, pre-listing date, suspended instrument or known provider no-data case cannot be silently treated as either present or missing without explicit rules. For partial history, report exact missing or unverified expected dates.
- **Range algebra:** union of adjacent/intersecting verified intervals may satisfy a request only if scope, dataset contract, provenance, version constraint, completeness policy and lineage are compatible. A gap in expected coverage means `PARTIAL`; absence of evidence is `UNKNOWN`/not ready, never success. Do not combine versions that violate exact-version or same-lineage requirements; explicit multi-version composition is a separate dataset policy decision.
- **Scope mapping:** one SYMBOL JobRun can request SYMBOL coverage for `HOSE:HPG`, while a step depending on EXCHANGE/GLOBAL data must evaluate the wider upstream scope. READY for one symbol never proves READY for the entire exchange. Store per-run-step *required input dataset and resolved effective scope/date range* alongside its JobRun path only when approved; do not duplicate READY authority inside JobRun.
- **Read vs execution:** when an upstream has authoritative READY coverage for the exact requirement, a **future** run-preparation policy may mark that dependency step as `SATISFIED_BY_DATA` (or equivalent external-evidence reference) without creating a fake SUCCESS execution. A newly scheduled upstream actually running remains governed by persisted JobRun completion barriers. **Do not reinterpret READY as execution SUCCESS** or bypass current DependencyGuard. The policy allowing dataset-evidence skip must be explicit per dependency edge/step and separately enabled/tested.
- **Freshness/revocation:** exact readiness includes immutable version and a current (or safely bounded/validated) READY authority. Repartition, overwrite, manifest pointer replacement, delete/corrupt object, new required upstream version or changed calendar invalidates affected coverage. Use fail-closed behavior during reconciliation gaps; don't permit PostgreSQL replicas/lag to authorize stale READY.
- **Example:** requested EOD for `HOSE:HPG` on expected trading dates 2026-10-12 through 2026-10-15. If evidence proves only 2026-10-12 and 2026-10-14, verdict `PARTIAL` with missing/unverified 2026-10-13 and 2026-10-15 (subject to trading calendar/lifecycle). Do not infer readiness from `MIN(date)` and `MAX(date)`.

**Proposed contract sketch (not implementation):**

```text
ReadinessRequirement(
  dataset, partition, scopeType, scopeKey,
  startDate, endDate, calendarPolicy,
  versionConstraint, lineageConstraint
)
ReadinessResult(
  verdict, verifiedCoverage, missingExpectedDates,
  unknownDates, exactVersionEvidence, provenance, verifiedAt
)
```

Keep response/query bounds explicit; detailed missing-date enumerations may be paginated or truncated with `complete=false`, which cannot be interpreted as READY.

## Evidence authority and reconciliation

1. Inventory actual dataset writers, immutable version manifests, READY pointers, logical partition/date paths, validation and `DependencyRegistry/JobDependencyGuard` behavior; identify datasets lacking sufficient manifest evidence.
2. Reconciliation must consult **manifest + exact object/version existence and lineage** (and bounded content validation when required by the dataset contract). Bare Parquet object discovery can yield `DISCOVERED/UNVERIFIED`, never READY.
3. Preserve **READY-last** publication: immutable dataset version and manifest must be valid before its READY pointer. An older valid version is not automatically the latest required version. A source manifest change/deletion/invalidation marks or removes the projection's readiness for that exact identity.
4. For historical objects with missing or conflicting manifests/lineage, record `UNKNOWN` / repair required, never fabricate provenance. Allow partial reconciliation, retries and bounded resume with progress/watermarks and failure accounting.
5. Do not equate execution SUCCESS, child RUNNING, outbox PUBLISHED, existence of an object or previous observation with dataset readiness. Valid-empty output requires an explicit dataset policy (see TD-005).
6. A future PostgreSQL-first guard requires **authoritative transactional update/invalidation**, or safe freshness proof and fail-closed fallback to current manifest-based guard. Avoid a dual-writer split-brain between object-store READY and PostgreSQL READY. Define consistency mode, snapshot/lag budget, exact input `dataVersion` pinning, crash gap repair and cutover/rollback before switching reads. A stale row must not authorize a dependency.
7. Reconcile permission boundaries, load/rate limits, retention, foreign input ownership, and missing files. Use bounded batches and idempotent upserts; avoid full-bucket scans on every scheduler tick.

## Small future stories (not scheduled)

- **S1 — Range-readiness contract and source audit.** Inventory data/manifest evidence, date range coverage, trading-calendar/lifecycle requirements, exact-version/lineage and valid-empty semantics; freeze typed request/verdict API.
- **S2 — Bounded PostgreSQL coverage projection.** Add date-range/dated-partition coverage with typed symbol/exchange/global scope, versioned schema/indexes, gap-aware read queries and dual-read parity diagnostics; no guard cutover.
- **S3 — Historical readiness coverage reconstruction.** Resumable dry-run, bounded Parquet/manifest discovery by scope/date, calendar-aware coverage classification, verified gap-aware upserts and uncertainty reporting; no fabricated JobRuns.
- **S4 — Controlled DependencyGuard read cutover and optional JobRun reuse.** Measure latency/pool/storage calls; test range parity, revocation and cross-version queries, safe fail-closed fallback and operational runbook. Any JobRun upstream data-reuse shortcut is a separately enabled step/edge policy with reference evidence, never a synthetic success.

## Acceptance / stop gates

- Proven gap-aware READY parity for exact dataset/partition/scope/date or date range/dataVersion and input lineage, including symbol-scoped and shared EXCHANGE/GLOBAL artifacts, mixed-version policies, and calendar/lifecycle evidence.
- Unit + PostgreSQL integration tests for range gaps, weekends/holidays, pre-listing/unexpected dates, partial/truncated coverage, overlapping intervals, shared upstream versus symbol scope, mismatched version/lineage, missing/corrupt data, manifest absent/pointer out of date, concurrent writer and reconciliation, deletion/invalidation, retry, restart, duplicated scans, late publish and empty output.
- Measured query/MinIO call latency and cardinality before introducing indexes or claiming performance improvements; no invented throughput thresholds.
- Stop if any dataset lacks a trustworthy READY publication contract, source of truth cannot be established, or a proposed migration could let stale PostgreSQL evidence bypass existing guard.
- No active Plan 030 dependency; no automatic Plan 028 backfill rollout; no change to Kafka/Parquet writers as part of documenting this TD. Promote only after owner approval and a bounded impact/test plan.

## Related plans

- [Plan 030 — Static Graph & DispatchPlanner](../plans/030-static-graph-dispatch-planner.md): JobRun scheduling and executor state remain independent from dataset readiness evidence; current guard continues authoritative manifest-based checks.
- [Plan 028 — Reusable Date-Range Backfill](../plans/028-reusable-date-range-backfill.md): data completeness and repair classification can consume verified evidence after a separately approved cutover.
- [Plan 029 — Operator Trust Console](../plans/029-operator-trust-console.md): Data Health has its own process-local scanned-result cache and must not be silently redefined as this PostgreSQL projection.
- [TD-005 — Job status empty-output semantics](005-job-status-empty-output-semantics.md): success/valid-empty/data presence are distinct.
