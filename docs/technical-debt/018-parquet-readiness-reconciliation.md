# TD-018 — Reconstruct Dataset Readiness from Parquet and Manifests

Status: **DEFERRED / owner-gated technical debt**. Date: 2026-10-15. Priority: **P2 conditional**, or P1 only if a demonstrated readiness correctness defect requires remediation. This record proposes no running process, migration or activation.

## Motivation and boundary

Historical EOD/indicator/signal Parquet can exist without corresponding current `JobRun` and `JobExecutionHistory` records. Reading MinIO/manifests for every dependency check may eventually be expensive. Provide an **evidence-backed PostgreSQL dataset readiness projection**, optionally seeded via one-time/backfill reconciliation, so future `DependencyGuard` checks *may* query PostgreSQL efficiently.

**Never invent historical successful JobRuns or executions from file existence.** A data artifact's existence is not proof of a job's execution history, processing SUCCESS, dataset completeness, exact version/lineage, or READY. JobRun is a real logical target-run and path; reconstructed dataset availability is a separate concern. `Dataset READY` and `JobRun COMPLETE` remain distinct facts.

## Proposed model (candidate, not authorized schema)

A normalized bounded read projection keyed by **dataset + logical partition + businessDate (where applicable) + immutable dataVersion**, with relevant optional scope (SYMBOL / SECTOR / EXCHANGE / GLOBAL). Potential fields: `status` (`VERIFIED_READY`, `NOT_READY`, `UNKNOWN`, `STALE` / `INVALID`), manifest identity/etag or object generation, recorded version and exact upstream input versions/lineage, producer/writer type where evidenced, evidence provenance, `verifiedAt`, `lastReconciledAt`, verification rule/schema version and explicit completeness/uncertainty. Do not use `jobRunId` as a mandatory key: imported/historical datasets may have no originating JobRun. An optional nullable source `jobRunId` may link **only when persisted evidence actually proves it**.

Use logical dataset/path resolution from existing contracts; do not store arbitrary user-controlled object paths or copy analytical data/entire Parquet payloads into PostgreSQL. Cardinality and indexes require dataset-size measurements.

## Evidence authority and reconciliation

1. Inventory actual dataset writers, immutable version manifests, READY pointers, logical partition/date paths, validation and `DependencyRegistry/JobDependencyGuard` behavior; identify datasets lacking sufficient manifest evidence.
2. Reconciliation must consult **manifest + exact object/version existence and lineage** (and bounded content validation when required by the dataset contract). Bare Parquet object discovery can yield `DISCOVERED/UNVERIFIED`, never READY.
3. Preserve **READY-last** publication: immutable dataset version and manifest must be valid before its READY pointer. An older valid version is not automatically the latest required version. A source manifest change/deletion/invalidation marks or removes the projection's readiness for that exact identity.
4. For historical objects with missing or conflicting manifests/lineage, record `UNKNOWN` / repair required, never fabricate provenance. Allow partial reconciliation, retries and bounded resume with progress/watermarks and failure accounting.
5. Do not equate execution SUCCESS, child RUNNING, outbox PUBLISHED, existence of an object or previous observation with dataset readiness. Valid-empty output requires an explicit dataset policy (see TD-005).
6. A future PostgreSQL-first guard requires **authoritative transactional update/invalidation**, or safe freshness proof and fail-closed fallback to current manifest-based guard. Avoid a dual-writer split-brain between object-store READY and PostgreSQL READY. Define consistency mode, snapshot/lag budget, exact input `dataVersion` pinning, crash gap repair and cutover/rollback before switching reads. A stale row must not authorize a dependency.
7. Reconcile permission boundaries, load/rate limits, retention, foreign input ownership, and missing files. Use bounded batches and idempotent upserts; avoid full-bucket scans on every scheduler tick.

## Small future stories (not scheduled)

- **S1 — Readiness source and parity audit.** Enumerate current manifests/paths and define authoritative READY criteria, including empty/missing/invalid output and provenance.
- **S2 — Bounded PostgreSQL evidence projection.** Add versioned schema/indexes and dual-read diagnostics without replacing current `DependencyGuard`.
- **S3 — Historical reconciliation command.** Resumable dry-run, bounded Parquet/manifest discovery, verification, safe upsert and uncertainty classifications; no fabricated JobRuns.
- **S4 — Controlled read cutover.** Instrument latency/pool/storage calls, parity/invalidation/cross-version tests, safe fallback and operational runbook. Only switch the guard's source when all safety evidence is accepted.

## Acceptance / stop gates

- Proven READY parity for exact dataset/partition/businessDate/dataVersion and input lineage, including symbol-scoped and shared EXCHANGE/GLOBAL artifacts.
- Unit + PostgreSQL integration tests for missing/corrupt data, manifest absent/pointer out of date, concurrent writer and reconciliation, deletion/invalidation, retry, restart, duplicated scans, late publish and empty output.
- Measured query/MinIO call latency and cardinality before introducing indexes or claiming performance improvements; no invented throughput thresholds.
- Stop if any dataset lacks a trustworthy READY publication contract, source of truth cannot be established, or a proposed migration could let stale PostgreSQL evidence bypass existing guard.
- No active Plan 030 dependency; no automatic Plan 028 backfill rollout; no change to Kafka/Parquet writers as part of documenting this TD. Promote only after owner approval and a bounded impact/test plan.

## Related plans

- [Plan 030 — Static Graph & DispatchPlanner](../plans/030-static-graph-dispatch-planner.md): JobRun scheduling and executor state remain independent from dataset readiness evidence; current guard continues authoritative manifest-based checks.
- [Plan 028 — Reusable Date-Range Backfill](../plans/028-reusable-date-range-backfill.md): data completeness and repair classification can consume verified evidence after a separately approved cutover.
- [Plan 029 — Operator Trust Console](../plans/029-operator-trust-console.md): Data Health has its own process-local scanned-result cache and must not be silently redefined as this PostgreSQL projection.
- [TD-005 — Job status empty-output semantics](005-job-status-empty-output-semantics.md): success/valid-empty/data presence are distinct.
