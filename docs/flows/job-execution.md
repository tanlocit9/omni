# Job Execution Flow

Platform owns job orchestration. Workers execute data-plane tasks and report status back through Kafka.

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

  Scheduler->>DB: Load due JobDefinition
  Scheduler->>ProducerRegistry: Resolve producer by JobType
  ProducerRegistry-->>Scheduler: JobProducer
  Scheduler->>Producer: publish(job, now)
  Producer->>DB: Create parent execution
  Producer->>DB: Create child execution(s)
  Producer->>Kafka: Publish job message(s)
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
| Schedule selection    | Platform             | Find enabled jobs due for execution.                                        |
| Producer resolution   | Platform             | Resolve `JobType` to a registered `JobProducer`; fail fast if none exists.  |
| Job definition        | Platform/PostgreSQL  | Store job type, schedule, and job-specific config.                          |
| Parent execution      | Platform/PostgreSQL  | Track an execution batch across child tasks.                                |
| Child execution       | Platform/PostgreSQL  | Track one executable work unit sent to a worker.                            |
| Kafka command         | Platform             | Publish a worker-specific job payload.                                      |
| Worker processing     | Ingestor or Analyzer | Execute data-plane work.                                                    |
| Status event          | Worker               | Publish `topic-sync-job-status` with execution identity, metrics, and meta. |
| Aggregation           | Platform             | Update child status and roll up parent status when applicable.              |
| Notification policy   | Platform             | Resolve custom policy by job type, or use the default generic policy.       |
| Notification delivery | Platform             | Publish a notification event for template rendering and Telegram delivery.  |

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

| Contract               | Canonical doc/source                                                                                                                                                                                     |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Status topic           | [`topic-sync-job-status`](../data/kafka-contracts.md#topic-sync-job-status)                                                                                                                              |
| Job definitions        | [`database/migrations/V1__create_job_definitions_table.sql`](../../database/migrations/V1__create_job_definitions_table.sql)                                                                             |
| Job execution history  | [`database/migrations/V2__create_job_execution_histories_table.sql`](../../database/migrations/V2__create_job_execution_histories_table.sql)                                                             |
| Java messaging records | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging)                                                   |
| Java producers         | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/producers`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/producers)                                                   |
| Producer registry      | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducerRegistry.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducerRegistry.java) |
| Notification policies  | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/notifications`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/notifications)                                           |
| Java status consumer   | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java)     |

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
3. Keep producer logic execution-focused: prepare execution, build Kafka messages, publish, and run `postPublish()` if needed.
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
