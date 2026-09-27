# Phase 4 — Job Dependency Guard

## Goal

Use manifests to decide whether analytical work is dispatchable, while keeping blocked work distinct from failed execution.

## Increment P4-I1 — Dependency policies and shadow guard

| Field                   | Value                                     |
| ----------------------- | ----------------------------------------- |
| id                      | P4-I1                                     |
| title                   | Dependency policies and shadow guard      |
| status                  | completed                                 |
| priority                | critical                                  |
| depends_on              | [P1-I2]                                   |
| blocks                  | [P4-I2, P5-I2, P6-I1]                     |
| owned_modules           | [apps/core, configs, docs/data]           |
| execution_mode          | autonomous                                |
| requires_owner_decision | false                                     |
| pr                      | https://github.com/tanlocit9/omni/pull/14 |
| last_verified_commit    | 0d09cbe14719f83d2536573575795171eca6a168  |

Goal: introduce typed dependency policies and a shadow-mode guard that reports what would block without stopping dispatch.

In scope: `EXISTS`, `READY`, `PARTITION_MATCH`, `MIN_ROW_COUNT`, `SUPPORTED_SCHEMA_VERSION`, `MAX_FRESHNESS_LAG`, `CURRENT_INPUTS`, structured reason codes, metrics, and startup validation.

Out of scope: enforcing dispatch blocking.

Acceptance criteria: policy tests cover ready/missing/stale/incompatible/current-input states, shadow guard produces observable reasons, and no worker execution behavior changes.

Required tests/checks: unit tests for every policy, manifest resolver integration tests, config validation, and Core Nx checks.

Stop conditions: stop if dependency metadata semantics conflict with existing job definitions.

## Increment P4-I2 — Scheduler enforcement for the first analytical job

| Field                   | Value                                              |
| ----------------------- | -------------------------------------------------- |
| id                      | P4-I2                                              |
| title                   | Scheduler enforcement for the first analytical job |
| status                  | completed                                          |
| priority                | critical                                           |
| depends_on              | [P4-I1]                                            |
| blocks                  | [P5-I2, P6-I1]                                     |
| owned_modules           | [apps/core, apps/analyzer, configs]                |
| execution_mode          | autonomous                                         |
| requires_owner_decision | false                                              |
| pr                      | https://github.com/tanlocit9/omni/pull/14          |
| last_verified_commit    | 0d09cbe14719f83d2536573575795171eca6a168           |

Goal: enforce dependency guard for one analytical job after shadow-mode evidence is acceptable.

Verification state: `feature/phase-7@0d09cbe14719f83d2536573575795171eca6a168` wires an ENFORCED per-symbol EOD
dependency context into `SYNC_INDICATORS`, releases claims while blocked,
persists one bounded-backoff blocked record without execution/outbox spam, and
attaches approved manifest versions before dispatch. Unit coverage and a real
PostgreSQL/Testcontainers BLOCKED-to-dispatch test are present. Completion is
passed the complete Platform build/test suite in CI run #149, including the
real PostgreSQL/Testcontainers BLOCKED-to-dispatch path.

Acceptance criteria: missing/stale data produces blocked/deferred scheduler state, no execution-history spam is created for blocked polls, exact approved input versions attach to execution, and dependency backoff is bounded and observable.

Required tests/checks: scheduler race tests around guard-ready then claim, blocked-state/backoff tests, metrics/log assertions, and affected Core/Analyzer checks.

Stop conditions: stop if enforcement target lacks READY manifests or shadow data contradicts documented policy.

## Increment P4-I3 — Dependency-aware outbox dispatch and terminal blocking

| Field                   | Value                                                  |
| ----------------------- | ------------------------------------------------------ |
| id                      | P4-I3                                                  |
| title                   | Dependency-aware outbox dispatch and terminal blocking |
| status                  | verification_pending                                   |
| priority                | critical                                               |
| depends_on              | [P1-I4, P4-I2, P7-I2]                                  |
| blocks                  | [P11-I1]                                               |
| owned_modules           | [apps/core, database, docs/flows, docs/data]           |
| execution_mode          | autonomous                                             |
| requires_owner_decision | false                                                  |
| pr                      | null                                                   |
| last_verified_commit    | null                                                   |

### Goal

Move dependency eligibility from pre-enqueue scheduler and manual-trigger checks to
one scheduler-outbox dispatch boundary. Due scheduled work and accepted manual work
must commit execution and PENDING outbox identities before dependency evaluation, so
restart catch-up is serialized by exact run and work identity instead of cron timing.

