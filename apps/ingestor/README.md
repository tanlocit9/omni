# Ingestor Service

Ingestor is the Python worker service for external market-data ingestion and raw/normalized Parquet dataset maintenance. Its Nx project name is `ingestor` and its source path is [`apps/ingestor`](.).

## Responsibility

Ingestor consumes Platform sync jobs, fetches external provider data, normalizes records, updates data-lake Parquet files, and publishes job status plus symbol/sector upsert events.

## Owns

- External stock data provider clients.
- Symbol and EOD ingestion handlers.
- Provider data normalization.
- Raw/normalized Parquet updates for `symbols` and `eod` datasets.
- Ingestor-side Kafka consumer/producer behavior.

## Does Not Own

- Platform scheduler state or PostgreSQL migrations.
- Indicator, signal, or Sector Wave analytical calculations.
- Platform notification delivery.
- Shared Python abstractions that belong in [`libs/py-common`](../../libs/py-common).

## What this service DOES

- Consumes stock-price and symbol sync Kafka jobs.
- Fetches market data from configured provider clients.
- Derives S3 paths from shared path builders.
- Reads, merges, deduplicates, and writes Parquet files.
- Publishes job status and metadata upsert events.

## What this service DOES NOT do

- It does not compute analytical outputs.
- It does not mutate Platform database tables directly.
- It does not accept bucket/object path routing fields in Kafka job messages.
- It does not own Kafka topic names outside shared config.

## Entry Points

| Entry point                                                    | Purpose                       |
| -------------------------------------------------------------- | ----------------------------- |
| [`main.py`](main.py)                                           | Service entry point.          |
| [`app/kafka_consumer.py`](app/kafka_consumer.py)               | Kafka consumer orchestration. |
| [`app/handlers/stock_prices.py`](app/handlers/stock_prices.py) | Stock-price sync handler.     |
| [`app/handlers/symbols.py`](app/handlers/symbols.py)           | Symbol/sector sync handler.   |

## Main Modules

| Module                               | Purpose                                                  |
| ------------------------------------ | -------------------------------------------------------- |
| [`app/handlers`](app/handlers)       | Business handlers for sync jobs.                         |
| [`app/stocks`](app/stocks)           | Provider clients, registry, normalization, sector cache. |
| [`app/messaging`](app/messaging)     | Ingestor messaging contracts/status helpers.             |
| [`app/storage`](app/storage)         | Storage integration glue.                                |
| [`app/settings.py`](app/settings.py) | Ingestor runtime settings and shared config access.      |

## Consumes

See [Kafka contracts](../../docs/data/kafka-contracts.md).

| Topic                     | Purpose                       |
| ------------------------- | ----------------------------- |
| `topic-sync-stock-prices` | Request EOD stock-price sync. |
| `topic-sync-symbols`      | Request symbol metadata sync. |

## Produces

See [Kafka contracts](../../docs/data/kafka-contracts.md).

| Topic                   | Purpose                              |
| ----------------------- | ------------------------------------ |
| `topic-sync-job-status` | Report sync job outcome.             |
| `topic-upsert-symbols`  | Project symbol snapshot to Platform. |
| `topic-upsert-sectors`  | Project sector snapshot to Platform. |

## Storage

Ingestor owns these Parquet datasets. See [Data lake](../../docs/data/data-lake.md).

| Dataset   | Role                                      |
| --------- | ----------------------------------------- |
| `symbols` | Symbol metadata by exchange.              |
| `eod`     | EOD price history by exchange and symbol. |

## Important Flows

- [Stock sync](../../docs/flows/stock-sync.md)
- [Job execution](../../docs/flows/job-execution.md)

## Run locally

Inspect targets first:

```bash
nx show project ingestor
```

Run the service:

```bash
nx run ingestor:serve
```

Run with hot reload when developing manually:

```bash
nx run ingestor:serve-hmr
```

## Test

```bash
nx run ingestor:test
nx run ingestor:lint
nx run ingestor:format
```

## Shared Contracts

| Contract                             | Source                                                               |
| ------------------------------------ | -------------------------------------------------------------------- |
| Kafka topics                         | [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml)     |
| S3 paths                             | [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml) |
| Shared Python runtime/config/storage | [`libs/py-common`](../../libs/py-common)                             |
