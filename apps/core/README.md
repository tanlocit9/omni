# Platform / Core Service

Platform is the Java/Spring Boot control-plane service for Omni. Its Nx project name is `platform` and its source path is [`apps/core`](.).

## Responsibility

Platform owns API boundaries, scheduler orchestration, Platform-local dependency evaluation, job definitions, execution/outbox history, Platform PostgreSQL state, Kafka job production, and Kafka status/upsert consumption.

Static topology declaration is Platform-local: `StaticJobTopologyProvider` exposes typed definition-level dependencies from `StaticDefinitionDependencies`; `RequiredPathResolver` consumes the same declaration. I3.a pre-cutover retains seed JSON for catalog compatibility/parity, pending consumer-removal gates; unused topology adapters are removed and JSON baseline/custom-fixture parsing is test-only. This checkpoint adds no JobRun persistence or runtime planner admission. See [Plan 030](../../docs/plans/030-static-graph-dispatch-planner.md#i3a-pre-cutover-source--2026-10-17).

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
`JOB_TYPE:SOURCE`. Accepted requests reuse scheduler claims, registered producers,
and the transactional outbox. They commit stable execution and PENDING outbox
identity before the dispatcher evaluates dependencies. They preserve the cron
`nextRun`. Runtime parameters, force/bypass, cancellation, direct Kafka access,
secrets, and physical paths are not part of this contract.

## Main Modules

| Module                                                                                                           | Purpose                                                                                                                                                |
| ---------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [`src/main/java/com/omni/platform/modules/scheduler`](src/main/java/com/omni/platform/modules/scheduler)         | Job definitions, dependency evaluation and READY/WAITING/BLOCKED outbox policy, execution/outbox history, Kafka producers/consumers, symbols, sectors. |
| [`src/main/java/com/omni/platform/modules/notifications`](src/main/java/com/omni/platform/modules/notifications) | Notification handling.                                                                                                                                 |
| [`src/main/java/com/omni/platform/modules/storages`](src/main/java/com/omni/platform/modules/storages)           | Platform storage integration.                                                                                                                          |
| [`src/main/java/com/omni/platform/shared`](src/main/java/com/omni/platform/shared)                               | Shared Java entities, ports, repositories, utilities.                                                                                                  |

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

## Telegram Notification Filtering

Platform consumes signal notification events from Analyzer and routes them to Telegram. Comprehensive filters control which notifications are sent:

### Filter Configuration

Configure filters via environment variables or [`application.yaml`](src/main/resources/application.yaml):

```yaml
app:
  notifications:
    telegram:
      signal-filter:
        allowed-strategies: CONFIRMED_TREND_EQUALS,TREND_MOMENTUM_V1
        allowed-symbols: HOSE-FPT,HOSE-VNM
        symbol-patterns: HOSE-*,HNX-*
        allowed-directions: BUY,SELL
        allowed-timeframes: 1d
        min-score: 0.7
        enable-new-signal-date: true
```

### Available Filters

| Filter                   | Purpose                               | Example                                    | Default                  |
| ------------------------ | ------------------------------------- | ------------------------------------------ | ------------------------ |
| `allowed-strategies`     | Filter by strategy                    | `CONFIRMED_TREND_EQUALS,TREND_MOMENTUM_V1` | `CONFIRMED_TREND_EQUALS` |
| `allowed-symbols`        | Exact symbol match                    | `HOSE-FPT,HOSE-VNM,HNX-ACB`                | Empty (all symbols)      |
| `symbol-patterns`        | Glob pattern match                    | `HOSE-*,HNX-*`                             | Empty (no patterns)      |
| `allowed-directions`     | Filter by signal                      | `BUY,SELL` (excludes HOLD)                 | Empty (all directions)   |
| `allowed-timeframes`     | Filter by timeframe                   | `1d,4h`                                    | Empty (all timeframes)   |
| `min-score`              | Minimum score threshold               | `0.7` (only >= 0.7)                        | Empty (no threshold)     |
| `enable-new-signal-date` | Include daily confirmed first-persist | `true` or `false`                          | `true`                   |

### Filter Behavior

- **Empty list = allow all**: An empty `allowed-symbols` or `allowed-directions` matches everything
- **Case insensitive**: Strategies/directions normalized to uppercase, timeframes to lowercase
- **Glob patterns**: Use `*` for any characters, `?` for single character
  - `HOSE-*` matches `HOSE-FPT`, `HOSE-VNM`, etc.
  - `H*-F*` matches `HOSE-FPT`, `HNX-FLC`, etc.
- **Early filtering**: Applied at Kafka consumption before rendering
- **Debug logging**: Filtered notifications logged at DEBUG level

### Example Configurations

**BUY signals only from HOSE stocks with high confidence:**

```bash
TELEGRAM_SIGNAL_FILTER_SYMBOL_PATTERNS=HOSE-*
TELEGRAM_SIGNAL_FILTER_ALLOWED_DIRECTIONS=BUY
TELEGRAM_SIGNAL_FILTER_MIN_SCORE=0.7
```

**Specific symbols across multiple strategies:**

```bash
TELEGRAM_SIGNAL_FILTER_ALLOWED_STRATEGIES=CONFIRMED_TREND_EQUALS,TREND_MOMENTUM_V1,ICHIMOKU_V1
TELEGRAM_SIGNAL_FILTER_ALLOWED_SYMBOLS=HOSE-FPT,HOSE-VNM,HOSE-HPG
```

**Daily timeframe only:**

```bash
TELEGRAM_SIGNAL_FILTER_ALLOWED_TIMEFRAMES=1d
```

See [`.env.example`](../../.env.example) for complete environment variable documentation.

## Durable notification delivery

Immediate signal notifications are committed to
`notification_outbox_messages` before the Kafka listener returns. Manual
`POST /api/v1/notifications/manual/signal` requests also insert a durable row and
return `202 ACCEPTED` with the delivery identity; they do not claim synchronous
Telegram delivery. Manual latest-signal requests ask Analyzer to publish the existing
Kafka notification and rely on the same Platform consumer for one durable enqueue.
There is no direct async signal event listener; operational events remain on their
existing incremental-migration path. A separate leased
dispatcher resolves Telegram credentials and logical-channel destinations only in
memory, enforces a database-coordinated provider rate gate, honors `Retry-After`, and
moves permanent or exhausted failures to visible `DEAD` state. Parent signal digests
are enqueued inside the guarded parent terminal transaction and use deterministic
`<parentExecutionId>:<page>` deduplication keys; there is no parallel
`AFTER_COMMIT` digest sender.

Configure polling, claims, attempts, retry bounds, and provider pacing with the
`NOTIFICATION_OUTBOX_*` and `TELEGRAM_RATE_LIMIT` variables in
[`.env.example`](../../.env.example). Actuator exposes pending/dead current counts,
oldest-pending age, sent/retry/dead/rate-limited counters, and delivery duration.
Payloads, bot tokens, and concrete chat identifiers are not metric labels or logs.
`DEAD` delivery is terminal; this increment intentionally provides no manual replay.

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

## Format

```bash
# Changed Platform Java files only
nx run platform:format
# Changed files across the workspace, including Platform Java
nx run omni:format
```

The Java target uses pinned google-java-format 1.29.0 with the existing JDK 21
toolchain, AOSP 4-space indentation and an isolated build-tool configuration.
Imports, string literals and Javadoc are preserved. It respects changed-file
selection and ignore rules; no compilation, tests or runtime dependency is added.
See [formatting scope](../../docs/development/001-where-to-change.md#formatting-scope).

## Test

Run the complete test suite or the build (which also runs tests):

```bash
nx run platform:test
nx run platform:build
```

The suite includes PostgreSQL Testcontainers tests and requires a working Docker
runtime. For the isolated application startup/restart regression:

```bash
nx run platform:test --args="--tests com.omni.platform.PlatformStartupPostgresTest"
```

This test boots the actual application twice on an ephemeral PostgreSQL database,
runs Flyway and seeding, checks HTTP readiness and typed dependency parity, and
preserves definition IDs/runtime fields across restart. Scheduled work, Kafka
listeners and external storage/notification calls are disabled in test-only wiring.
It does not test the production Docker image or live broker/object-storage behavior.
The owner-approved focused run passed on 2026-10-18: 1 test, zero failures/errors/skips.
The same startup test passed again after removing unused adapters; see [cleanup evidence](../../docs/plans/030-static-graph-dispatch-planner.md#unused-compatibility-adapter-cleanup--2026-10-18).
This does not close the full I3.a coverage/regression/build gate; see [Plan 030 evidence](../../docs/plans/030-static-graph-dispatch-planner.md#i3a-testcontainers-startup-regression--2026-10-18).
Commands require the verification approval specified in the repository AGENTS.md.

The affected topology/planning/seed and Telegram/notification unit tests also passed
on 2026-10-18: 109 tests across 13 classes, zero failures/errors/skips. This was a
filtered Platform test run, not the full suite or an attributable coverage/build pass.

The later unfiltered `platform:test` run passed 293 tests across 50 suites with
zero failures/errors/skips (`WINDOWS-HIDDEN-CONSOLES-20261018`). It generated
JaCoCo reports but does not close the separate I3.a coverage/build gate. Three
cached-context PostgreSQL shutdown timeouts remain a test-teardown follow-up.
