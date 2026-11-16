# ADR-008: Platform-owned PostgreSQL Output Readiness via Kafka

## Status

Accepted on 2026-10-30, amended through 2026-11-11. P14-I10 is in progress.
Platform PostgreSQL readiness is implemented for scoped enrollment. MinIO JSON
manifests remain the default discovery authority until coordinated cutover. See
[the increment registry](../plans/roadmap/implementation-increments.md#increment-registry)
and [retained-environment acceptance gates](../reference/019-i10-review-20261111.md).

## Context

Periodic global manifest discovery delays availability after atomic work completes.
Exact work/date coverage is easier to query as indexed operational rows. Direct
worker database writes would violate ADR-004 and couple analytical workers to the
Platform schema. The future writer needs the same registration boundary after
batching output writes, without becoming the owner of Platform database access.

## Decision

### Completion and ownership

Workers write and validate Parquet. For newly enrolled commands declared with
`WORKER_COMPLETION_V1`, a single terminal `WorkerCompletionEvent` on the existing
metadata-registration topic carries terminal status. SUCCESS includes one
validated registration, including the approved reason for a valid-empty result.
ERROR carries no registration. RUNNING remains a separate receipt on the status
topic. Platform validates the completion against
the persisted attempt and commits registration, terminal status and eligible
READY promotion together. Platform owns the inbox, persistence and authenticated
readiness API. Kafka carries logical dataset references and version evidence.
Services derive object paths from shared configuration. See the
[completion protocol](../reference/018-i10-worker-completion.md).

Queued legacy commands keep separate registration and status streams. Platform
durably correlates both arrival orders for legacy enrolled attempts. Broker ACK
confirms delivery to Kafka. READY requires a qualifying Platform promotion.

### Work identity and physical safety

READY covers the exact registered work rows and actual dates. Use canonical
`atomicWorkId` (type, key, day and parameters) for work readiness and keep
`atomicAttemptId` and output version distinct. Freeze direct upstream attempts
and matching versions at admission. Partition pointers support physical/catalog
discovery. New enrollment can use scoped row evidence without a whole-file
snapshot. Preserve schema, statistics, exact coverage and lineage. See the
[work identity contract](../reference/007-p14-i10-work-identity.md).

Opt-in VERSIONED storage uses immutable backend version coordinates, frozen
accepted merge bases and exact-version reads at existing logical paths. Timeout
replacement requires both attempts to use VERSIONED contracts. Uncertain legacy
writers remain fenced. Historical baselines may keep NULL producer provenance
when unknown and require validated bytes, schema, dates and lineage under a
stable fence with writers drained and ownership resolved. Registered READY proofs
take precedence. A physical pin alone creates no work READY or producer
provenance. See the [storage and rollout protocol](../reference/017-i10-versioned-storage.md).

Actual VERSIONED READY promotions retain immutable proof for exact-attempt lookup
after refresh. Current discovery may advance independently while frozen reads keep
their approved attempt. Mutable CANONICAL evidence keeps its existing boundary.
On a validated terminal VERSIONED replay, identical logical SUCCESS is a no-op.
Conflicting SUCCESS or late completion against FAILED is audited durably before
ACK. Malformed or foreign events and legacy records keep strict handling. The
first accepted registration, terminal status and READY stay intact. See the
[V26 replay and frozen-proof review](../reference/019-i10-review-20261111.md).

### Scoped enrollment and cutover

The default-off bounded backfill endpoint supports
[Indicators](../reference/009-indicators-backfill.md) and
[eligible Signals definitions](../reference/016-i10-signals-backfill-closure.md).
Accepted work can use scoped Platform readiness and private readers while global
realtime flags are off. Apply the matching worker/reader rollout and writer
safety rules, including an exclusive drained window for affected legacy writers.

Global admission and readers move to PostgreSQL after validated backfill,
shadow comparison and a coordinated cutover. Preserve the last validated READY
pointer on failure. After cutover, retain a single readiness authority and use
`SYNC_METADATA` for 12-hour reconciliation under controlled writer ownership.

Automatic execution expiry is deferred under
[TD-018](../technical-debt/018-execution-watchdog-observation.md). Keep
`APP_OUTPUT_REGISTRATION_WATCHDOG_ENABLED=false` during observation. Completion
evidence, terminal-state immutability and safe rollout remain I10 acceptance
criteria. The original two-minute scan and 30-minute deadline are historical scope.

## Consequences and compatibility

- ADR-003 still governs Parquet storage and default manifest publication.
  ADR-008 defines scoped PostgreSQL readiness and its cutover conditions.
- ADR-004 remains intact. Python services use Kafka and authenticated APIs for
  Platform data access.
- Kafka, PostgreSQL and MinIO have separate durability boundaries. Replay,
  idempotency and reconciliation cover gaps between broker ACK, completion commit
  and object writes. DB attempt fencing alone cannot protect mutable object bytes.
- The paired rollout must verify exact-byte safety, writer ownership, affected
  reader parity and forward recovery. Use the [deployment runbook](../deployment/004-job-run-admission-rollout.md).
- The future P12 writer may use the same registration boundary after durable
  physical batches. Work identity, writeKey, intentId and registration identity
  remain distinct. P12 and durable worker recovery remain deferred.

## Decision history

Earlier checkpoints record the [4cc0011 baseline](../reference/003-p14-main-source-reconciliation.md),
[registration](../reference/004-p14-i10-registration-checkpoint.md),
[READY integration](../reference/006-p14-i10-ready-integration.md),
[versioned storage](../reference/017-i10-versioned-storage.md) and
[unified completion](../reference/018-i10-worker-completion.md).
The current decision above incorporates the November 11 amendments.

## Reserved identity and query-trace extension

Keep canonical atomicWorkId end to end and atomicAttemptId/executionId per attempt.
persist output-version provenance for all contributing works. Existing workIdentity
is a storage alias, not a new identity. Reserve optional diagnostic traceContext and
future durable queryId-level snapshots of actually read versions linked to producing
work/attempts. Trace/query context never changes work/output/version identity. Future
shared-work/batch spans need multiple links, not a substituted single request root.
This reserves contracts only: no tracing SDK, exporter, query schema or collector is
implemented or required to close I10. See the I10 plan for exact source-name mapping.
