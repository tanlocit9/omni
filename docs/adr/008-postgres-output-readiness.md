# ADR-008: Platform-owned PostgreSQL Output Readiness via Kafka

November 11 frozen-proof amendment: owner-approved V26 READY promotion history
allows exact VERSIONED attempt lookup after refresh. Only actual READY promotion
creates this immutable evidence; registration/SUCCESS alone remains insufficient.
Current discovery and mutable CANONICAL boundaries are preserved. See
[scope and evidence](../reference/019-i10-review-20261111.md).

November 11 terminal-replay amendment: [validated terminal VERSIONED completion](../reference/017-i10-versioned-storage.md#terminal-completion-replay-audit-owner-approved-november-11)
keeps the first accepted result. Same-output SUCCESS replay is a no-op; conflicting
SUCCESS or late FAILED output is durably audited before ACK. Malformed/foreign and
legacy records retain strict handling. This exception does not replace READY checks,
activate watchdogs or authorize accepting a different output version.

Owner amendment 2026-11-11: [TD-018](../technical-debt/018-execution-watchdog-observation.md)
defers automatic expiry/watchdog during observation; keep its flag false. Completion
evidence, terminal-state immutability and safe READY/reader cutover remain required.
Historical watchdog timing requirements below are not current I10 completion gates.

Owner amendment (2026-11-10): [one worker completion](../reference/018-i10-worker-completion.md)
carries terminal status and validated output evidence for newly declared work.
Platform commits registration, status and eligible READY together. This removes
the new-command cross-topic join, not the existing backlog/late-writer gates.

Owner-scoped operational exception (2026-11-05): [Indicators sector/date
backfill](../reference/009-indicators-backfill.md) may explicitly enroll and promote
its accepted work while global realtime readiness flags remain off. It retains
registration plus SUCCESS, frozen inputs, private work readers and the exclusive
legacy-writer window. This does not authorize global reader cutover or waive the
coordinated rollout requirements below.

Owner scoped-data amendment (2026-11-03): READY covers exact registered work rows
already present in canonical Parquet, not the whole file. Additive workScope and
row-based versions preserve unrelated days/symbols; new enrollment does not require
whole-file snapshots. Frozen approvals remain provenance, separate from perpetual
freshness. See the current identity handoff for compatibility and cutover gates.

Owner identity revision (2026-11-03): enrolled work READY and direct upstream
approvals use canonical atomicWorkId (type/key/day/parameters), with attempt and
output version kept separate. Partition pointers remain physical/catalog projections;
they cannot substitute for work identity. The I10 migration sequence and the private work lookup
are documented in the [current identity handoff](../reference/007-p14-i10-work-identity.md). I10 remains in_progress;
old source/checkpoint statements below are historical for this revised scope.

Owner amendment (2026-11-02): immutable output byte snapshots are approved inside
existing workers, with configured physical version addresses and canonical
logical dataVersion semantics. No new writer service or durable worker recovery
subsystem is approved; recovery is deferred. Snapshot source is opt-in;
this does not establish READY, activate a reader cutover, or prove storage-level
write exclusion. Durable Platform status/registration correlation remains in scope.

I10 READY integration (2026-11-02): **in_progress; local checks recorded, release gate unresolved**. [Current source, baseline decision and release gates](../reference/006-p14-i10-ready-integration.md). Enrollment/worker registration, PostgreSQL READY/provider/readers, fenced baseline backfill and shadow/reconciliation now have opt-in source. Exact-date admission defers missing inputs; failed attempts remain terminal. Runtime remains manifest-authoritative by default; no flags/deployment or retained migrations were activated. Durable worker recovery, tracing and P12 remain deferred.

I10 checkpoint (2026-11-01): `in_progress`; [shadow registration source and remaining gates](../reference/004-p14-i10-registration-checkpoint.md). Source is unverified, consumer disabled by default, worker handlers not connected. Manifest READY remains authoritative; no watchdog/cutover/tracing activation. Earlier pending statements describe the pre-implementation baseline.

Main-source reconciliation (2026-11-01, `4cc0011`): [commit/source mapping and remaining gates](../reference/003-p14-main-source-reconciliation.md). I10 remains planned/pending; I11 has no confirmed registry/plan scope on this baseline. Existing diagnostics do not implement PostgreSQL readiness, a watchdog or tracing.

## Status

Accepted on 2026-10-30; **partially implemented, verification/cutover pending**.
Current MinIO JSON readiness remains authoritative until verified cutover.
See [I10 plan and acceptance](../plans/030-static-graph-dispatch-planner.md#p14-i10--output-metadata-registration-and-execution-watchdog).

## Context

Periodic global manifest discovery delays availability after atomic work completes.
Exact work/date coverage is easier to query as indexed operational rows. Direct
worker database writes would violate ADR-004 and couple analytical workers to the
Platform schema. The future writer needs the same registration boundary after
batching output writes, without becoming the owner of Platform database access.

## Decision

Owner physical-safety amendment (2026-11-09): approve an opt-in backend-version
coordinate, frozen accepted merge bases and exact-version readers at existing
logical paths. Timeout replacement is allowed only for two versioned contracts;
legacy uncertainty remains fenced. Historical pinning adds no work READY or
producer provenance. See [protocol and rollout](../reference/017-i10-versioned-storage.md).
This records the approved design, not retained-environment cutover evidence.

Owner amendment (2026-11-02): historical baselines may retain NULL producer
provenance when unknown, without synthetic executions/attempts. They require
validated immutable bytes/schema/actual dates/lineage, unchanged generation fencing
and drained/resolved writer ownership. Registered READY pointers always take
precedence. See [source and operational limits](../reference/006-p14-i10-ready-integration.md).

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
