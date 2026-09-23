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
| status                  | pending                                                |
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

P4-I2 enforces dataset dependencies before scheduled execution and outbox creation.
The manual trigger path uses the same pre-enqueue guard. The outbox dispatcher claims
eligible PENDING rows by availability and publishes them without dependency awareness.
The current execution status model has no terminal `BLOCKED` value.

### Dependencies and eligibility conditions

- P1-I4 supplies required `workType`/`workKey` execution identity.
- P4-I2 supplies the enforced manifest dependency semantics to migrate without
  weakening READY or exact `dataVersion` checks.
- P7-I2 supplies the audited manual-trigger and execution-status boundary.
- All dependencies are completed. Under the owner-approved 2026-09-21 active MVP
  order, P4-I3 follows P8-I1, P8-I2, P8-I4, P8-I5, P9-I1, and P9-I4 closure because
  those active increments overlap `apps/core`. P4-I3 remains `pending`; normal
  readiness propagation may promote it only after those ownership conflicts are
  reconciled. This scheduling order adds no dependency edge and does not weaken the
  critical-correctness priority of P4-I3.

### In scope

- Add a dedicated dependency application module with one exported registry contract
  and private domain policies, including a static VN policy for V1.
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

1. Introduce the registry, immutable request/result types, static VN policy, evaluator,
   and neutral codec behind a Spring Modulith boundary.
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

- `apps/core/src/main/java/com/omni/platform/modules/dependency/`;
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
- Only the dependency registry contract is exported; duplicate policy domains fail
  startup and unsupported domains fail closed.
- Migration and rollback preserve pending work and existing audit history.
- Canonical flow, database, architecture, navigation, service, and applicable agent
  guidance are synchronized.

### Required unit tests

- Registry selection, duplicate domain rejection, unsupported-domain failure, and
  module-boundary tests.
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
coverage. Record formatting and exact-head CI evidence before completion. No executable
verification has been run for this planning update.

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

Completion requires implementation, approved Platform checks, exact-head CI evidence,
migration compatibility, observability, and synchronized canonical documentation.
Rollback disables the new dispatcher gate and preserves every pending or terminal row;
it does not restore the old pre-enqueue path until schema/read compatibility is proven.
Supporting implementation detail is maintained in
[`../../docs/plans/023-dependency-aware-outbox-dispatch.md`](../../docs/plans/023-dependency-aware-outbox-dispatch.md).
