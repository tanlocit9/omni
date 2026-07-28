# Omni Architecture

This document captures the detailed architecture for the Omni monorepo. For setup and daily commands, see [README.md](README.md).

---

## System Architecture & Services

Omni is an Nx monorepo for stock analytics and data integration. It contains three primary services plus shared configuration, database migrations, and local infrastructure.

```text
omni/
├── apps/
│   ├── core/          # Java 21 / Spring Boot 4 — Platform API, scheduler, orchestration
│   ├── analyzer/      # Python 3.14 / FastAPI — Analytical API boundary
│   └── ingestor/      # Python 3.14 / Async Event Worker — Parquet Data Lake Sync
├── configs/shared/    # Shared Kafka topic and S3 path configuration
├── database/
│   └── migrations/    # Flyway SQL migrations
├── externals/
│   └── vnstock-etl/   # Git submodule — ETL pipeline for stock data
├── docker-compose.yaml
├── docker-compose.infra.yaml
├── docker-compose.services.yaml
├── nx.json
├── package.json
└── project.json
```

---

## Service Responsibilities

### Platform Core API (`apps/core`)

- **Stack**: Java 21, Spring Boot 4.0.1, Spring Modulith, Spring Data JPA, Hibernate, PostgreSQL, Flyway, MinIO client, Kafka.
- **Role**: Central command orchestrator.
- **Owns**:
  - user accounts;
  - portfolio tracking;
  - screener alerts;
  - scheduler job definitions;
  - parent and child execution tracking;
  - Kafka request production;
  - Kafka status consumption.
- **Pattern**: Clean Ports & Adapters. Storage providers are resolved at runtime through `StorageProviderRegistry`.
- **Database**: SQL migrations are applied by Flyway on Platform startup.

### Stock Ingestor Service (`apps/ingestor`)

- **Stack**: Python 3.14, `aiokafka`, MinIO/S3-compatible storage, `pandas`, `pyarrow`.
- **Role**: Async event-driven worker for stock data synchronization.
- **Owns**:
  - consuming stock synchronization commands;
  - fetching external market data;
  - deriving object paths from shared path builders;
  - merging and deduplicating Parquet snapshots;
  - writing Parquet files to MinIO/S3;
  - publishing processing status and symbol snapshots.
- **Pattern**: Ports & Adapters for Kafka and stock clients. Stock clients are resolved through a registry, with VNDirect/VCI-style providers and mock clients available.
- **Concurrency**: Uses bounded async concurrency through `asyncio.Semaphore` and `MAX_CONCURRENT_TASKS`.

### Stock Analyzer API (`apps/analyzer`)

- **Stack**: Python 3.14, FastAPI, Pydantic settings, Kafka and MinIO infrastructure adapters.
- **Role**: Analytical API boundary.
- **Current maturity**:
  - compatibility stock endpoints are available;
  - analyzer does not own Platform scheduler jobs;
  - analyzer does not persist stock prices directly to PostgreSQL;
  - analyzer must not bypass Platform-owned orchestration or Ingestor-owned Parquet writes.

---

## End-to-End Event-Driven Sync Pipeline

Heavy file-based synchronization runs asynchronously over Kafka. Platform owns orchestration and execution tracking. Ingestor owns external data retrieval and Parquet writes. Analyzer is not in the stock-sync execution path.

```text
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. Scheduler selects due job definitions              │
 │  2. Create parent execution and child task executions  │
 │  3. Publish topic-sync-stock-prices commands           │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           ▼
              [Topic: topic-sync-stock-prices]
                           │
                           │ Payload: {"symbolKey": "HOSE-HPG",
                           │           "jobDefinitionId",
                           │           "executionId",
                           │           "parentExecutionId", ...}
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                    ingestor (Python)                   │
 │                                                        │
 │  1. Fetch existing eod/hose/hpg.parquet from MinIO    │
 │  2. Fetch recent incremental records via stock client │
 │  3. Merge and deduplicate records                     │
 │  4. Stream updated parquet back to MinIO              │
 │  5. Publish topic-sync-job-status metrics             │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           │ Payload: {"symbolKey": "HOSE-HPG",
                           │           "executionId", "status",
                           │           "recordsInserted", ...}
                           ▼
                [Topic: topic-sync-job-status]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. Consume status                                    │
 │  2. Update child execution by executionId             │
 │  3. Aggregate parent execution when applicable        │
 └────────────────────────────────────────────────────────┘
```

