# Analyzer Service

Analyzer is the Python analytical worker/API service for indicators, signals, signal evaluation, and Sector Wave calculations. Its Nx project name is `analyzer` and its source path is [`apps/analyzer`](.).

## Responsibility

Analyzer owns analytical jobs that read Parquet inputs, compute derived datasets, write Parquet outputs, and publish job status or notification events back to Platform.

## Owns

- Technical indicator calculation jobs.
- Market signal calculation and signal-history persistence.
- Signal evaluation jobs.
- Sector Wave symbol-feature, sector-feature, and sector-rotation backtest jobs.
- Analyzer HTTP endpoints for synchronous calculation entry points.
- Analyzer-side Kafka consumers/producers.

## Does Not Own

- Stock-price ingestion from external providers.
- Platform scheduler state or PostgreSQL migrations.
- Symbol/sector database projection ownership.
- Shared infrastructure abstractions that belong in [`libs/py-common`](../../libs/py-common).

## What this service DOES

- Reads EOD, indicator, signal, and feature Parquet datasets.
- Computes indicators, signals, sector features, rankings, and backtests.
- Writes analytical Parquet outputs.
- Publishes job status to `topic-sync-job-status`.
- Publishes signal notification events when signal transitions should be delivered.

## What this service DOES NOT do

- It does not write Platform PostgreSQL tables directly.
- It does not fetch raw provider EOD data.
- It does not own Kafka topic literals; topic names come from shared config.
- It does not hard-code object-store paths outside shared path builders.

## Entry Points

| Entry point                                                          | Purpose                             |
| -------------------------------------------------------------------- | ----------------------------------- |
| [`main.py`](main.py)                                                 | Service-level FastAPI entry point.  |
| [`app/main.py`](app/main.py)                                         | FastAPI app/router setup.           |
| [`app/indicators/kafka.py`](app/indicators/kafka.py)                 | Indicator Kafka worker lifecycle.   |
| [`app/signals/kafka.py`](app/signals/kafka.py)                       | Signal Kafka worker lifecycle.      |
| [`app/signals/evaluation_kafka.py`](app/signals/evaluation_kafka.py) | Signal evaluation Kafka lifecycle.  |
| [`app/sector_wave/kafka.py`](app/sector_wave/kafka.py)               | Sector Wave Kafka worker lifecycle. |

## Main Modules

| Module                                 | Purpose                                                          |
| -------------------------------------- | ---------------------------------------------------------------- |
| [`app/calculations`](app/calculations) | Core calculation utilities.                                      |
| [`app/indicators`](app/indicators)     | Indicator messages, handlers, Kafka integration.                 |
| [`app/signals`](app/signals)           | Signal messages, strategy, storage, handlers, evaluation.        |
| [`app/sector_wave`](app/sector_wave)   | Sector Wave messages, calculations, handlers, Kafka integration. |
| [`app/storage`](app/storage)           | Analyzer storage factory glue.                                   |
| [`app/adapters`](app/adapters)         | Infrastructure adapters.                                         |
| [`app/settings.py`](app/settings.py)   | Analyzer runtime settings and shared config access.              |

## Consumes

See [Kafka contracts](../../docs/data/kafka-contracts.md).

| Topic                              | Purpose                              |
| ---------------------------------- | ------------------------------------ |
| `topic-sync-indicators`            | Compute indicators.                  |
| `topic-sync-signals`               | Compute signals.                     |
| `topic-evaluate-signals`           | Evaluate signal outcomes.            |
| `topic-precompute-symbol-features` | Compute Sector Wave symbol features. |
| `topic-precompute-sector-features` | Compute Sector Wave sector features. |
| `topic-sector-rotation-backtest`   | Run Sector Wave backtests.           |

## Produces

See [Kafka contracts](../../docs/data/kafka-contracts.md).

| Topic                        | Purpose                                  |
| ---------------------------- | ---------------------------------------- |
| `topic-sync-job-status`      | Report analytical job status.            |
| `topic-signal-notifications` | Publish signal transition notifications. |

## Storage

Analyzer reads and writes analytical Parquet datasets. See [Data lake](../../docs/data/data-lake.md).

| Dataset                     | Role                                                           |
| --------------------------- | -------------------------------------------------------------- |
| `eod`                       | Input from Ingestor.                                           |
| `indicators`                | Output of indicator jobs; input to signal jobs.                |
| `signals`                   | Output of signal jobs; input to evaluation/notification paths. |
| `symbol-features`           | Output of symbol feature jobs; input to sector aggregation.    |
| `sector-features`           | Output of sector aggregation; input to ranking/backtests.      |
| `sector-rotation-backtests` | Output of backtest jobs.                                       |

## Important Flows

- [Indicator and signal](../../docs/flows/indicator-signal.md)
- [Sector wave](../../docs/flows/sector-wave.md)
- [Job execution](../../docs/flows/job-execution.md)

## Run locally

Inspect targets first:

```bash
nx show project analyzer
```

Run the API service:

```bash
nx run analyzer:serve
```

Run with hot reload when developing manually:

```bash
nx run analyzer:serve-hmr
```

## Test

```bash
nx run analyzer:test
nx run analyzer:lint
nx run analyzer:format
```

## Shared Contracts

| Contract                             | Source                                                               |
| ------------------------------------ | -------------------------------------------------------------------- |
| Kafka topics                         | [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml)     |
| S3 paths                             | [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml) |
| Shared Python runtime/config/storage | [`libs/py-common`](../../libs/py-common)                             |
