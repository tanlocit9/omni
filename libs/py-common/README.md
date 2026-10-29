# py-common Library

Execution input context uses `messaging.approved_inputs.approved_input_version`.
`ParquetStorage.read_bound_dataframe` verifies a canonical version against the same
bytes decoded. These helpers preserve work identity and dataset paths; they do not
provide a writer service, historical object retention or all-writer exclusion.

Current P14 scheduling reconciliation (2026-10-29): I8 and I9 are now explicitly
registered pending after completed I7, with I9.a/b/c internal checkpoints.
Reuse atomic work/execution envelope, accepted parameter snapshots, validation
and storage/status helpers. Result + work identity reaches writer through a
future durable operation intent; output writeKey and deterministic intentId stay
separate. Writer does not resolve graph or expand; actual handoff/offset loops
remain deferred P12 work. This is no implementation or readiness/version-safety PASS.
See [current handoff](../../docs/plans/030-static-graph-dispatch-planner.md#current-i8i9-handoff--2026-10-29).

## I9 checkpoint and result-handoff alignment — 2026-10-28

P14-I9 is split into sequential internal checkpoints: I9.a graph-driven expansion,
I9.b shared ancestor work, I9.c definition consolidation with history/offset/
migration/diagnostics/rollback evidence. These are not new registry IDs and do not
change I9 status or activate P12.

Future consumers send computed result plus unchanged atomic work identity to the
writer: JobType, typed workKey, businessDate, canonical effective parameters and
execution correlation. Add operation, logical output/row identity, deterministic
intentId and result or a validated durable logical candidate reference. Keep
physical paths out of business routing and retain execution attribution.

Writer validates the intent, derives output writeKey and buffers only compatible
operations; it does not resolve static graph or expand work. One work can emit
multiple operations/outputs; several works or JobTypes can share a writeKey.
Conflicting results under one intentId require explicit rejection/conflict handling.
All required outputs must be durable before individual terminal SUCCESS.

Current terminal-status commit loop is not the future durable-intent handoff loop.
Preserve source-after-intent and writer-after-output/status commit boundaries.
No implementation, new test/coverage PASS, version-binding solution or default
activation is claimed. See [I9 checkpoints and handoff](../../docs/plans/030-static-graph-dispatch-planner.md#i9-checkpoints-and-writer-result-handoff--2026-10-28).

P14 planned scope revision (2026-10-28): I8 — Unified work expansion first;
then I9 — Graph-driven expansion and definition consolidation. Both are pending.
Existing definitions remain the sole preset/configuration source. Full cron
removal and definition-free runtime are outside scope; no second catalog.
Preparation stays disabled. This describes plans, not implemented behavior.
Reuse validation/storage/path/status/lifecycle helpers. Terminal-status commit loop cannot be reused unchanged for future durable-intent handoff.
See [scope, impact and writer reuse](../../docs/plans/030-static-graph-dispatch-planner.md#i8i9-expansion-first-scope-revision--2026-10-28).

py-common is the shared Python library for Omni worker services. Its Nx project name is `py-common` and its source path is [`libs/py-common`](.).

## Responsibility

py-common owns reusable Python infrastructure that is shared by Analyzer and Ingestor: configuration loading, typed runtime settings, Kafka helpers, messaging payload foundations, runtime helpers, storage ports/adapters, and Parquet utilities.

## Owns

- Shared Python configuration loading from [`configs/shared`](../../configs/shared).
- Shared Kafka helper factories and messaging abstractions.
- Shared storage ports, provider registry, MinIO adapter, and Parquet storage helper.
- Runtime helpers for worker/API startup patterns.
- Cross-service Python constants and path builders.

## Does Not Own

- Analyzer-specific business calculations.
- Ingestor-specific provider clients or normalization logic.
- Platform Java records/entities.
- Service-specific Kafka handling decisions that are not reusable.

## What this library DOES

- Loads shared topic and S3 path config.
- Maps flat environment variables into typed settings.
- Provides reusable object-storage abstractions.
- Provides Parquet read/write helpers.
- Provides Kafka producer/consumer construction helpers.

## What this library DOES NOT do

- It does not run a service by itself.
- It does not define service-specific job orchestration.
- It does not make business decisions for indicators, signals, sector wave, or ingestion.
- It does not hard-code cloud credentials or provider-specific business rules.

## Entry Points

| Entry point                                  | Purpose                                                           |
| -------------------------------------------- | ----------------------------------------------------------------- |
| [`py_common/config`](py_common/config)       | Shared config loading, settings models, path builders, constants. |
| [`py_common/kafka`](py_common/kafka)         | Kafka helper factories and job-status service helpers.            |
| [`py_common/messaging`](py_common/messaging) | Shared messaging payload/publisher foundations.                   |
| [`py_common/runtime`](py_common/runtime)     | Worker/API runtime helpers.                                       |
| [`py_common/storage`](py_common/storage)     | Storage ports, adapters, registry, and Parquet utilities.         |

## Main Modules

| Module                                                                       | Purpose                         |
| ---------------------------------------------------------------------------- | ------------------------------- |
| [`py_common/config/loader.py`](py_common/config/loader.py)                   | YAML config loading.            |
| [`py_common/config/models.py`](py_common/config/models.py)                   | Typed settings models.          |
| [`py_common/config/paths.py`](py_common/config/paths.py)                     | S3 path construction.           |
| [`py_common/kafka/factory.py`](py_common/kafka/factory.py)                   | Kafka client factory helpers.   |
| [`py_common/messaging/job_messages.py`](py_common/messaging/job_messages.py) | Shared job message foundations. |
| [`py_common/storage/parquet.py`](py_common/storage/parquet.py)               | Parquet storage helper.         |
| [`py_common/storage/adapters/minio.py`](py_common/storage/adapters/minio.py) | MinIO/S3-compatible adapter.    |

## Consumes

| Input                                                                | Purpose                                                       |
| -------------------------------------------------------------------- | ------------------------------------------------------------- |
| [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml)     | Kafka topic defaults.                                         |
| [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml) | S3 path patterns.                                             |
| Flat environment variables                                           | Runtime overrides shared by Java, Python, and Docker Compose. |

## Produces

py-common does not publish Kafka events directly as a standalone service. It provides reusable helpers that Analyzer and Ingestor use to produce or consume events.

## Storage

py-common does not own datasets. It provides storage abstractions used by dataset owners. See [Data lake](../../docs/data/002-data-lake.md).

## Important Flows

py-common supports these flows but does not own their business logic:

- [Stock sync](../../docs/flows/002-stock-sync.md)
- [Indicator and signal](../../docs/flows/003-indicator-signal.md)
- [Sector wave](../../docs/flows/004-sector-wave.md)

## Run locally

Inspect targets first:

```bash
nx show project py-common
```

Synchronize dependencies:

```bash
nx run py-common:sync
```

## Test

```bash
nx run py-common:test
nx run py-common:lint
nx run py-common:format
```

Real Kafka offset/restart tests are opt-in:

```bash
nx run py-common:test-integration
```

This target requires Docker and the locally available
`confluentinc/cp-kafka:7.6.0` image. It creates and removes its own disposable broker;
it does not use the developer's configured broker. Shared consumer factories
disable auto-commit; consumers must use the
[acknowledged-status commit contract](../../docs/data/001-kafka-contracts.md#python-command-offset-ownership).
The normal test target skips these Docker integration cases.

The four cases cover crash/commit boundaries and a broker pause during terminal
status publication. The outage case checks automatic same-group recovery of the
uncommitted command and HTTP 503 during recovery, recorded in JUnit properties.
Shared `WorkerSupervisor` retries cleaned-up sessions with 5–60 second backoff;
shutdown cancellation stops retries. Poison records are not skipped or committed.
See the [current recovery and entry policy](../../docs/deployment/004-job-run-admission-rollout.md#worker-recovery-and-terminal-entries--2026-10-26).

## Design Principles

- Shared abstractions belong here only when they are genuinely reusable across Python services.
- Keep service-specific business logic in the owning service.
- Keep topic names and path patterns centralized in shared config files.
- Prefer typed validation and fail-fast errors at contract boundaries.
