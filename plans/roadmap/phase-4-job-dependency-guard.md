# Phase 4 — Job Dependency Guard

## Goal

Use manifests to decide whether analytical work is dispatchable, while keeping blocked work distinct from failed execution.

## Increment P4-I1 — Dependency policies and shadow guard

| Field                   | Value                                |
| ----------------------- | ------------------------------------ |
| id                      | P4-I1                                |
| title                   | Dependency policies and shadow guard |
| status                  | pending                              |
| priority                | critical                             |
| depends_on              | [P3-I3, P1-I4]                       |
| blocks                  | [P4-I2, P5-I2, P6-I1]                |
| owned_modules           | [apps/core, configs, docs/data]      |
| execution_mode          | autonomous                           |
| requires_owner_decision | false                                |
| pr                      | null                                 |
| last_verified_commit    | null                                 |

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
| status                  | verification_pending                               |
| priority                | critical                                           |
| depends_on              | [P4-I1]                                            |
| blocks                  | [P5-I2, P6-I1]                                     |
| owned_modules           | [apps/core, apps/analyzer, configs]                |
| execution_mode          | autonomous                                         |
| requires_owner_decision | false                                              |
| pr                      | null                                               |
| last_verified_commit    | cb0b397                                            |

Goal: enforce dependency guard for one analytical job after shadow-mode evidence is acceptable.

Verification state: `feature/phase-7@cb0b397` wires an ENFORCED per-symbol EOD
dependency context into `SYNC_INDICATORS`, releases claims while blocked,
persists one bounded-backoff blocked record without execution/outbox spam, and
attaches approved manifest versions before dispatch. Unit coverage and a real
PostgreSQL/Testcontainers BLOCKED-to-dispatch test are present. Completion is
still pending a successful Platform test/build and CI run; the current
automation environment could not download the pinned Gradle distribution.

Acceptance criteria: missing/stale data produces blocked/deferred scheduler state, no execution-history spam is created for blocked polls, exact approved input versions attach to execution, and dependency backoff is bounded and observable.

Required tests/checks: scheduler race tests around guard-ready then claim, blocked-state/backoff tests, metrics/log assertions, and affected Core/Analyzer checks.

Stop conditions: stop if enforcement target lacks READY manifests or shadow data contradicts documented policy.
