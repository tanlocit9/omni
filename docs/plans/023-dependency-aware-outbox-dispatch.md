# Dependency-Aware Outbox Dispatch

Canonical status and schedule owner: [P4-I3 in implementation increments](../../plans/roadmap/implementation-increments.md). This document is supporting implementation detail only and must not define an independent execution schedule. P4-I3 is `pending` until overlapping active `apps/core` ownership is reconciled under the roadmap readiness rules.

## Goal

Prevent overdue scheduled jobs from starting nearly simultaneously after a service restart by moving dependency eligibility to the outbox dispatch boundary. Reuse one dependency model for Scheduler, Outbox, and future modules, with a static Vietnam-market definition in V1.

## Outcome

- Scheduled and accepted manual work create executions and PENDING outbox messages without blocking on dependencies.
- `SchedulerOutboxDispatcher` is the one dependency gate for scheduled and manual work before claim/publish.
- A dedicated Spring Modulith module exports only the `DependencyRegistry` contract and the minimum immutable request/result types.
- The module follows the existing notification-policy registration pattern: one public registry, private policies discovered as Spring beans, and fail-fast duplicate registration.
- The registry selects a dependency policy by domain, so future VN/US/Crypto policies can be added without changing Scheduler or Outbox.
- V1 uses static definitions; no dependency repository or dependency table is introduced.
- Dispatch is scoped to the same logical run and work item so an older success cannot unlock a new run.
- Metadata jobs use a global barrier and cannot race upstream sync/manifest updates after evening startup.
- Terminal upstream failure marks the downstream execution `BLOCKED` with a bounded structured reason; it does not fabricate a worker `FAILED` result.

## Current Problem

The scheduler currently checks dependencies before enqueue, while the outbox claims PENDING messages FIFO without dependency awareness. After downtime, multiple overdue jobs can be enqueued or become runnable together, causing concurrent database, Kafka, MinIO, and metadata activity. The metadata job currently has no effective dependency barrier, which allows metadata updates to overlap incomplete upstream work.

## Proposed Module Boundary

Create a dedicated `dependency` application module.

Exported API:

```java
public interface DependencyRegistry {
    DependencySpec get(DependencyKey key);
    DependencyDecision evaluate(DependencyRequest request);
}
```

The minimum exported immutable types are `DependencyDomain`, `DependencyKey`, `DependencySpec`, `DependencyRequest`, and `DependencyDecision`. Policy interfaces and implementations stay private.

Use the same registration pattern as `JobNotificationPolicyRegistry`:

```text
DependencyRegistry
  -> List<DependencyPolicy>
       -> VNDependencyPolicy
       -> future USDependencyPolicy
       -> future CryptoDependencyPolicy
```

- `DependencyPolicy`: private internal policy contract exposing `domain()`, `specs()`, and `evaluate(request)`.
- `VNDependencyPolicy`: V1 Spring component that owns the immutable VN dependency catalog and evaluation rules.
- `StaticDependencyRegistry`: receives `List<DependencyPolicy>`, indexes policies by `DependencyDomain`, and delegates `specs(domain)` and `evaluate(request)`.
- This mirrors notification: `DependencyRegistry` corresponds to `JobNotificationPolicyRegistry`; `DependencyPolicy` corresponds to `JobNotificationPolicy`; VN/US/Crypto policies correspond to concrete notification policies.
- Scheduler and Outbox inject only `DependencyRegistry`; they never request or cast to `VNDependencyPolicy`.
- Duplicate domain registration fails during startup. Unlike notification, dependency dispatch has no permissive default policy: an unsupported domain fails closed with a diagnosable decision.
- `DependencyEvaluator`: treats manifests as the hard data-readiness source and uses execution/outbox state only for exact same-run/work matching and global barriers.
- `DependencySpecCodec`: converts definitions to/from neutral column/JSON maps for future persistence or SQL binding; it does not generate raw SQL.
- No factory layer, repository, dependency table, or runtime write API in V1.

## Runtime Flow

1. Scheduler or the audited manual-trigger path accepts work, creates the execution, and writes a PENDING outbox message without a pre-enqueue dependency block.
2. Manual API responses expose the stable request/execution identity; later status reflects dependency `WAITING`, publication, or terminal `BLOCKED`.
3. Dispatcher over-fetches PENDING candidates so blocked rows do not consume the publish batch.
4. For each candidate, dispatcher builds a request with `domain`, `jobDefinitionId`, `executionId`, `parentExecutionId`, `workType`, `workKey`, and `runKey`/trading date.
5. Registry selects the registered `DependencyPolicy`; V1 routes `VN` to `VNDependencyPolicy`.
6. The selected policy evaluates its static specs and returns:
   - `READY`: atomically claim and publish.
   - `WAITING`: keep PENDING, do not increment delivery attempts, persist bounded diagnostics, and return an optional `retryAt`.
   - `BLOCKED`: atomically stop future claims, mark the downstream execution terminal `BLOCKED`, and persist a bounded structured dependency reason.
7. On the next poll, completed upstream work unlocks eligible downstream messages.

Eligibility is evaluated before the atomic claim without holding a transaction across manifest I/O. The atomic claim must revalidate that the row is still PENDING and eligible under its fencing identity. Concurrent dispatchers continue to rely on the database claim/locking boundary to prevent duplicate publication.

## Dependency Semantics