### Current verified baseline

P4-I3 source is present and locally verified. Scheduled and accepted manual work now
commit stable execution and PENDING-outbox identities before dependency evaluation.
The scheduler outbox dispatcher evaluates READY/WAITING/BLOCKED decisions, preserves
approved input lineage before eligible claim, and uses fenced publication/retry
transitions. The increment remains `verification_pending` because increment-owned
commit/PR, exact-head CI, and remaining completion evidence are not recorded.

### Dependencies and eligibility conditions

- P1-I4 supplies required `workType`/`workKey` execution identity.
- P4-I2 supplies the enforced manifest dependency semantics to migrate without
  weakening READY or exact `dataVersion` checks.
- P7-I2 supplies the audited manual-trigger and execution-status boundary.
- All declared dependencies are completed. The owner explicitly requested P4-I3
  implementation on 2026-09-26 despite the prior serial-priority ownership note.
  Source is now present and the truthful status is `verification_pending`; this does
  not complete or promote P8-I5, P9-I1, P9-I4, or any downstream increment.

### In scope

- Keep dependency evaluation in the scheduler-owned dependency package, with one
  registry boundary used by scheduler-outbox dispatch. Do not create a facade-only
  application module that imports its implementation back from Scheduler.
- Make both scheduled and manual entry points create execution plus PENDING outbox
  work before dependency evaluation.
- Use manifests as the hard data-readiness source. Use execution/outbox state only
  for exact same-run/work matching and the global metadata barrier.
- Return `READY`, `WAITING`, or terminal `BLOCKED` from dependency evaluation.
- Keep `WAITING` rows PENDING without incrementing delivery attempts and over-fetch
  candidates so blocked rows cannot starve unrelated ready work.
- Add terminal `BLOCKED` execution semantics with a structured dependency reason and
  an additive database migration. Terminally blocked outbox rows must never be
  reclaimed or published.
- Preserve database claim fencing and duplicate-publication safety when eligibility
  changes between evaluation and atomic claim.
- Remove the scheduled blocked-job retry path after equivalent outbox visibility and
  retry behavior are established; migrate historical semantics without silently
  dropping operational evidence.

### Out of scope

- Dynamic dependency administration or persistence of dependency definitions.
- Kafka/protobuf, object-storage manifest, storage-path, or dataset-ownership changes.
- Provider capacity scheduling, a general DAG orchestrator, or dependency bypass.

### Expected implementation approach

1. Introduce the registry request/decision boundary in the scheduler dependency package
   and adapt the existing manifest guard without a facade-only module.
2. Migrate existing scheduler definitions without changing their hard manifest
   meaning, exact `dataVersion` lineage, or logical dataset references.
3. Change scheduled and manual preparation to commit execution and outbox records
   without a pre-enqueue dependency block.
4. Over-fetch unclaimed PENDING rows, evaluate outside Kafka I/O, then atomically
   claim only a still-PENDING eligible row using the existing lease/fencing boundary.
5. Persist waiting diagnostics without consuming delivery attempts. On terminal
   upstream failure, atomically mark the outbox terminal and transition the downstream
   execution to `BLOCKED` with a bounded structured reason.
6. Define parent aggregation, status API, notification, catalog, and operator behavior
   for terminal `BLOCKED`; it is terminal but is not a worker `FAILED` result.
7. Add the metadata global barrier and exact run/work tests before removing the old
   scheduler blocked-job mechanism.

### Files or modules likely to be touched

- `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/`;
- scheduler, outbox, manual-trigger, execution aggregation, status API, and notification
  code under `apps/core`;
- Platform unit and PostgreSQL/Testcontainers integration tests;
- `database/migrations/` for additive status and outbox terminal-state changes;
- job-flow, database, architecture, developer-navigation, and Platform service docs.

### Acceptance criteria

- Scheduled and accepted manual work commit stable execution and PENDING outbox
  identities before dependency evaluation.
- The dispatcher publishes only `READY` rows; `WAITING` remains retryable without
  consuming attempts, and blocked rows cannot starve unrelated ready rows.
- Manifest READY and exact `dataVersion` remain the hard data dependency contract.
- Execution/outbox lookups cannot use an unscoped latest success; run and item-level
  work match exactly, while metadata waits for the complete global run barrier.
- Terminal upstream failure produces terminal downstream `BLOCKED` plus a bounded
  structured dependency reason; it never fabricates a worker `FAILED` result.
