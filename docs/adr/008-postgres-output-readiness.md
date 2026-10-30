# ADR-008: Platform-owned PostgreSQL Output Readiness via Kafka

## Status

Accepted for the planned P14-I10 target on 2026-10-30; **not implemented**.
Current MinIO JSON readiness remains authoritative until verified cutover.
See [I10 plan and acceptance](../plans/030-static-graph-dispatch-planner.md#p14-i10--output-metadata-registration-and-execution-watchdog).

## Context

Periodic global manifest discovery delays availability after atomic work completes.
Exact work/date coverage is easier to query as indexed operational rows. Direct
worker database writes would violate ADR-004 and couple analytical workers to the
Platform schema. The future writer needs the same registration boundary after
batching output writes, without becoming the owner of Platform database access.

## Decision

Producers write and validate Parquet, then publish a new versioned Kafka metadata
registration event containing bounded arrays. Platform owns the new consumer,
idempotent inbox, transactional metadata upserts and authenticated read API/provider.
Readers and admission switch to PostgreSQL readiness after backfill and shadow
comparison. Preserve schema/statistics, exact coverage dates, versions and lineage.
Keep physical paths outside business messages; derive them from logical references.

The metadata topic is separate from terminal status. Platform durably correlates
both arrival orders; authoritative SUCCESS requires all required registrations or
an explicit valid-empty declaration. Transport acknowledgement alone is not READY.
Events and late statuses cannot reopen a failed attempt or replace a newer pointer.

SYNC_METADATA becomes 12-hour reconciliation after cutover. A separate watchdog
scans every 2 minutes and fails published work lacking terminal completion after
30 minutes, with write ownership still treated as uncertain after timeout.

The future writer emits the same arrays after durable physical batches. Atomic
work, output writeKey, intentId and registration identity remain separate. This
ADR does not authorize P12 implementation or activate concurrency.

## Consequences and compatibility

- This is a scoped future exception to JSON READY-manifest rules in ADR-003 and
  plan governance; analytical Parquet storage and current runtime remain unchanged.
- ADR-004 remains intact: Python services use Kafka/API, never direct Platform DB.
- Failure preserves the last validated READY pointer. No silent mixed DB/manifest
  authority or fallback to stale JSON is permitted after cutover.
- PostgreSQL/Kafka/MinIO are not one transaction. Idempotency, replay, pending
  completion and reconciliation must cover each durability gap.
- DB attempt fencing does not prevent a stale worker from changing mutable MinIO
  bytes. Exact-byte validation plus proven write exclusion/version addressability
  is a cutover gate; timeout or two stable scans cannot establish worker termination.
- Backfill, reader migration, authenticated API, rollout and forward recovery are
  required I10 acceptance work, not evidence supplied by accepting this ADR.

## Reserved identity and query-trace extension

Keep canonical atomicWorkId end to end and atomicAttemptId/executionId per attempt;
persist output-version provenance for all contributing works. Existing workIdentity
is a storage alias, not a new identity. Reserve optional diagnostic traceContext and
future durable queryId-level snapshots of actually read versions linked to producing
work/attempts. Trace/query context never changes work/output/version identity. Future
shared-work/batch spans need multiple links, not a substituted single request root.
This reserves contracts only: no tracing SDK, exporter, query schema or collector is
implemented or required to close I10. See the I10 plan for exact source-name mapping.
