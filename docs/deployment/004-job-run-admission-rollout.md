# JobRun Admission Rollout and Rollback

This runbook covers Plan 030 P14-I4's Platform-local planner/admission path.
It is operational guidance, not evidence that a retained environment was deployed.
See [Plan 030](../plans/030-static-graph-dispatch-planner.md) for executed local
checks and [ADR-007](../adr/007-scheduler-claim-and-outbox-boundary.md) for ownership.

## Configuration boundary

- `app.scheduler.job-run.planning-enabled` defaults to `true`, through
  `APP_SCHEDULER_JOB_RUN_PLANNING_ENABLED`. This selects bounded JobRun-aware
  planning and guarded legacy FIFO; it does not create new runs.
- `app.scheduler.job-run.preparation-enabled` remains `false`. Keep it off until
  the owner selects safe terminal/standalone entries and reviews overlapping
  GLOBAL/shared upstream work. All 32 cron identities remain. Planning completion
  does not authorize narrowing producers or enabling every existing cron as a target.
- Enabling preparation while planning is disabled fails startup. An outbox claim
  lease must exceed the 15-second publish timeout; the existing two-minute default
  is retained. Review custom lease overrides before upgrading.
- No new table/index/migration, Kafka/worker payload, manifest or dataset-path
  change is introduced by I4. The I3 V12/V13 membership schema is required; V14
  manual-audit retirement retains its separate stop-readers/backup boundary.

## Upgrade checks

1. Record the current Platform artifact/configuration and normal queue/worker
   state. Follow the existing database backup and deployment process.
2. Upgrade Platform with planning enabled and preparation still disabled. Existing
   unlinked rows use the legacy lane with identity revalidation; frozen JobRun
   rows require closed membership and all predecessor executions SUCCESS.
3. Observe bounded `PLANNED`, `FALLBACK` or `FAILED` selection modes. A fallback
   is paginated FIFO, but every linked message still undergoes admission and
   DependencyRegistry checks. Database failure dispatches nothing.
4. Confirm WAITING does not consume attempts, BLOCKED remains terminal, and
   retries preserve identity. Before each broker send, the exact live token/owner
   is renewed; superseded/expired claims are not sent. Acknowledgement/retry
   updates remain fenced. Delivery is at-least-once, not exactly-once.
5. Treat PUBLISHED as broker acknowledgement, not worker completion or dataset
   READY. No worker start/completion timestamps or successful parents are inferred
   from planning. Only actual worker evidence closes execution barriers; the
   existing registry separately approves READY and exact data versions.

Advisory run/FIFO cursors are process-local and may reset on restart. Membership,
status and claims remain PostgreSQL-authoritative. Poison payloads are skipped
with bounded diagnostics, not rewritten or treated as successful; repair/replay
and generalized DLQ tooling remain separate work.

## Read-only diagnostics

Use the [v1 SQL/operator pack](../../database/diagnostics/p14/v1/README.md) with an
explicit read-only transaction, bounded run IDs/pages and timeouts. The
[I5 measurement report](../../database/diagnostics/p14/v1/benchmark-results.md) is
synthetic local evidence, not a production latency budget or actual worker rate.
Never run the test benchmark against retained data; its claim EXPLAIN/rollback and
fake status transitions are intentionally confined to disposable PostgreSQL.

The additive [I6 operator read contract](../../database/diagnostics/p14/v1/read-model.md)
uses the same SELECT-only invocation. Begin a fresh transaction per observation;
long-lived repeatable-read snapshots cannot establish current freshness. Consumers
must enforce their declared age/clock policy and retain unknown/unavailable states.
The contract introduces no endpoint, polling service, deployment flag or migration.

## Explicit rollback

Keep preparation disabled first. Prefer retaining guarded planning while work
drains; a planner/snapshot exception already has a barrier-preserving fallback.
Do not disable planning automatically in response to those exceptions.

Setting `APP_SCHEDULER_JOB_RUN_PLANNING_ENABLED=false` is a conscious rollback to
historical guarded FIFO. It removes the JobRun completion barrier; review/quiesce
queued JobRun work and its upstream state before accepting that behavior. A binary
rollback to an older dispatcher has the same concern. Do not delete run/outbox/
execution state, fabricate SUCCESS or edit applied Flyway migrations to roll back.

The retired manual-trigger API remains authenticated HTTP 410 under TD-017.
Disabling the planner does not restore manual triggering or dropped audit data.
Future symbol producers, shared-work coalescing and date-range backfill require
their own supported-scope/rollout decision. I5's local diagnostic/benchmark
artifacts are available above; operator read-model presentation remains I6 and
Plan 029 work. They do not authorize retained-data migration or activation.

### V15 legacy outbox identity cutover (P14-I7)

The new Platform artifact requires V15 before its strict candidate reader runs.
Flyway applies it at startup only after the operator completes these prerequisites.
Local test success does not authorize applying it to a retained database.

1. Inventory every page of the [legacy outbox query](../../database/diagnostics/p14/v1/legacy_outbox_inventory.sql)
   across PENDING/PUBLISHED/BLOCKED and active/expired leases. Reconcile the whole
   population under a stable writer boundary, not merely one zero-alias page.
   Inventory CURRENT_IDENTITY proves the FK matches the payload ID only; V15 also
   validates parent/definition identity. Resolve malformed, missing, ambiguous or
   conflicting identities through a separately reviewed data repair before cutover.
2. Drain claims and stop affected Platform instances, schedulers and status writers.
   Confirm no incompatible old binary or external execution/outbox writer remains.
   Kafka status delivery may wait durably while Platform is stopped; do not reset
   offsets or replay published work. Validate a database backup and restore procedure
   covering schema history, outbox, executions and frozen JobRun membership.
3. Apply the reviewed artifact/migration only with retained-environment authorization.
   V15 takes execution/outbox table locks with a five-second lock timeout. Locks are
   a second line of defense, not a replacement for writer quiescence. The migration
   validates all rows and projected `(execution_id,message_index)` keys before
   changing only alias execution_id values. It preserves payload/key/topic, IDs,
   status, attempts, availability, leases/tokens/owners, audit timestamps, metadata
   and all execution/JobRun relationships. Existing membership guards stay enabled.
4. On error, stop deployment. PostgreSQL/Flyway roll back the V15 transaction,
   including its new guard. Errors name the first affected message/reason or
   projected collision key without logging raw payloads. Do not delete/quarantine
   rows, disable guards, run Flyway repair, rewrite V1–V14 or reset delivery state
   to force progress. Fix the stated precondition under a reviewed recovery action
   and retry the same forward migration after quiescence.
5. Before resuming, reconcile every message against its payload execution ID and
   verify zero remaining aliases, unchanged row/ID counts and preserved delivery/
   lease/history fields against the backup/inventory. The new trigger rejects
   INSERT or payload/FK updates with missing, poison or mismatching execution IDs.
   Ordinary lease/retry/ack/status updates still work. Verify canonical NULL-JobRun
   and JobRun admission, exact approved-input metadata on the work child, fenced
   retry/ack and child status/parent aggregation; do not infer dataset READY.
6. Resume only the reviewed compatible writers. Binary rollback must keep canonical
   child FKs and the V15 guard; validate the selected artifact's reader/writer contract
   first. A full pre-V15 restore requires separate approval, stopped writers and a
   verified compatible artifact. Never restore parent aliases into a running V15
   deployment, fabricate SUCCESS, reset attempts or republish historical messages.

Physical history purging, retained migration execution and preparation activation
remain separate owner-authorized operations. This procedure adds no new runtime flag.
