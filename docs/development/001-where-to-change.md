# Where to Change

Use this guide after reading [System overview](../architecture/001-system-overview.md). It maps common product or architecture changes to the first place to inspect.

## Quick Navigation

| Change                          | Start here                                                                                                                                                                 | Also check                                                                                                                                                                                                                                                                                                          |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Data provider integration       | [`apps/ingestor/app/stocks`](../../apps/ingestor/app/stocks)                                                                                                               | [`apps/ingestor/app/handlers`](../../apps/ingestor/app/handlers), [Data lake](../data/002-data-lake.md)                                                                                                                                                                                                             |
| Stock price ingestion           | [`apps/ingestor/app/handlers/stock_prices.py`](../../apps/ingestor/app/handlers/stock_prices.py)                                                                           | [`topic-sync-stock-prices`](../data/001-kafka-contracts.md#topic-sync-stock-prices), [`eod`](../data/002-data-lake.md#eod)                                                                                                                                                                                          |
| Symbol ingestion                | [`apps/ingestor/app/handlers/symbols.py`](../../apps/ingestor/app/handlers/symbols.py)                                                                                     | [`topic-sync-symbols`](../data/001-kafka-contracts.md#topic-sync-symbols), [`topic-upsert-symbols`](../data/001-kafka-contracts.md#topic-upsert-symbols)                                                                                                                                                            |
| Indicator calculation           | [`apps/analyzer/app/indicators`](../../apps/analyzer/app/indicators), [`apps/analyzer/app/calculations/indicators.py`](../../apps/analyzer/app/calculations/indicators.py) | [Indicator/signal flow](../flows/003-indicator-signal.md), [`indicators`](../data/002-data-lake.md#indicators)                                                                                                                                                                                                      |
| Signal rule                     | [`apps/analyzer/app/signals`](../../apps/analyzer/app/signals)                                                                                                             | [Indicator/signal flow](../flows/003-indicator-signal.md), [`signals`](../data/002-data-lake.md#signals)                                                                                                                                                                                                            |
| Sector Wave model               | [`apps/analyzer/app/sector_wave`](../../apps/analyzer/app/sector_wave)                                                                                                     | [Sector wave flow](../flows/004-sector-wave.md), [`features/symbol`](../data/002-data-lake.md#symbol-features), [`features/sector`](../data/002-data-lake.md#sector-features)                                                                                                                                       |
| Sector Transition research      | [`apps/analyzer/app/sector_transition`](../../apps/analyzer/app/sector_transition)                                                                                         | [Sector wave deferred research](../flows/004-sector-wave.md#deferred-research-sector-transition-and-recommendation), [`topic-sector-transition-analyze`](../data/001-kafka-contracts.md#topic-sector-transition-analyze), [`sector-transition-predictions`](../data/002-data-lake.md#sector-transition-predictions) |
| Scheduler or job orchestration  | [`apps/core/src/main/java/com/omni/platform/modules/scheduler`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler)                                         | [Job execution flow](../flows/001-job-execution.md), [`JobProducerRegistry`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducerRegistry.java), [Database](../data/003-database.md)                                                                                             |
| Kafka topic or payload contract | Producer + consumer + [`libs/py-common/py_common/messaging`](../../libs/py-common/py_common/messaging) + [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml)  | [Kafka contracts](../data/001-kafka-contracts.md), status metadata preservation, tests on both sides                                                                                                                                                                                                                |
| Notification policy             | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/notifications`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/notifications)             | [Job execution flow](../flows/001-job-execution.md), notification templates, job service tests, policy registry tests                                                                                                                                                                                               |
| S3 path or dataset layout       | [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml) + path builders in [`libs/py-common/py_common/config`](../../libs/py-common/py_common/config)         | [Data lake](../data/002-data-lake.md), all producers and consumers of the dataset                                                                                                                                                                                                                                   |
| Shared Python abstraction       | [`libs/py-common`](../../libs/py-common)                                                                                                                                   | Analyzer and Ingestor imports/tests                                                                                                                                                                                                                                                                                 |
| Database schema                 | [`database/migrations`](../../database/migrations) + owning Platform module                                                                                                | [Database](../data/003-database.md), Platform tests                                                                                                                                                                                                                                                                 |
| Local runtime command           | Relevant [`project.json`](../../apps/analyzer/project.json)                                                                                                                | Use Nx targets only                                                                                                                                                                                                                                                                                                 |

## Decision Tree

```mermaid
flowchart TD
  Change["What are you changing?"]
  Provider["External data provider or raw data ingestion"]
  Analytics["Analytical calculation"]
  Orchestration["Scheduler, job state, Platform API"]
  Notification["Job notification behavior"]
  Contract["Kafka payload/topic or S3 path"]
  Shared["Reusable Python infrastructure"]
  DB["Platform database schema"]

  Change --> Provider
  Change --> Analytics
  Change --> Orchestration
  Change --> Notification
  Change --> Contract
  Change --> Shared
  Change --> DB

  Provider --> Ingestor["Start in apps/ingestor"]
  Analytics --> Analyzer["Start in apps/analyzer"]
  Orchestration --> Platform["Start in apps/core scheduler module"]
  Notification --> Policy["Start in scheduler notifications policies"]
  Contract --> Both["Update producer + consumer + tests + docs"]
  Shared --> Common["Start in libs/py-common"]
  DB --> Migration["Add database/migrations/V<N>__*.sql"]
```

## Adding a Job Type

1. Add or reuse a `JobDefinition.JobType` value in Platform.
2. Add a `JobProducer` that returns the value from `getJobType()`.
3. Do not edit `JobScheduler` dispatch for the new type; `JobProducerRegistry` resolves the registered producer.
4. Add producer and registry tests, plus worker consumer tests for the Kafka payload.
5. Update [Job execution flow](../flows/001-job-execution.md) and [Kafka contracts](../data/001-kafka-contracts.md) when payload semantics change.

## Adding a Notification Policy

Use the default policy for generic job success/failure notifications. Add a custom `JobNotificationPolicy` only when a job type needs domain-specific wording, metadata rendering, or event type selection. Keep notification decisions in the policy layer, not in `JobService`, `JobScheduler`, or producers.

## Kafka Contract Rule

Do not update a Kafka producer or consumer in isolation. When changing a topic, event schema, field semantics, serialization, validation, or error-handling contract:

1. Find the producer.
2. Find the consumer.
3. Update shared payload/config abstractions if used.
4. Update tests for both sides.
5. Update [Kafka contracts](../data/001-kafka-contracts.md).
6. Check impact radius before merging.

## Storage Contract Rule

Do not add bucket names or object names to Kafka job messages for routing. Workers derive object paths from shared path builders backed by [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml).

When changing storage layout:

1. Update [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml).
2. Update path builders and tests.
3. Update all producers and consumers of that dataset.
4. Update [Data lake](../data/002-data-lake.md).

## Verification

Use Nx targets from the workspace root. Inspect a project before running unfamiliar operations:

```bash
nx show project analyzer
nx show project ingestor
nx show project platform
nx show project py-common
```

Common checks:

```bash
nx run analyzer:test
nx run analyzer:lint
nx run ingestor:test
nx run ingestor:lint
nx run py-common:test
nx run py-common:lint
nx run platform:build
```