---

## Kafka Topics and Contracts

Topic names are centralized in `configs/shared/topics.yaml` and exposed through service settings.

| Topic | Direction from Ingestor | Consumer Group | Description |
| :--- | :--- | :--- | :--- |
| `topic-sync-stock-prices` | Inbound | ingestor | Stock-price sync command from Platform with `symbolKey`, `jobDefinitionId`, `executionId`, and optional `parentExecutionId`. |
| `topic-sync-symbols` | Inbound | ingestor | Symbol-master sync command from Platform, keyed by exchange. |
| `topic-sync-job-status` | Outbound | — | Job status result from Ingestor to Platform. |
| `topic-upsert-symbols` | Outbound | — | Full symbol snapshot from Ingestor to Platform, keyed by exchange. |

Runtime overrides:

- `SYNC_STOCK_PRICES_TOPIC` — default `topic-sync-stock-prices`.
- `SYNC_SYMBOLS_TOPIC` — default `topic-sync-symbols`.
- `JOB_STATUS_TOPIC` — default `topic-sync-job-status`.
- `UPSERT_SYMBOLS_TOPIC` — default `topic-upsert-symbols`.
- `KAFKA_BOOTSTRAP_SERVERS` — default `localhost:9092`.
- `CONSUMER_GROUP_ID` — default `ingestor`.

### Stock-Price Request Example

```json
{
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "22222222-2222-4222-8222-222222222222",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "source": "VCI",
  "symbolKey": "HOSE-HPG",
  "fromOffset": "2024-01-01T00:00:00Z",
  "toOffset": "2026-07-12T12:00:00Z",
  "metadata": {}
}
```

### Job Status Example

```json
{
  "symbolKey": "HOSE-HPG",
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "22222222-2222-4222-8222-222222222222",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "status": "SUCCESS",
  "recordsInserted": 12,
  "totalRecords": 2500,
  "durationMs": 7000,
  "errorMessage": null
}
```

### Contract Rule

Do not update a Kafka producer or consumer in isolation. When changing a topic, event schema, field semantics, serialization, validation, or error-handling contract, review and update both the producer and consumer sides in the same change, including tests and documentation.

---

## Ingestor Ports & Adapters Design

The ingestor abstracts both Kafka and stock data sources so transport and provider choices can evolve independently.

```text
ingestor/
├── app/
│   ├── kafka_consumer.py          # Kafka adapter
│   ├── stocks/
│   │   ├── base.py                # Abstract stock client interface
│   │   ├── registry.py            # Stock client resolver
│   │   └── clients/
│   │       ├── vndirect.py        # VNDirect API client
│   │       └── mock.py            # Mock data client
│   └── ...
├── main.py                        # Event router + concurrency control
└── api.py                         # Optional FastAPI HTTP entry point
```

Event router responsibilities:

- listen to `topic-sync-stock-prices` and `topic-sync-symbols`;
- dispatch to the correct handler based on topic;
- publish results to `topic-sync-job-status`;
- publish symbol upserts to `topic-upsert-symbols`;
- enforce bounded concurrency.

The optional HTTP entry point can be used for manual triggers or future serverless invocation. Business logic should remain in handlers and ports, not in transport-specific code.

---

## Deployment Architecture

The default deployment target is an Oracle Always Free ARM VM using Docker Compose.

| Component | Oracle VM Default | Cloud Migration Target |
| :--- | :--- | :--- |
| Kafka | Confluent/KRaft-compatible Kafka | MSK, Pub/Sub adapter, or another broker-backed adapter |
| Object Storage | MinIO | S3, GCS-compatible abstraction, Cloudflare R2 |
| PostgreSQL | Self-hosted PostgreSQL | RDS, Cloud SQL, managed PostgreSQL |
| Compute | Docker Compose | ECS, Lambda, Cloud Run |

Runtime configuration is shared through root-level env files and optional app-specific overrides.

