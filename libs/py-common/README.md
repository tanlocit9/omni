# py-common Library

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

py-common does not own datasets. It provides storage abstractions used by dataset owners. See [Data lake](../../docs/data/data-lake.md).

## Important Flows

py-common supports these flows but does not own their business logic:

- [Stock sync](../../docs/flows/stock-sync.md)
- [Indicator and signal](../../docs/flows/indicator-signal.md)
- [Sector wave](../../docs/flows/sector-wave.md)

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

## Design Principles

- Shared abstractions belong here only when they are genuinely reusable across Python services.
- Keep service-specific business logic in the owning service.
- Keep topic names and path patterns centralized in shared config files.
- Prefer typed validation and fail-fast errors at contract boundaries.
