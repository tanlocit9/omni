# Legacy Dependency-Guard Residue

## Review — 2026-10-08

| Field                 | Assessment                                                                                                                                                                                                                                                                                                                                                                                                               |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Type                  | Cleanup / optional dependency-policy capability                                                                                                                                                                                                                                                                                                                                                                          |
| Status                | OPEN / non-blocking for P14-I1 and P14-I2                                                                                                                                                                                                                                                                                                                                                                                |
| Priority              | P2 conditional; promote a bounded slice only from an activation trigger below                                                                                                                                                                                                                                                                                                                                            |
| Static evidence       | V7 `blocked_jobs`, entity/repository/tracker source exist, but current `JobScheduler` has no tracker dependency and code-review-graph found no caller of the tracker class. Optional dependency conditions exist in the enum/config model, but no evaluator is registered for `PARTITION_MATCH`, `SUPPORTED_SCHEMA_VERSION`, or `MAX_FRESHNESS_LAG`; unsupported configured conditions fail closed as dependency errors. |
| Active owner boundary | Plan 023/P4-I3 owns scheduler-outbox READY/WAITING/BLOCKED and claim/fencing evidence. Plan 030/P14 owns topology and dispatch selection. TD-007 owns dependency-evaluation executor lifecycle, admission, and load evidence.                                                                                                                                                                                            |

## Decision

Move these legacy residues to technical debt so P14-I1 static topology and P14-I2 pure planning can proceed without reactivating the old pre-enqueue dependency design.

This deferral does not authorize deleting schema/audit state, weakening fail-closed behavior, enabling unsupported conditions, or bypassing `DependencyRegistry`. P14-I3 follows P14-I2 and owns the transferred dependency-aware safety proof documented in TD-014; a separate P4-I3 completion is no longer required.

## Residue A — Legacy Blocked-Job Tracking

Current source contains:

- `database/migrations/V7__create_blocked_jobs_table.sql`;
- `BlockedJob`, `BlockedJobRepository`, and `BlockedJobTracker`;
- retry/resolution queries and historical diagnostics.

Current active flow instead:

1. commits execution plus PENDING scheduler-outbox intent;
2. evaluates dependencies at scheduler-outbox dispatch;
3. leaves transient WAITING work PENDING with a future availability time;
4. records terminal BLOCKED on the outbox/execution;
5. uses atomic claim/fencing for READY work.

`JobScheduler` does not call `BlockedJobTracker`, and static graph analysis found no caller of the tracker class. The legacy table/service therefore must not become a second readiness, retry, or topology authority.

### Activation triggers

Promote a bounded cleanup/migration story only when one of these occurs:

- operational/database evidence shows meaningful storage, query, migration, or support cost;
- a supported API still exposes legacy blocked-job records as current truth;
- schema cleanup is required for a deployment or database-maintenance objective;
- an audit/retention decision explicitly defines how historical rows must be preserved.

### Required cleanup boundary

- inventory all reads/writes and retained rows before migration;
- prove no runtime caller, scheduled trigger, API, report, or operational script depends on the table;
- define retention/export requirements;
- remove Java components and schema only through an additive/non-destructive migration path appropriate to retained evidence;
- never delete pending scheduler-outbox rows or execution history;
- update database, job-flow, Plan 005/006/023, and service documentation together.

## Residue B — Optional Dependency Evaluators

`DependencyCondition` and parameter extraction include:

- `PARTITION_MATCH`;
- `SUPPORTED_SCHEMA_VERSION`;
- `MAX_FRESHNESS_LAG`.

`DefaultJobDependencyGuard` registers only EXISTS, READY, MIN_ROW_COUNT, and CURRENT_INPUTS evaluators. If an unregistered condition is configured, evaluation produces an error and an ENFORCED dependency fails closed.

The current P4-I2 exact EOD path generated by `JobDependencyContextFactory` uses EXISTS and READY. Therefore the absent optional evaluators are not prerequisites for P14 topology/planning.

### Activation triggers

Promote one evaluator as a bounded guard-contract story before:

- an active ENFORCED dependency declaration uses that condition;
- a product/data contract requires schema-version or freshness policy at dispatch;
- current partition identity cannot be validated safely by existing logical references;
- runtime evidence shows the existing conditions cannot protect an approved workflow.

### Required evaluator boundary

- define parameter type, nullability, units, and compatibility;
- update declaration validation so unsupported/invalid values fail at seed/startup validation rather than only at dispatch;
- implement one evaluator with direct success, failure, missing-evidence, malformed-input, and I/O/error coverage;
- preserve manifest-based readiness, exact `dataVersion` lineage, and no Parquet-prefix scan;
- update affected producer/consumer/configuration/data documentation only if the condition changes a shared contract.

## Relationship to Plan 030

- P14-I1 may inventory these legacy declarations but must not absorb tracker cleanup or optional evaluator implementation.
- P14-I2 treats dependency decisions as external readiness facts and must not interpret a missing evaluator, execution SUCCESS, or empty backlog as READY.
- P14-I3 continues to call `DependencyRegistry`; fail-closed guard errors remain WAITING/BLOCKED according to the transferred TD-014 safety contract.
- This debt record cannot substitute for P14-I3 blast-radius, coverage, migration, concurrency, claim/fencing, compatibility, fallback, or runtime evidence.

## Contract Impact

| Contract area                     | Decision                                                                                                                                |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf | Unchanged by this debt classification.                                                                                                  |
| Object-storage JSON manifests     | Unchanged; optional evaluators would consume existing manifest evidence unless separately approved.                                     |
| Storage paths/dataset ownership   | Unchanged.                                                                                                                              |
| Public Java/Python API            | Unchanged by classification. A future cleanup/evaluator slice must review internal Java callers and any exposed legacy blocked-job API. |
| Configuration/environment         | Unchanged now. A future evaluator may add validated configuration only through its own approved story.                                  |
| PostgreSQL                        | No migration now. Future tracker cleanup requires explicit retention and non-destructive migration review.                              |

## Verification Boundary

Static source inspection and code-review-graph queries were performed for this documentation classification. No build, test, lint, format, migration, database, load, runtime, or deployment command was run.

## Closure Criteria

This record closes only when both residue families are explicitly resolved or separately superseded:

- legacy blocked-job ownership is retained with a documented active purpose, or safely removed with migration/retention evidence; and
- optional conditions are either implemented before use or removed/reserved from the supported declaration contract with compatibility evidence.

Closing this debt is not a prerequisite for completing P14-I1 or P14-I2. It becomes relevant to P14-I3 only if integration introduces an active dependency on one of these residues, which Plan 030 currently forbids.