| File | Purpose |
| :--- | :--- |
| `.env.example` | Local development defaults for all apps. Copy to `.env` on developer machines. |
| `.env.deploy.example` | Deployment template using container DNS names and production placeholders. Copy to `.env` on servers. |
| `apps/core/.env.example` | Optional Platform-only overrides. |
| `apps/analyzer/.env.example` | Optional Analyzer-only overrides. |
| `apps/ingestor/.env.example` | Optional Ingestor-only overrides. |

`docker-compose.services.yaml` loads the shared root `.env` first, then the app-specific `.env`. App-specific values override shared values for that service only.

Canonical shared env names are flat so Java, Python, and Docker Compose can share the same contract:

```dotenv
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=stock-data
S3_ENDPOINT_URL=http://localhost:9000
AWS_ACCESS_KEY_ID=minioadmin
AWS_SECRET_ACCESS_KEY=minioadmin
S3_BUCKET_NAME=stock-data
```

Do not add duplicate Pydantic-style nested names such as `MINIO__ENDPOINT`; Python services map the flat env contract into typed settings.

---

## S3 Data Lake Structure

The `stock-data` bucket follows a standardized lowercase path structure defined in `configs/shared/s3-paths.yaml`. All path construction is centralized and configuration-driven.

```text
stock-data/
├── symbols/
│   ├── hose.parquet
│   ├── hnx.parquet
│   └── upcom.parquet
└── eod/
    ├── hose/
    │   ├── hpg.parquet
    │   ├── fpt.parquet
    │   └── ...
    ├── hnx/
    └── upcom/
```

Path naming conventions:

1. Exchange names and ticker codes are normalized to lowercase.
2. Folder names use kebab-case.
3. Files are overwritten or merged in place; no temporal `dt=` or `run_id=` partitions.
4. Each ticker has one Parquet file per data type.
5. Sector, industry, and classification metadata stay in metadata files, not paths.

Python path builders are exposed through ingestor settings:

```python
from app.settings import settings

symbols_path = settings.get_symbols_path("HOSE")
eod_path = settings.get_eod_path("HOSE", "HPG")
```

Shared configuration example:

```yaml
stock-data:
  bucket: stock-data
  paths:
    symbols:
      base: "symbols/"
      pattern: "{exchange}.parquet"
    eod:
      base: "eod/"
      pattern: "{exchange}/{code}.parquet"
```

Future expansion paths include `intraday/`, `financials/`, `fundamentals/`, `corporate-actions/`, `ownership/`, `news/`, `macro/`, `derivatives/`, `warrants/`, and `etf/`.

---

## Architecture Guardrails

- **S3 abstraction**: File operations use S3-compatible clients and centralized path builders.
- **No S3 object metadata in Kafka commands**: Producers should not send bucket or object names. Consumers derive paths from contract fields such as `symbolKey`.
- **Kafka transport abstraction**: Ingestor Kafka access stays behind port interfaces.
- **Infrastructure portability**: Services are containerized and should avoid cloud-vendor runtime coupling in business logic.
- **12-factor settings**: Credentials, broker hosts, buckets, and endpoints are runtime environment variables.
- **Service boundaries**: Platform owns orchestration, Ingestor owns Parquet writes, Analyzer owns analytical API boundaries.
- **Producer/consumer consistency**: Kafka contract changes must update both sides, tests, and documentation.
- **External submodule safety**: Do not modify `externals/` unless explicitly working on that submodule.

---

## Database Architecture

Database schemas are managed in `database/migrations/V*__*.sql` and are applied by Flyway on Platform startup.

Migration guidance:

- check the current highest migration version before adding a new one;
- use explicit column types and constraints;
- add indexes for foreign keys and frequently queried columns;
- include `created_at` and `updated_at` timestamps where appropriate;
- use explicit foreign-key delete behavior;
- test migrations against a development database before deployment.

---

## Related Documents

- [README.md](README.md) — minimal project overview, setup, and Nx commands.
- [AGENTS.md](AGENTS.md) — agent and repository workflow rules.
- [configs/shared/topics.yaml](configs/shared/topics.yaml) — canonical Kafka topic configuration.
- [configs/shared/s3-paths.yaml](configs/shared/s3-paths.yaml) — canonical S3 path configuration.