- Dataset manifests and exact `dataVersion` lineage are the hard data dependency contract.
- Execution/outbox state is used only to match dependencies within the same `runKey`/trading date and, for item-level pipelines, the same `workType + workKey`, plus the metadata global barrier.
- Never use an unscoped “latest success” from a previous run.
- Prefer item-level streaming: one symbol may advance from price to indicators to signals while other symbols remain in progress.
- Use a global barrier for metadata: all required upstream executions/datasets for the run must be complete and no relevant work may remain pending or in flight.
- Initial VN catalog:
  - indicators depend on stock-price EOD readiness;
  - signals depend on indicator readiness;
  - confirmed signals retain any exact-date intraday requirement defined by their algorithm plan;
  - metadata depends on the required upstream executions and dataset readiness for that run.

## Implementation Increments

1. Add the `dependency` module and exported registry contract, plus private `DependencyPolicy`, `VNDependencyPolicy`, evaluator, and codec using the notification registry pattern.
2. Migrate existing scheduler dependency definitions into the registry without changing manifest READY or `dataVersion` semantics.
3. Remove dependency blocking from both `JobScheduler` and the audited manual-trigger acceptance path; always enqueue accepted work transactionally.
4. Add the registry gate to outbox candidate selection/claim and prevent head-of-line blocking.
5. Add an additive migration for terminal execution `BLOCKED`, structured dependency reasons, and a terminal outbox disposition that cannot be reclaimed.
6. Define aggregation, status API, notification, and operator visibility semantics for `BLOCKED`.
7. Add terminal upstream-failure propagation and metadata global-barrier rules.
8. Remove the old scheduler blocked-job retry path only after equivalent outbox retry and visibility behavior is covered.
9. Add module-boundary, scheduler, manual-trigger, dispatcher, migration, concurrency, and run/work-scope tests.
10. Update canonical job-flow, database, architecture, developer-navigation, and relevant service documentation.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output. This change only controls when the existing metadata workflow may run.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No new algorithm. Existing price, indicator, signal, intraday-confirmation, and metadata pipelines gain deterministic dependency-aware dispatch.

## Contract Impact

| Area                              | Decision                                                                                                                                                                                                                                                                                       |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf | Unchanged in V1; dispatch uses existing execution and work identity.                                                                                                                                                                                                                           |
| Object-storage JSON manifest      | Unchanged; dependency evaluation reads existing readiness/version evidence.                                                                                                                                                                                                                    |
| Storage path/dataset ownership    | Unchanged.                                                                                                                                                                                                                                                                                     |
| Public Java/Python API            | Add one Java module contract for in-process consumers and extend existing execution-status responses with terminal `BLOCKED`; no new service endpoint is added.                                                                                                                                |
| Configuration/environment         | No new environment variable in V1; VN definitions are static.                                                                                                                                                                                                                                  |
| Database                          | No dependency repository/table. Add an additive migration for terminal execution `BLOCKED`, a bounded structured dependency reason, and a terminal outbox disposition/status. Deploy schema and compatible readers before writers; preserve existing PENDING/PUBLISHED rows and audit history. |

## Repository Guidance Updates

Implementation must review and update, where behavior changes:

- `docs/flows/001-job-execution.md`;
- `docs/architecture/001-system-overview.md`;
- `docs/data/003-database.md`;
- `docs/development/001-where-to-change.md`;
- `docs/plans/005-job-dependency-guard.md` as historical/canonical dependency context;
- relevant Core/Platform README files;
- `AGENTS.md`, `CLAUDE.md`, and `.roo/rules/` only if ownership or workflow guidance changes.

## Verification

Not run for this documentation-only roadmap admission.

Required implementation evidence:

- Spring Modulith boundary test proves only the dependency contract package is exported.
- Policy-registry tests prove VN selection, rejection of duplicate domains, and fail-closed behavior for an unsupported domain.
- Scheduler and manual-trigger tests prove unmet dependencies do not prevent accepted work from creating execution and PENDING outbox identities.
- Dispatcher tests cover `READY`, `WAITING`, `BLOCKED`, no attempt increment while waiting, and no blocked-row starvation.
- Scope tests prove exact run/trading-date and work-key matching.
- Metadata test proves the global barrier blocks while upstream work is pending/in flight.
- Execution aggregation, status API, notification, and sanitization tests cover terminal `BLOCKED` without treating it as worker `FAILED`.
- Migration tests cover old PENDING/PUBLISHED rows, schema-first rollout, terminal outbox exclusion, and non-destructive rollback.
- PostgreSQL integration tests prove concurrent eligibility/claim/publish remains duplicate-safe and terminal rows cannot be reclaimed.
- After approval under the verification gate, run `nx run platform:test` and `nx run platform:build` from the workspace root; record formatting and exact-head CI separately before completion.

## Acceptance Criteria

- Restarting after downtime does not dispatch dependent stages simultaneously.
- Scheduled and accepted manual enqueue are independent from dependency readiness.
- Outbox publishes only `READY` messages.
- `WAITING` messages stay retryable without consuming delivery attempts.
- Terminal upstream failure produces downstream terminal `BLOCKED` with a bounded structured reason and never fabricates worker `FAILED`.
- Terminally blocked outbox rows cannot be reclaimed or published.
- Blocked candidates cannot starve unrelated ready work.
- Dependency resolution is exact to the same run and work item.
- Metadata dispatch waits for the complete upstream run barrier.
- Only the registry contract is visible outside the dependency module.
- A new VN/US/Crypto policy can be registered without changing Scheduler, Outbox, or the exported registry contract.
- V1 has no dependency repository and no Kafka, manifest, or storage-path contract change.
- Migration/rollback preserves pending work, terminal records, and existing audit history.
- Canonical flow, database, architecture, developer-navigation, service, and applicable repository guidance are synchronized.

## Non-goals

- Provider rate limiting or a general resource-capacity scheduler.
- Dynamic dependency administration UI/API.
- Persisted dependency definitions in V1.
- Kafka payload redesign.
- New Query Service or Console features; existing Platform execution-status responses may expose terminal `BLOCKED`.
