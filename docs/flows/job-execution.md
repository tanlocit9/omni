# Job Execution Flow

Platform owns job orchestration. Workers execute data-plane tasks and report status back through Kafka.

The repository now contains versioned `JobCommand` and `JobStatusEvent` Proto3 schemas in [`contracts/proto`](../../contracts/proto). They are a generated contract foundation only: the production flow described below remains on the existing JSON wire format until compatible consumers and adapters are delivered in later Phase 2 increments.

## Flow

```mermaid
sequenceDiagram
  participant Scheduler as JobScheduler
  participant ProducerRegistry as JobProducerRegistry
  participant Producer as JobProducer
  participant DB as PostgreSQL
  participant Kafka as Kafka
  participant Worker as Ingestor / Analyzer
  participant Storage as MinIO / Parquet
  participant Status as Platform Status Consumer
  participant PolicyRegistry as JobNotificationPolicyRegistry
  participant Notification as Notification Event

  Scheduler->>DB: Claim due JobDefinition with SKIP LOCKED
  Scheduler->>ProducerRegistry: Resolve producer by JobType
  ProducerRegistry-->>Scheduler: JobProducer
  Scheduler->>Producer: prepareDispatch(job, claim, now)
  Producer->>DB: Atomically create execution(s), outbox, advance nextRun, release claim
  DB-->>Scheduler: Commit stable execution/message identities
  Scheduler->>DB: Claim pending outbox messages
  Scheduler->>Kafka: Publish serialized outbox payload(s)
  Scheduler->>DB: Mark exact outbox claim published or retryable
  Kafka->>Worker: Deliver job by topic
  Worker->>Storage: Read/write datasets if needed
  Worker->>Kafka: Publish topic-sync-job-status
  Kafka->>Status: Deliver status event
  Status->>DB: Update child execution
  Status->>DB: Aggregate parent execution
  Status->>PolicyRegistry: Resolve notification policy by JobType
  PolicyRegistry-->>Status: Default or custom notification event
  Status->>Notification: Publish event for delivery
```

## Compact Flow

```text
JobScheduler
 → JobProducerRegistry
 → JobProducer
 → SchedulerOutboxMessage
 → SchedulerOutboxDispatcher
 → Kafka
 → Worker / Analyzer
 → JobStatusMessage
 → JobService
 → JobNotificationPolicyRegistry
 → NotificationEvent
 → NotificationTemplate
 → TelegramNotificationService
```

## Responsibilities

| Step                  | Owner                | Responsibility                                                              |
| --------------------- | -------------------- | --------------------------------------------------------------------------- |
| Schedule selection    | Platform             | Atomically claim enabled jobs due for execution.                            |
| Producer resolution   | Platform             | Resolve `JobType` to a registered `JobProducer`; fail fast if none exists.  |
| Job definition        | Platform/PostgreSQL  | Store job type, schedule, and job-specific config.                          |
| Parent execution      | Platform/PostgreSQL  | Track an execution batch across child tasks.                                |
| Child execution       | Platform/PostgreSQL  | Track one executable work unit sent to a worker.                            |
| Kafka command         | Platform             | Persist then asynchronously publish a worker-specific job payload.          |
| Worker processing     | Ingestor or Analyzer | Execute data-plane work.                                                    |
| Status event          | Worker               | Publish `topic-sync-job-status` with execution identity, metrics, and meta. |
| Aggregation           | Platform             | Update child status and roll up parent status when applicable.              |
| Notification policy   | Platform             | Resolve custom policy by job type, or use the default generic policy.       |
| Notification delivery | Platform             | Publish a notification event for template rendering and Telegram delivery.  |

## Scheduler Claim and Transactional Outbox

The production scheduler uses PostgreSQL leases and a transactional outbox. Claim acquisition and outbox acquisition are separate short transactions; Kafka I/O never holds a database transaction open.