- Terminally blocked outbox rows cannot be reclaimed or published.
- Concurrent dispatchers remain duplicate-safe when eligibility changes around claim.
- Scheduler outbox depends on the dependency registry boundary rather than concrete
  evaluators; unsupported decisions fail closed without a facade-only module cycle.
- Migration and rollback preserve pending work and existing audit history.
- Canonical flow, database, architecture, navigation, service, and applicable agent
  guidance are synchronized.

### Required unit tests

- Registry READY/WAITING/BLOCKED adaptation, incompatible-evidence fail-closed behavior,
  and package-boundary tests.
- Scheduler and manual-trigger tests proving enqueue is independent of readiness.
- Dispatcher `READY`, `WAITING`, `BLOCKED`, no-attempt-increment, and no-starvation tests.
- Exact run/trading-date/work-key and metadata global-barrier tests.
- Parent aggregation, API serialization, notification, and reason sanitization tests
  for terminal `BLOCKED`.

### Required integration or contract tests

- PostgreSQL/Testcontainers tests for concurrent eligibility-and-claim fencing,
  duplicate-safe publication, terminal outbox exclusion, and migration compatibility.
- Restart catch-up test proving dependent stages do not publish simultaneously.
- Manual-trigger lifecycle test from accepted request through WAITING to publication or
  terminal BLOCKED.
- Existing manifest READY/dataVersion behavior remains covered; no Kafka, manifest, or
  storage-path contract migration is permitted in this increment.

### Required Nx/build/CI commands

Subject to the repository verification approval gate, run from the workspace root:

```text
nx run platform:test
nx run platform:build
```

The Platform test target owns the focused unit and PostgreSQL/Testcontainers migration
coverage. Shared local verification on 2026-09-27 concluded
`PASS P4-I3-P8-I5 required=2 pass=2 fail=0 unknown=0 missing=0 sources=exit_code`
for owner-approved `nx run platform:test` and `nx run platform:build`. The build included
the Platform test lifecycle. Initial test failures exposed a stale P4-I2 integration
expectation, a missing transaction on the scheduled producer overload, and a mocked
serializer returning null; all attributable defects were repaired before the complete
passing rerun. Existing Hikari/Modulith shutdown warnings remained non-failing. Format,
CI, commit/PR, deployment, and production checks were not run.

### Data migration or backward-compatibility considerations

Use an additive migration for `BLOCKED` execution support and an explicit terminal
outbox disposition/status. Deploy schema before writer code. Readers must recognize
`BLOCKED` before writers emit it. Rollback must disable dependency-aware dispatch
without deleting pending or terminal records; never rewrite `BLOCKED` as worker
`FAILED`. Any enum/check-constraint, DTO, or persistence change requires coverage for
old PENDING/PUBLISHED rows.

### Security, concurrency, data-quality, and operational risks

- Never add force or dependency-bypass behavior to manual triggers.
- Keep dependency reasons bounded and free of credentials or physical object paths.
- Avoid holding a database transaction while reading manifests or publishing Kafka.
- Revalidate row status and fencing identity in the atomic claim after evaluation.
- Expose waiting/blocked counts and oldest-waiting age so terminal and stalled work are
  diagnosable without execution-history spam.

### Stop conditions requiring owner input

Stop if implementation requires a Kafka/protobuf or manifest schema change, physical
storage paths in business messages, destructive migration, weakening READY/dataVersion
semantics, bypassing manual-trigger dependency checks, or treating downstream BLOCKED
as a worker failure.

### Completion and rollback notes

Local source implementation and Platform test/build evidence are present: durable
execution/PENDING-outbox preparation, Platform-local READY/WAITING/BLOCKED dispatch
decisions, approved `dataVersion` lineage persistence, over-fetch/no-starvation, fenced
eligible claims, terminal outbox/execution blocking, additive V11 persistence, focused
PostgreSQL coverage, mechanics-only safe-write invariants, and canonical docs.
Completion still requires increment-owned commit/PR, exact-head CI, and remaining
operational/completion evidence. Lint and format were not run because Platform defines
no corresponding targets and formatting was not authorized.
Rollback disables the new dispatcher gate and preserves every pending or terminal row;
it does not restore the old pre-enqueue path until schema/read compatibility is proven.
Supporting implementation detail is maintained in
[`../../docs/plans/023-dependency-aware-outbox-dispatch.md`](../../docs/plans/023-dependency-aware-outbox-dispatch.md).
