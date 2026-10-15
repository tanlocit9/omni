# TD-017 — Scheduler Snapshot Cache & Recovery (Deferred Epic)

Status: **DEFERRED EPIC / NOT IMPLEMENTED**. Owner scope decision: 2026-10-15.

## Why this exists

Plan 030 P14-I2 is **PostgreSQL-first**. Persisted execution, parent/child membership, job-definition and outbox evidence remain authoritative; every dispatch tick uses short-lived bounded read-only projections and a tick-local immutable snapshot. Static topology remains in memory. There is no long-lived runtime parent-binding cache and **no startup binding reconstruction feature** in current scope.

This epic groups *conditional future enhancements* to query/cache performance, resilient cached bindings, and startup reconstruction. It is **not a current correctness prerequisite or dependency** for P14-I2/P14-I3. Existing correctness, cohort identity, fail-closed admission and retention requirements belong to [Plan 030](../plans/030-static-graph-dispatch-planner.md).

## Activation trigger / owner gate

Do **not** implement based only on anticipated future scale. Open a bounded story only if at least one measured, documented condition holds:

- representative `EXPLAIN (ANALYZE, BUFFERS)` plus load evidence shows database snapshot scans/latency, Hikari pool wait or query cost cannot meet an approved operational target with reasonable indexing and bounded-query improvements;
- reliable binding reconstruction is impossible from persisted records and the owner explicitly approves a durable identity/persistence contract (an in-memory cache alone cannot fix absent persisted identity);
- multi-instance deployment, retention/cleanup, or startup cost creates a demonstrated requirement for a shared or durable projection.

Record baseline dataset sizes, p50/p95/p99 latency, rows scanned/returned, buffer reads, query round trips, connection pool active/pending/wait, snapshot correctness and peak dispatch cadence. Compare simpler options (index/query improvements, bounded reads, per-tick projection) before introducing cache state.

## Future story candidates — NOT SCHEDULED

1. **TD-017-S1 — Cache policy and contract.** Define which immutable derived facts may be cached, their authoritative persisted inputs, expiration, version/invalidation semantics, maximum footprint, observability, and fail-closed behavior. Separate static dependency declarations from mutable execution progress; never cache dataset READY or exact input lineage as admission authority.
2. **TD-017-S2 — Transaction-safe publication.** If bindings are produced in memory, expose them only after enqueue commit; reconcile committed-but-unpublished gaps, ignore rolled-back writes and avoid ghost bindings. Use atomic immutable replacement and serialize/version-check concurrent updates. Keep all active cohorts, not only the latest batch.
3. **TD-017-S3 — Deterministic restart reconstruction.** If a binding cache is adopted, rebuild from retained outbox **and** authoritative execution/parent/definition/cohort evidence. PENDING-only discovery is insufficient: include PUBLISHED-but-running and terminal upstream proof required by downstream work. Parent ID denotes fan-out membership, not a cross-step dependency. Validate group/date/run correlation and membership closure; missing/ambiguous mapping stays UNKNOWN and blocks only affected dependent paths.
4. **TD-017-S4 — Retention, cutover and verification.** Define evidence-retention closure before cleanup; bound rebuild query time and data size. Gate planner-driven admission for affected scopes until reconstruction is complete, preserve claim/guard/fencing, and define explicit safe rollback. Verify crash between commit/after-commit update, restart, concurrent updates, older active cohorts, failed/missing/late children, retry/duplicate/out-of-order events, cross-group/date/run isolation, and the 75/25/wait/advance trace.

These are candidate *subtasks of one technical-debt epic*, not new active roadmap increments. Promote individually only after owner approval; no automatic schema, index, outbox status, Kafka/protobuf or deployment change is authorized.

## Boundaries and ownership

- **Plan 030 P14-I2:** correctness of persisted cohort identity, one-time atomic fan-out membership, bounded execution-centered read projections, SQL diagnostics, performance baseline and metric contract.
- **Plan 030 P14-I3:** admission-time revalidation, guarded claim/publish, rollback and runtime safety.
- **TD-012:** static topology and optional generic immutable dependency-declaration extraction. Do not create a second graph authority.
- **TD-017:** optional **long-lived derived cache and recovery lifecycle**, only after evidence-triggered activation.
- **Plan 029:** consumers of metrics and diagnostic projections, not cache ownership.

No performance benefit or production failure is currently claimed. Do not mistake documenting possible cache failure modes for a reason to implement them.