```mermaid
sequenceDiagram
  participant Scheduler as JobScheduler
  participant ClaimService as SchedulerClaimService
  participant DB as PostgreSQL job_definitions
  participant Outbox as scheduler_outbox_messages
  participant Kafka as Kafka

  Scheduler->>ClaimService: claimDueJobs(now)
  ClaimService->>DB: SELECT due rows FOR UPDATE SKIP LOCKED LIMIT batchSize
  ClaimService->>DB: Set claimToken, claimedBy, claimedAt, claimUntil
  ClaimService-->>Scheduler: Immutable SchedulerClaim list
  Scheduler->>DB: Load claimed definition
  Scheduler->>DB: In one transaction create execution(s), advance nextRun, insert outbox rows, clear exact claim
  Scheduler->>Outbox: Claim pending rows with lease + fencing token
  Scheduler->>Kafka: Publish stable serialized payload outside transaction
  alt publish succeeds
    Scheduler->>Outbox: Mark PUBLISHED using exact token and owner
  else publish fails
    Scheduler->>Outbox: Preserve same message id/payload and schedule retry
  end
```

Claim candidates use the Phase 0 due semantics: active jobs where `nextRun <= now` or `nextRun IS NULL`. A live lease prevents reclaim; an expired claim receives a new UUID fencing token. Execution and outbox rows commit once, so a Kafka retry reuses the same execution id, outbox message id, key, and serialized payload instead of creating a second logical execution. Delivery remains at-least-once because a process may stop after Kafka accepts a message but before the database acknowledgement commits; consumers must therefore remain idempotent by execution identity.

## Dependency Tree Metadata

Seeded job definitions carry non-enforcing dependency metadata in [`JobDefinitionConfig.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/constants/JobDefinitionConfig.java). The metadata is stored under `dataDependencies` inside `config_json` for visibility and future orchestration work only. The scheduler does not block, reorder, or retry jobs from this metadata yet.

```mermaid
flowchart TD
  SyncSymbols["SYNC_SYMBOLS"] --> Symbols[(symbols)]
  SyncSymbols --> Sectors[(sectors)]
  Symbols --> SyncStockPrice["SYNC_STOCK_PRICE"]
  Sectors --> SyncStockPrice
  SyncStockPrice --> EOD[(eod)]

  EOD --> SyncIndicators["SYNC_INDICATORS"]
  SyncIndicators --> Indicators[(indicators)]
  EOD --> SyncSignals["SYNC_SIGNALS"]
  Indicators --> SyncSignals
  SyncSignals --> Signals[(signals)]
  EOD --> EvaluateSignals["EVALUATE_SIGNALS"]
  Signals --> EvaluateSignals
  EvaluateSignals --> SignalEvaluations[(signal-evaluations)]

  Symbols --> PrecomputeSymbolFeatures["PRECOMPUTE_SYMBOL_FEATURES"]
  Sectors --> PrecomputeSymbolFeatures
  EOD --> PrecomputeSymbolFeatures
  PrecomputeSymbolFeatures --> SymbolFeatures[(symbol-features)]
  SymbolFeatures --> PrecomputeSectorFeatures["PRECOMPUTE_SECTOR_FEATURES"]
  PrecomputeSectorFeatures --> SectorFeatures[(sector-features)]
  SectorFeatures --> SectorRotationBacktest["SECTOR_ROTATION_BACKTEST"]
  EOD --> SectorRotationBacktest
  SectorRotationBacktest --> SectorRotationBacktests[(sector-rotation-backtests)]

  SectorFeatures --> SectorTransitionAnalyze["SECTOR_TRANSITION_ANALYZE"]
  SectorTransitionAnalyze --> SectorTransitionPredictions[(sector-transition-predictions)]
  SectorTransitionAnalyze --> SectorTransitionProbabilities[(sector-transition-probabilities)]
  SectorTransitionAnalyze --> SectorTransitionDecisions[(sector-transition-decisions)]
  SectorTransitionPredictions --> SectorTransitionEvaluateOutcomes["SECTOR_TRANSITION_EVALUATE_OUTCOMES"]
  SectorTransitionDecisions --> SectorTransitionEvaluateOutcomes
  EOD --> SectorTransitionEvaluateOutcomes
  SectorTransitionEvaluateOutcomes --> SectorTransitionOutcomes[(sector-transition-outcomes)]
```

## Core Contracts

| Contract               | Canonical doc/source                                                                                                                                                                                                                                 |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Status topic           | [`topic-sync-job-status`](../data/kafka-contracts.md#topic-sync-job-status)                                                                                                                                                                          |
| Job definitions        | [`database/migrations/V1__create_job_definitions_table.sql`](../../database/migrations/V1__create_job_definitions_table.sql), [`database/migrations/V5__add_scheduler_claim_lease.sql`](../../database/migrations/V5__add_scheduler_claim_lease.sql) |
| Job execution history  | [`database/migrations/V2__create_job_execution_histories_table.sql`](../../database/migrations/V2__create_job_execution_histories_table.sql)                                                                                                         |
| Scheduler outbox       | [`database/migrations/V6__create_scheduler_outbox.sql`](../../database/migrations/V6__create_scheduler_outbox.sql)                                                                                                                                   |
| Java messaging records | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging)                                                                                               |
| Java producers         | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/producers`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/producers)                                                                                               |
| Producer registry      | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducerRegistry.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducerRegistry.java)                                             |
| Notification policies  | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/notifications`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/notifications)                                                                                       |
| Java status consumer   | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java)                                                 |

## Parent/Child Execution Model

```mermaid
flowchart TD
  Definition["JobDefinition"]
  Parent["Parent JobExecutionHistory"]
  ChildA["Child execution A"]
  ChildB["Child execution B"]
  ChildN["Child execution N"]
  StatusA["Status A"]
  StatusB["Status B"]
  StatusN["Status N"]
  Aggregate["Aggregate parent status"]

  Definition --> Parent
  Parent --> ChildA
  Parent --> ChildB
  Parent --> ChildN
  ChildA --> StatusA
  ChildB --> StatusB
  ChildN --> StatusN
  StatusA --> Aggregate
  StatusB --> Aggregate
  StatusN --> Aggregate
  Aggregate --> Parent
