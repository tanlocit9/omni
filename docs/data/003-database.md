# Database

PostgreSQL stores Platform-owned operational state. It is not the analytical data lake; market and analytical datasets live in MinIO/S3 as Parquet files.

Flyway migrations under [`database/migrations`](../../database/migrations) are the source of truth for active Platform schema. Reference or older schema material may exist under [`database/refs`](../../database/refs), but it should not be treated as the active migration chain.

## Domain Relationship Map

```mermaid
erDiagram
  JOB_DEFINITIONS ||--o{ JOB_EXECUTION_HISTORIES : creates
  JOB_DEFINITIONS ||--o{ JOB_RUNS : targets
  JOB_RUNS ||--|{ JOB_RUN_STEPS : freezes
  JOB_RUN_STEPS ||--o{ JOB_RUN_STEP_PREDECESSORS : dependent
  JOB_RUN_STEPS ||--o{ JOB_RUN_STEP_PREDECESSORS : upstream
  JOB_RUN_STEPS |o--o{ JOB_EXECUTION_HISTORIES : optional_run_link
  JOB_RUN_STEPS ||--o| JOB_RUN_STEP_PREPARATIONS : closes
  JOB_EXECUTION_HISTORIES ||--o| JOB_RUN_STEP_PREPARATIONS : root_membership
  JOB_EXECUTION_HISTORIES ||--o{ JOB_EXECUTION_HISTORIES : parent_child
  SECTORS ||--o{ SYMBOLS : classifies

  JOB_DEFINITIONS {
    uuid id
    string job_type
    string schedule
    json config
    boolean enabled
  }

  JOB_EXECUTION_HISTORIES {
    uuid id
    uuid job_definition_id
    uuid parent_execution_id
    string status
    timestamptz started_at
    timestamptz completed_at
  }

  JOB_RUNS {
    uuid id
    uuid target_definition_id
    string mode
    string state
    string scope_type
    string scope_key
    date business_date
    string path_fingerprint
  }

  JOB_RUN_STEPS {
    uuid job_run_id
    int step_index
    uuid job_definition_id
    string scope_type
    string scope_key
    date business_date
  }

  JOB_RUN_STEP_PREDECESSORS {
    uuid job_run_id
    int step_index
    int predecessor_index
  }

  JOB_RUN_STEP_PREPARATIONS {
    uuid job_run_id
    int step_index
    uuid parent_execution_id
    int child_count
    int message_count
    timestamptz closed_at
  }

  SYMBOLS {
    uuid id
    string exchange
    string code
    string symbol_key
    uuid sector_id
  }

  SECTORS {
    uuid id
    string code
    string name
    integer level
  }
```

## Important Domains

