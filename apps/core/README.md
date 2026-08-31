# Platform / Core Service

Platform is the Java/Spring Boot control-plane service for Omni. Its Nx project name is `platform` and its source path is [`apps/core`](.).

## Responsibility

Platform owns API boundaries, scheduler orchestration, job definitions, execution history, Platform PostgreSQL state, Kafka job production, and Kafka status/upsert consumption.

## Owns

- Scheduler job definitions and execution history.
- Parent/child job aggregation.
- Platform PostgreSQL migrations and entities.
- Kafka job producers for ingestion and analytical workers.
- Kafka consumers for worker status, symbol upserts, sector upserts, and notifications.
- Platform-facing API and operational state.
- Private job catalog, manual-trigger, audit, and execution-status API.

## Does Not Own

- External stock data fetching.
- Indicator, signal, or Sector Wave calculation logic.
- Parquet analytical dataset implementation.
- S3 object path routing inside Kafka messages.

## What this service DOES

- Creates and schedules jobs.
- Publishes job commands to Kafka.
- Tracks child and parent execution status.
- Stores operational state in PostgreSQL.
- Consumes worker status and projection events.

## What this service DOES NOT do

- It does not fetch provider data directly.
- It does not compute analytical datasets.
- It does not write Analyzer/Ingestor Parquet outputs.
- It does not put bucket or object path fields in worker-routing messages.

## Entry Points

| Entry point                                                                                                                                                                                | Purpose                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------ |
| [`src/main/java/com/omni/platform/PlatformApplication.java`](src/main/java/com/omni/platform/PlatformApplication.java)                                                                     | Spring Boot application entry point. |
| [`src/main/java/com/omni/platform/modules/scheduler/SyncJobScheduler.java`](src/main/java/com/omni/platform/modules/scheduler/SyncJobScheduler.java)                                       | Scheduled job trigger.               |
| [`src/main/java/com/omni/platform/modules/scheduler/services/JobService.java`](src/main/java/com/omni/platform/modules/scheduler/services/JobService.java)                                 | Job orchestration service.           |
| [`src/main/java/com/omni/platform/modules/scheduler/controllers/JobOperationsController.java`](src/main/java/com/omni/platform/modules/scheduler/controllers/JobOperationsController.java) | Private job operations HTTP API.     |

## Private job operations API

`/api/v1/jobs` exposes a redacted catalog, definition detail, manual trigger,
trigger status, and execution status to Omni Console. The trusted reverse proxy
must replace `X-Omni-User` with the authenticated operator identity. The browser
must not manufacture this header.

Manual execution is disabled unless
`APP_SCHEDULER_MANUAL_TRIGGER_ALLOW_LIST` explicitly lists a definition UUID or
`JOB_TYPE:SOURCE`. Accepted requests reuse scheduler claims, dependency checks,
registered producers, and the transactional outbox. They preserve the cron
`nextRun`. Runtime parameters, force/bypass, cancellation, direct Kafka access,
secrets, and physical paths are not part of this contract.

## Main Modules

| Module                                                                                                           | Purpose                                                                          |
| ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| [`src/main/java/com/omni/platform/modules/scheduler`](src/main/java/com/omni/platform/modules/scheduler)         | Job definitions, execution history, Kafka producers/consumers, symbols, sectors. |
| [`src/main/java/com/omni/platform/modules/notifications`](src/main/java/com/omni/platform/modules/notifications) | Notification handling.                                                           |
| [`src/main/java/com/omni/platform/modules/storages`](src/main/java/com/omni/platform/modules/storages)           | Platform storage integration.                                                    |
| [`src/main/java/com/omni/platform/shared`](src/main/java/com/omni/platform/shared)                               | Shared Java entities, ports, repositories, utilities.                            |

## Consumes

See [Kafka contracts](../../docs/data/001-kafka-contracts.md).

| Topic                        | Purpose                                        |
| ---------------------------- | ---------------------------------------------- |
| `topic-sync-job-status`      | Worker status updates.                         |
| `topic-upsert-symbols`       | Symbol projection updates from Ingestor.       |
| `topic-upsert-sectors`       | Sector projection updates from Ingestor.       |
| `topic-signal-notifications` | Signal transition notifications from Analyzer. |

## Produces

See [Kafka contracts](../../docs/data/001-kafka-contracts.md).

| Topic                              | Purpose                              |
| ---------------------------------- | ------------------------------------ |
| `topic-sync-stock-prices`          | Request EOD price sync.              |
| `topic-sync-symbols`               | Request symbol sync.                 |
| `topic-sync-indicators`            | Request indicator calculation.       |
| `topic-sync-signals`               | Request signal calculation.          |
| `topic-evaluate-signals`           | Request signal evaluation.           |
| `topic-precompute-symbol-features` | Request Sector Wave symbol features. |
| `topic-precompute-sector-features` | Request Sector Wave sector features. |
| `topic-sector-rotation-backtest`   | Request sector rotation backtest.    |

## Storage

| Storage    | Purpose                                                                                                                                                       |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PostgreSQL | Platform operational state. See [Database](../../docs/data/003-database.md).                                                                                  |
| MinIO/S3   | Platform may integrate with object storage, but analytical dataset ownership belongs to Ingestor/Analyzer. See [Data lake](../../docs/data/002-data-lake.md). |

## Important Flows

- [Job execution](../../docs/flows/001-job-execution.md)
- [Stock sync](../../docs/flows/002-stock-sync.md)
- [Indicator and signal](../../docs/flows/003-indicator-signal.md)
- [Sector wave](../../docs/flows/004-sector-wave.md)

## Run locally

Inspect targets first:

```bash
nx show project platform
```

Run with the dev profile:

```bash
nx run platform:serve
```

## Test

```bash
nx run platform:build
```

Use a dedicated test target if one is added to [`project.json`](project.json).