```

## Extending a Job Type

1. Add or reuse a `JobDefinition.JobType` value.
2. Implement a `JobProducer` and return that value from `getJobType()`.
3. Keep producer logic execution-focused: prepare execution, build Kafka messages, enqueue them transactionally, and run `postPublish()` if needed.
4. Do not add scheduler dispatch branches. Spring registers the producer in `JobProducerRegistry` automatically; duplicate producers for the same job type fail application startup.
5. Add or update producer and scheduler tests for the new registration.

## Notification Policies

`JobService` owns status updates and parent aggregation only. After a standalone or parent execution reaches a terminal status, it builds a `JobNotificationContext` and delegates event selection to `JobNotificationPolicyRegistry`.

Use `DefaultJobNotificationPolicy` for generic operational success/failure messages. Add a custom `JobNotificationPolicy` when a job type needs domain-specific rendering, such as Signal Digest summaries or actionable Sector Transition failures. Custom policies should return a notification event and keep template/Telegram delivery outside scheduler producers.

`symbolKey` remains in Kafka status messages for backward compatibility. New Platform child execution code also stores `workKey` in metadata via `createChildExecution(...)`; future contract changes can promote `workKey` only after producers, consumers, tests, and this document are updated together.

## Failure Semantics

- Worker failures should publish a status event when possible.
- Status events should include `executionId`, optional `parentExecutionId`, final status, duration, metrics, error details, and relevant `metaJson` context.
- Workers should preserve domain metadata on failures instead of replacing metadata with only `recordsProcessed = 0`.
- Platform should update the child execution by execution identity, not by symbol alone.
- Parent aggregation should wait for all child executions to reach terminal states.
- Error details must not include credentials, object-store secrets, or provider tokens.

## Related Flows

- [Stock sync](stock-sync.md)
- [Indicator and signal](indicator-signal.md)
- [Sector wave](sector-wave.md)