The diagram describes the schema after V14. Historical `manual_job_triggers`
belongs to V8 through V13 only; see [deferred manual job triggers](#deferred-manual-job-triggers)
for its removal and rollback boundary.

### JobRun dispatch snapshot and admission (I4)

The integrated coordinator reads bounded explicit run IDs using a read-only
REPEATABLE READ snapshot, with separate execution/outbox aggregates to prevent
overcounting. The admission store reads exact run/step/definition/scope/date
candidates, locks run and transitive roots/children before the message using
SKIP LOCKED, and rereads closed membership on the same transaction connection.
It preserves the existing fenced claim; publish renewal requires a still-live
owner/token. Fallback is paginated and retains the same linked-run barrier.
No schema/index/trigger/status change is introduced. Actual PostgreSQL tests and
bounded source-query EXPLAIN pass locally; SQL has no invented numeric coverage
and this is not production-scale benchmark evidence. See
[I4 completion](../plans/030-static-graph-dispatch-planner.md#i4c-integrated-admission-and-completion--2026-10-24).

### JobRun persistence foundation (I3.b)

[`V12__create_job_runs_and_frozen_steps.sql`](../../database/migrations/V12__create_job_runs_and_frozen_steps.sql)
adds `job_runs` request/lifecycle identity, frozen `job_run_steps` and their
`job_run_step_predecessors`. The snapshot is an immutable instance of the static
path, including effective scope and business date for every step. It is not a
mutable topology definition. `JobRunRepository` persists and reloads these instances;
its I3.c preparation caller is gated off by default.

Creation atomically moves transaction-local PREPARING to PREPARED; incomplete
transactions cannot commit. Request identity and sealed membership cannot be
rewritten/deleted with ordinary DML. Lifecycle/failure reason are separate mutable
fields. Referenced job definitions are protected from deletion; history retention
and purge are not implemented. Idempotency is unique per mode/actor/key; changed
metadata/path fails rather than reshaping an existing run. BACKFILL is reserved.

Execution history adds nullable `job_run_id` and `job_run_step_index`. Existing
rows/writers retain NULL links. Linked execution definitions and parent/child run
ownership are enforced by composite foreign keys. Execution and parent IDs retain
their current meanings. I3.b introduced no legacy history rewrite or manual-table drop; I3.d V14 separately removes deferred manual audit below.

Apply V12 before running the updated history entity mapping; allow a normal DDL
lock window for adding indexes/foreign keys. Roll back application code while
retaining the additive schema. Do not modify applied migrations or delete run
history as a rollback shortcut. No production deployment is claimed. Source/tests
are present; current verification evidence belongs to
[Plan 030](../plans/030-static-graph-dispatch-planner.md#i3b-persistence-foundation--2026-10-18).

I3.c adds [V13](../../database/migrations/V13__seal_job_run_execution_membership.sql):
`job_run_step_preparations` records each closed root/child/message membership.
Versioned root/work uniqueness, count validation and deferred closure checks protect
atomic preparation. Closed execution/outbox membership cannot be appended, deleted
or rebound; status/retry/claim progress stays mutable. Legacy NULL links and optional
V12 links are retained. Apply V13 before enabling any preparation caller; retain
the additive schema on rollback. The flag remains false until completion barriers
are proved in I4. I3.c did not remove manual audit; I3.d V14 does so separately. See
[I3.c evidence](../plans/030-static-graph-dispatch-planner.md#i3c-bounded-preparation--2026-10-18).

The execution guard validates versioned parent run/step membership before allowing
NULL-linked legacy rows: omitting a child's JobRun link cannot append or rebind it
under a prepared parent. Independent legacy parents remain compatible.

I3.c lock-safety repair source now bypasses run locks on progress-only UPDATEs in
both execution and outbox guards, using complete NULL-safe identity comparison.
Structural/mixed-identity changes retain membership checks and sorted run locks.
[The repair plan](../plans/030-static-graph-dispatch-planner.md#i3c-lock-safety-repair-plan--2026-10-19)
records an actual old-code deadlock reproduction and the repaired five-check local
PASS on 2026-10-20, including 28 focused PostgreSQL tests. SQL coverage is an
executed criterion matrix, not a numeric JaCoCo claim. Keep preparation disabled;
no retained-environment migration was run.

### Job definitions

| Field          | Value                                                                                                                                                                                      |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Migration      | [`database/migrations/V1__create_job_definitions_table.sql`](../../database/migrations/V1__create_job_definitions_table.sql)                                                               |
| Owner          | Platform scheduler module                                                                                                                                                                  |
| Purpose        | Stores configured jobs, schedules, and job-specific config.                                                                                                                                |
| Related source | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/JobDefinition.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/JobDefinition.java) |
| Related flow   | [Job execution](../flows/001-job-execution.md)                                                                                                                                             |

### Job execution history

| Field          | Value                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Migrations     | [`V2__create_job_execution_histories_table.sql`](../../database/migrations/V2__create_job_execution_histories_table.sql), [`V9__backfill_execution_work_identity.sql`](../../database/migrations/V9__backfill_execution_work_identity.sql), [`V11__dependency_aware_scheduler_outbox.sql`](../../database/migrations/V11__dependency_aware_scheduler_outbox.sql), [`V12__create_job_runs_and_frozen_steps.sql`](../../database/migrations/V12__create_job_runs_and_frozen_steps.sql), [`V13__seal_job_run_execution_membership.sql`](../../database/migrations/V13__seal_job_run_execution_membership.sql) |
| Owner          | Platform scheduler module                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Purpose        | Tracks parent and child executions, worker status, metrics, and errors.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| Related source | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/JobExecutionHistory.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/JobExecutionHistory.java)                                                                                                                                                                                                                                                                                                                                                                                                     |
| Related flow   | [Job execution](../flows/001-job-execution.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |

Child rows store canonical execution identity in `meta_json.workType` and
`meta_json.workKey`. V9 is a schema-only cutover: it never deletes or rewrites
operational records, refuses to run while pending scheduler outbox work exists,
and requires operators to clear all execution history manually before deployment.
After those preconditions pass, it replaces symbol-key indexes with canonical
work-identity/offset indexes. Domain payloads may still contain `symbolKey`; it is
not used for execution lookup. V11 adds terminal `BLOCKED` execution semantics for
terminal dependency decisions. `BLOCKED` is not a worker failure and must not be
rewritten to `FAILED`.

Phase 13 P13-I1 plans additive authoritative stage evidence so dispatch preparation,
dependency wait, outbox publication, worker wait, processing start, terminal-status
publication, and Platform application can be measured separately. Current child
`RUNNING`/`started_at` values may originate during dispatch preparation and therefore
must not be treated as proof of active processing. The future migration must preserve
legacy rows as explicitly unknown where evidence is absent; this document does not
claim that migration exists.

### Scheduler outbox

| Field      | Value                                                                                                                                                                                                                                                                                                                                      |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Migrations | [`V6__create_scheduler_outbox.sql`](../../database/migrations/V6__create_scheduler_outbox.sql), [`V11__dependency_aware_scheduler_outbox.sql`](../../database/migrations/V11__dependency_aware_scheduler_outbox.sql), [`V13__seal_job_run_execution_membership.sql`](../../database/migrations/V13__seal_job_run_execution_membership.sql) |
| Owner      | Platform scheduler module                                                                                                                                                                                                                                                                                                                  |
| Purpose    | Commits immutable execution/message intent before dependency evaluation, then supports fenced READY claims, retryable WAITING, and terminal BLOCKED disposition.                                                                                                                                                                           |

`PENDING` rows are over-fetched as unclaimed candidates. Manifest and exact
run/work eligibility is evaluated without holding a database transaction. Only a
still-PENDING row can then be atomically claimed; this transition increments the
delivery attempt and installs a lease/fencing token. WAITING updates bounded
`dependency_reason` and `available_at` without incrementing attempts. BLOCKED is
terminal, clears claim fields, transitions the execution to terminal `BLOCKED`,
and is excluded from all pending candidate and claim predicates. Existing
PENDING/PUBLISHED rows and audit history are preserved by the additive migration.
Phase 13 P13-I2 plans read-only aggregate visibility over publishable, dependency-waiting,
claimed, and future-retry backlog, including recent publish rate and a guarded drain
estimate for the eligible snapshot. That estimate does not mutate outbox state and does
not predict worker completion.

The reproducible disposable-database harness is
[`database/tests/p1_i4_work_identity_migration.sql`](../../database/tests/p1_i4_work_identity_migration.sql).
It runs V9 twice against empty execution history, verifies both canonical indexes,
and uses PostgreSQL `dblink` to prove that remaining execution history or pending
outbox work aborts the migration. This evidence does not target or modify
production.

### Notification outbox

| Field          | Value                                                                                                                                                                                                                      |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Migration      | [`database/migrations/V10__create_notification_outbox.sql`](../../database/migrations/V10__create_notification_outbox.sql)                                                                                                 |
| Owner          | Platform notifications module                                                                                                                                                                                              |
| Purpose        | Durable, idempotent provider-delivery state with leased claims, fencing, bounded retries, and visible `SENT`/`DEAD` outcomes.                                                                                              |
| Related source | [`apps/core/src/main/java/com/omni/platform/modules/notifications/entities/NotificationOutboxMessage.java`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/entities/NotificationOutboxMessage.java) |
| Related plan   | [Notification outbox](../plans/022-notification-outbox.md)                                                                                                                                                                 |

The payload is a versioned typed notification request. Provider credentials, API
credentials, and concrete chat identifiers are resolved from configuration only at
dispatch and are never stored in either notification table. The separate
`notification_provider_rate_limits` row serializes provider permits across Platform
instances. `DEAD` rows are visible terminal records and are not replayed automatically.

### Symbols

### Deferred manual job triggers

[V8](../../database/migrations/V8__create_manual_job_triggers.sql) historically
created `manual_job_triggers`. Owner decision in I3.d defers manual job triggering
and request audit under [TD-017](../technical-debt/017-manual-request-rejection-audit.md).
[V14](../../database/migrations/V14__drop_deferred_manual_job_triggers.sql) drops only
that table, without CASCADE or historical-data migration. Runtime entity/repository
and manual-trigger allow-list are removed; catalog/execution reads stay available.
Stop old application readers before applying V14 and back up audit history if
needed: application rollback alone cannot restore it. No retained-environment
migration is claimed. JobRun V12/V13 and execution/outbox history are unchanged.

### Symbols

| Field          | Value                                                                                                                                                                        |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Migration      | [`database/migrations/V3__create_symbols_table.sql`](../../database/migrations/V3__create_symbols_table.sql)                                                                 |
| Owner          | Platform scheduler/domain projection                                                                                                                                         |
| Purpose        | Stores Platform query/projection state for tradable symbols.                                                                                                                 |
| Related source | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/Symbol.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/Symbol.java) |
| Upsert topic   | [`topic-upsert-symbols`](001-kafka-contracts.md#topic-upsert-symbols)                                                                                                        |

### Sectors

| Field          | Value                                                                                                                                                                        |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Migration      | [`database/migrations/V4__create_sectors_table.sql`](../../database/migrations/V4__create_sectors_table.sql)                                                                 |
| Owner          | Platform scheduler/domain projection                                                                                                                                         |
| Purpose        | Stores sector classification state used by symbol metadata and sector-wave jobs.                                                                                             |
| Related source | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/Sector.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/Sector.java) |
| Upsert topic   | [`topic-upsert-sectors`](001-kafka-contracts.md#topic-upsert-sectors)                                                                                                        |

## Planned Data Health memory cache

Owner decision (2026-10-07): Query Service owns a bounded process-local cache of manual EOD scan runs/results and provenance. Data Health adds no PostgreSQL table, SQLite store, Redis, disk persistence, or analytical EOD copy. Exact dataset/partition/dataVersion/scope/rule/evidence keys, TTL, entry/byte/finding limits, in-flight deduplication, explicit refresh, and restart behavior are defined in [Plan 029](../plans/029-operator-trust-console.md#data-health-in-memory-cache).

Cache loss or restart requires another manual scan; it is not durable audit history and is not shared across processes or replicas. Platform operational-stage persistence remains separate. Calendar/lifecycle evidence still requires approval or explicit narrowing of classifications.

## Boundary Rules

- Platform owns migrations and PostgreSQL state.
- Ingestor and Analyzer should communicate operational results through Kafka, not direct writes to Platform tables.
- Analytical datasets belong in the Parquet data lake, not in Platform transactional tables.
- Schema changes require migrations, Platform code updates, tests, and documentation updates when domain meaning changes.

## Adding a Migration

1. Inspect the highest active version in [`database/migrations`](../../database/migrations).
2. Add the next file using `V<N>__<description>.sql`.
3. Keep changes domain-focused and reversible by future migrations.
4. Update Platform entities/repositories/services as needed.
5. Update this document if the migration adds or changes an important domain.
6. Verify through the relevant Nx target, usually Platform build/tests.
7. For data-changing migrations, add a disposable-database harness when no Nx
   database target exists; record that exception and never substitute production
   execution for migration tests.
