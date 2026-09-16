# Dependency-Aware Outbox Dispatch

Status: Draft implementation plan; not roadmap-scheduled.

## Goal

Prevent overdue scheduled jobs from starting nearly simultaneously after a service restart by moving dependency eligibility to the outbox dispatch boundary. Reuse one dependency model for Scheduler, Outbox, and future modules, with a static Vietnam-market definition in V1.

## Outcome

- `JobScheduler` creates due executions and PENDING outbox messages without blocking on dependencies.
- `SchedulerOutboxDispatcher` evaluates dependency eligibility before claim/publish.
- A dedicated Spring Modulith module exports only the `DependencyRegistry` contract and the minimum immutable request/result types.
- The module implementation, VN factory/catalog, evaluators, codecs, and storage-shape helpers remain private.
- V1 uses static definitions; no dependency repository or dependency table is introduced.
- Dispatch is scoped to the same logical run and work item so an older success cannot unlock a new run.
- Metadata jobs use a global barrier and cannot race upstream sync/manifest updates after evening startup.

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

The minimum exported immutable types are `DependencyKey`, `DependencySpec`, `DependencyRequest`, and `DependencyDecision`. All factories and implementation packages stay private.

Private V1 implementation:

- `StaticDependencyRegistry`: immutable definitions keyed by stable job/work type.
- `VnDependencyFactory`: creates the VN dependency catalog.
- `DependencyEvaluator`: resolves job status, dataset readiness, and global barriers.
- `DependencySpecCodec`: converts definitions to/from neutral column/JSON maps for future persistence or SQL binding; it does not generate raw SQL.
- No repository, dependency table, or runtime write API in V1.

## Runtime Flow

1. Scheduler calculates due work, creates the execution, and writes a PENDING outbox message.
2. Dispatcher over-fetches PENDING candidates so blocked rows do not consume the publish batch.
3. For each candidate, dispatcher builds a request with `jobDefinitionId`, `executionId`, `parentExecutionId`, `workType`, `workKey`, and `runKey`/trading date.
4. Registry returns:
   - `READY`: atomically claim and publish.
   - `WAITING`: keep PENDING, do not increment attempts, and return an optional `retryAt`.
   - `FAILED`: stop retrying and mark the downstream execution blocked/failed with a reason code.
5. On the next poll, completed upstream work unlocks eligible downstream messages.

Eligibility is evaluated before the atomic claim. Concurrent dispatchers must still rely on the existing database claim/locking boundary to prevent duplicate publication.

## Dependency Semantics

- Match dependencies within the same `runKey`/trading date and, for item-level pipelines, the same `workType + workKey`.
- Never use an unscoped “latest success” from a previous run.
- Prefer item-level streaming: one symbol may advance from price to indicators to signals while other symbols remain in progress.
- Use a global barrier for metadata: all required upstream executions/datasets for the run must be complete and no relevant work may remain pending or in flight.
- Initial VN catalog:
  - indicators depend on stock-price EOD readiness;
  - signals depend on indicator readiness;
  - confirmed signals retain any exact-date intraday requirement defined by their algorithm plan;
  - metadata depends on the required upstream executions and dataset readiness for that run.

## Implementation Increments

1. Add the `dependency` module, exported contract, private VN static factory, evaluator, and codec.
2. Migrate existing scheduler dependency definitions into the registry without changing their meaning.
3. Remove dependency blocking from `JobScheduler`; always enqueue due work transactionally.
4. Add the registry gate to outbox candidate selection/claim and prevent head-of-line blocking.
5. Add terminal upstream-failure propagation and metadata global-barrier rules.
6. Add module-boundary, scheduler, dispatcher, concurrency, and run/work-scope tests.
7. Update canonical job-flow, architecture, developer-navigation, and relevant service documentation.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output. This change only controls when the existing metadata workflow may run.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No new algorithm. Existing price, indicator, signal, intraday-confirmation, and metadata pipelines gain deterministic dependency-aware dispatch.

## Contract Impact

| Area | Decision |
| --- | --- |
| Kafka/service-to-service protobuf | Unchanged in V1; dispatch uses existing execution and work identity. |
| Object-storage JSON manifest | Unchanged; dependency evaluation reads existing readiness/version evidence. |
| Storage path/dataset ownership | Unchanged. |
| Public Java/Python API | Add one Java module contract for in-process consumers; no external service API is added. |
| Configuration/environment | No new environment variable in V1; VN definitions are static. |
| Database | No dependency repository/table. Existing execution/outbox state is reused; any new terminal status requires an explicit migration review. |

## Repository Guidance Updates

Implementation must review and update, where behavior changes:

- `docs/flows/001-job-execution.md`;
- `docs/architecture/001-system-overview.md`;
- `docs/development/001-where-to-change.md`;
- `docs/plans/005-job-dependency-guard.md` as historical/canonical dependency context;
- relevant Core/Platform README files;
- `AGENTS.md`, `CLAUDE.md`, and `.roo/rules/` only if ownership or workflow guidance changes.

## Verification

Not run for this documentation-only draft.

Required implementation evidence:

- Spring Modulith boundary test proves only the dependency contract package is exported.
- Scheduler test proves unmet dependencies do not prevent PENDING outbox creation.
- Dispatcher tests cover `READY`, `WAITING`, `FAILED`, no attempt increment while waiting, and no blocked-row starvation.
- Scope tests prove exact run/trading-date and work-key matching.
- Metadata test proves the global barrier blocks while upstream work is pending/in flight.
- PostgreSQL integration test proves concurrent claim/publish remains duplicate-safe.
- Inspect Nx targets before running the smallest relevant Platform/Core test and build targets.

## Acceptance Criteria

- Restarting after downtime does not dispatch dependent stages simultaneously.
- Scheduler enqueue is independent from dependency readiness.
- Outbox publishes only `READY` messages.
- `WAITING` messages stay retryable without consuming delivery attempts.
- Terminal upstream failure produces a diagnosable downstream terminal state.
- Blocked candidates cannot starve unrelated ready work.
- Dependency resolution is exact to the same run and work item.
- Metadata dispatch waits for the complete upstream run barrier.
- Only the registry contract is visible outside the dependency module.
- V1 has no dependency repository and no Kafka, manifest, or storage-path contract change.

## Non-goals

- Provider rate limiting or a general resource-capacity scheduler.
- Dynamic dependency administration UI/API.
- Persisted dependency definitions in V1.
- Kafka payload redesign.
- Query Service or Console feature changes.
