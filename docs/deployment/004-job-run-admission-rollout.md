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
