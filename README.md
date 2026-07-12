# Omni Monorepo Workspace

Welcome to **Omni**, a high-performance, cloud-portable stock analytics and data integration platform built inside an **Nx 22.5** monorepo. This workspace coordinates robust, enterprise-grade backends and stateless event-driven consumers, providing a highly automated and optimized ecosystem for financial data tracking.

---

## 📐 System Architecture & Services

Omni consists of three main services organized inside the `apps/` directory, supported by local infrastructure:

```
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
├── nx.json
├── package.json
└── project.json
```

### 1. Platform Core API (`apps/core`)

- **Tech Stack**: Java 21, Spring Boot 4.0.1, Spring Modulith, Spring Data JPA + Hibernate, PostgreSQL, Flyway, MinIO client, Kafka.
- **Role**: Central command orchestrator. Manages user accounts, portfolio tracking, screener alerts, scheduler job definitions, parent/child execution tracking, Kafka request production, and status consumption.
- **Design Pattern**: Clean Ports & Adapters architecture. Storage providers are resolved dynamically at runtime using the `StorageProviderRegistry`.
- **Database Migrations**: Automated SQL-based migrations via Flyway on service startup.

### 2. Stock Ingestor Service (`apps/ingestor`)

- **Tech Stack**: Python 3.14, `aiokafka` 0.12.0+ (async Kafka consumer/producer), `minio` 7.2.0 (S3-compatible client), `pandas` 2.2.0, `pyarrow` 15.0.0.
- **Role**: Async event-driven worker that subscribes to stock synchronization commands and performs high-speed in-memory Parquet updates to MinIO object storage.
- **Design Pattern**: Ports & Adapters — Kafka client and stock data sources are abstracted behind port interfaces, making the transport layer swappable without touching business logic. Registry pattern for stock clients (VNDirect default).
- **Ingestion Pipeline**:
  1. Consumes stock-price requests from `topic-sync-stock-prices` and symbol-master requests from `topic-sync-symbols`.
  2. Derives object paths from shared path builders, for example `eod/{exchange}/{code}.parquet` and `symbols/{exchange}.parquet`.
  3. Fetches external market data using the configured stock client.
  4. Merges and deduplicates records in-memory using Pandas and PyArrow.
  5. Writes complete ticker-owned or exchange-owned Parquet snapshots to MinIO/S3.
  6. Publishes processing metrics to `topic-sync-job-status` and full symbol snapshots to `topic-upsert-symbols`.
- **Concurrency**: Uses `asyncio.Semaphore` to process multiple messages concurrently (bounded by configurable `MAX_CONCURRENT_TASKS`), safe for I/O-bound workloads.

### 3. Stock Analyzer API (`apps/analyzer`)

- **Tech Stack**: Python 3.14, FastAPI 0.128.8, Pydantic settings, Kafka and MinIO infrastructure adapters.
- **Role**: Analytical API boundary. Analyzer no longer owns stock persistence or synchronization execution.
- **Current maturity**:
  - Compatibility stock endpoints remain available, but they do not own Platform scheduler jobs and do not persist stock prices directly to PostgreSQL.
  - `KafkaEventPublisher` and `MinioObjectStorage` are infrastructure foundations for future analytical integrations.
  - Analyzer must not bypass Platform-owned orchestration or Ingestor-owned Parquet writes.

---

## 🔄 End-to-End Event-Driven Sync Pipeline

Heavy file-based synchronization runs asynchronously over Kafka. Platform owns scheduler orchestration and execution tracking; Ingestor owns external data retrieval and Parquet writes. Analyzer is not in the stock-sync execution path.

```
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. Scheduler selects due job definitions              │
 │  2. Create parent execution and child task executions  │
 │  3. Publish `topic-sync-stock-prices` commands         │
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
 │  1. Fetch existing `eod/hose/hpg.parquet` from MinIO   │
 │  2. Fetch recent incremental records via stock client  │
 │  3. Merge and deduplicate records                      │
 │  4. Stream updated `.parquet` back to MinIO            │
 │  5. Publish `topic-sync-job-status` metrics            │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           │ Payload: {"symbolKey": "HOSE-HPG",
                           │           "executionId", "status",
                           │           "recordsInserted", ...}
                           ▼
                [ Topic: topic-sync-job-status ]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. Consume status                                     │
 │  2. Update child execution by `executionId`            │
 │  3. Aggregate parent execution when applicable         │
 └────────────────────────────────────────────────────────┘
```

### Kafka Message Formats

Detailed message contracts are maintained in `docs/STOCK_SYNC_WORKFLOW.md` and `docs/SECTOR_SYNC_WORKFLOW.md`. Short stock-price request example:

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

Short status example:

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

---

## 🏗️ Ingestor: Ports & Adapters Design

The `ingestor` abstracts both Kafka and stock data sources, allowing independent evolution of transport and data providers:

```
ingestor/
├── app/
│   ├── kafka_consumer.py          # Kafka adapter (aiokafka)
│   ├── stocks/
│   │   ├── base.py                # Abstract stock client interface
│   │   ├── registry.py            # Stock client resolver
│   │   └── clients/
│   │       ├── vndirect.py        # VNDirect API client (default)
│   │       └── mock.py            # Mock data client
│   └── ...                        # Other utilities
├── main.py                        # Event router + concurrency control
└── api.py                         # FastAPI HTTP entry point (optional)
```

**Event router pattern** (main.py):

- Listens to `topic-sync-stock-prices` and `topic-sync-symbols` topics
- Dispatches to appropriate handler based on topic
- Publishes results to `topic-sync-job-status` topic
- Publishes symbol upserts to `topic-upsert-symbols` topic
- Bounded concurrency via `asyncio.Semaphore`

**HTTP entry point** (api.py — optional, for manual triggers or serverless):

```python
@app.post("/trigger/{symbol}")
async def manual_trigger(symbol: str):
    await handle_stock_sync({"symbol": symbol, ...})
    return {"status": "ok"}

@app.get("/health")
async def health():
    return {"status": "ok"}
```

This design allows swapping Kafka for different transports (MSK, Pub/Sub) or stock clients (VNDirect, Yahoo Finance, etc.) without modifying handlers.

---

## ☁️ Deployment: Oracle Always Free (Recommended)

### Why Oracle Always Free VM

Oracle provides **Always Free** ARM Compute (Ampere A1) — permanently free with no expiry:

| Resource | Allocation           |
| :------- | :------------------- |
| CPU      | 4 Ampere A1 cores    |
| RAM      | 24 GB                |
| Storage  | 200 GB boot volume   |
| Network  | 10 TB outbound/month |

The `docker-compose.yaml` runs as-is on the Oracle VM — no architecture changes needed. Confluent Kafka, MinIO, and PostgreSQL all run on the same instance, which provides a realistic environment similar to production while remaining at zero cost.

### Deployment Setup

```bash
# On Oracle VM (Ubuntu 22.04 ARM)
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
git clone --recursive <repository-url>
cd omni
docker compose up -d
```

### Cloud Portability (Migration Path)

All external dependencies are configured via environment variables. Migrating to AWS, GCP, or any other provider requires only env var changes:

| Component      | Oracle VM (Free)   | AWS          | GCP       |
| :------------- | :----------------- | :----------- | :-------- |
| Kafka          | Confluent cp-kafka | MSK          | Pub/Sub   |
| Object Storage | MinIO              | S3           | GCS       |
| PostgreSQL     | Self-hosted        | RDS          | Cloud SQL |
| Compute        | Docker Compose     | ECS / Lambda | Cloud Run |

`minio` S3 client in `ingestor` requires only `endpoint_url` change when switching between MinIO, AWS S3, or Cloudflare R2:

```bash
S3_ENDPOINT_URL=http://minio:9000          # Oracle VM (MinIO)
S3_ENDPOINT_URL=https://<id>.r2.cloudflarestorage.com  # Cloudflare R2
# Unset for AWS S3 (boto3 default)
```

When migrating to FaaS (Lambda / Cloud Run), the HTTP entry point in `ingestor/api.py` becomes the new invocation target — business logic remains unchanged.

---

## ⚙️ Key Rules & Cloud-Agnostic Guardrails

- **S3 Abstraction**: File-based operations use S3-compatible clients (`minio` library). Storage tier is swapped solely via `S3_ENDPOINT_URL` environment variable without code changes.
- **Kafka Transport Abstraction**: Ingestor's Kafka client is isolated behind port interfaces, allowing swap of Kafka adapters (e.g., Confluent → MSK → Pub/Sub) without touching business logic.
- **Infrastructure Portability**: Services are standard containerized OCI images with no cloud-vendor runtime dependencies.
- **12-Factor Settings**: All credentials, connection strings, broker hosts, and bucket names are configured through runtime environment variables.
- **Service Boundaries**: Platform owns orchestration, Ingestor owns Parquet writes, and Analyzer must not reintroduce direct stock persistence outside agreed service contracts.

---

## 📁 S3 Data Lake Structure

The stock-data bucket follows a standardized, lowercase path structure defined in `configs/shared/s3-paths.yaml`. All path construction is centralized and configuration-driven.

### Current Implementation

```
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

### Path Naming Conventions

1. **Lowercase normalization**: Exchange names and ticker codes are automatically converted to lowercase in paths (`HOSE` → `hose`, `HPG` → `hpg`)
2. **Folder names**: Use kebab-case (`corporate-actions/`, `income-statement.parquet`)
3. **No temporal partitioning**: Files are overwritten or merged in place (no `dt=` or `run_id=` folders)
4. **One ticker = one file**: Each ticker has a single Parquet file per data type
5. **Metadata separation**: Sector, industry, and classification metadata stored in separate metadata files, not in paths

### Path Building in Code

**Python (Ingestor):**
```python
from app.settings import settings

# Symbol metadata path
path = settings.get_symbols_path("HOSE")  # Returns: symbols/hose.parquet

# EOD price data path  
path = settings.get_eod_path("HOSE", "HPG")  # Returns: eod/hose/hpg.parquet
```

**Configuration File:** `configs/shared/s3-paths.yaml`

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

### Future Expansion Paths

The configuration file includes documented placeholders for upcoming features:
- `intraday/` — Intraday price data
- `financials/` — Financial statements (income statement, balance sheet, cash flow)
- `fundamentals/` — Financial ratios and metrics
- `corporate-actions/` — Corporate events and announcements
- `ownership/` — Shareholder data
- `news/` — News articles
- `macro/` — Macroeconomic indicators
- `derivatives/`, `warrants/`, `etf/` — Alternative instruments

**See:** `docs/S3_PATH_CONFIGURATION.md` for detailed documentation.

---

## 🛠️ Prerequisites

- **Node.js (v18+ or v20+) & npm**: Workspace orchestration via Nx 22.5.1.
- **Java JDK 21**: Adoptium JVM recommended.
- **Python 3.14+**: Application runtime.
- **`uv`**: Ultra-fast Python package resolver.
  - _macOS/Linux:_ `curl -LsSf https://astral.sh/uv/install.sh | sh`
  - _Windows:_ `powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"`
- **Docker & Docker Compose**: PostgreSQL, Confluent Kafka, MinIO, pgAdmin.

---

## 🚀 Getting Started

### 1. Initialize Workspace Infrastructure

```bash
git clone --recursive <repository-url>
cd omni
npm install
nx run omni:init
```

### 2. Synchronize Python Environments

```bash
nx run analyzer:sync
nx run ingestor:sync
```

---

## � Kafka Configuration & Topics

| Topic | Direction from Ingestor | Consumer Group | Description |
| :--- | :--- | :--- | :--- |
| `topic-sync-stock-prices` | Inbound | ingestor | Stock-price sync command from Platform with `symbolKey`, `jobDefinitionId`, `executionId`, and optional `parentExecutionId`. |
| `topic-sync-symbols` | Inbound | ingestor | Symbol-master sync command from Platform, keyed by exchange. |
| `topic-sync-job-status` | Outbound | — | Job status result from Ingestor to Platform. |
| `topic-upsert-symbols` | Outbound | — | Full symbol snapshot from Ingestor to Platform, keyed by exchange. |

**Configuration** is centralized in `configs/shared/topics.yaml`, with runtime overrides available through service settings:

- `SYNC_STOCK_PRICES_TOPIC` — Stock-price request topic (default: `topic-sync-stock-prices`)
- `SYNC_SYMBOLS_TOPIC` — Symbol-master request topic (default: `topic-sync-symbols`)
- `JOB_STATUS_TOPIC` — Status topic (default: `topic-sync-job-status`)
- `UPSERT_SYMBOLS_TOPIC` — Symbol snapshot topic (default: `topic-upsert-symbols`)
- `KAFKA_BOOTSTRAP_SERVERS` — Broker addresses (default: `localhost:9092`)
- `CONSUMER_GROUP_ID` — Ingestor consumer group (default: `ingestor`)

---

### Development Mode (all services concurrently)

```bash
nx run omni:dev
```

Logs are streamed with prefixes `[JAVA]`, `[ANALYZER]`, and `[INGESTOR]`.

### Individual Application Tasks

#### Platform API (`apps/core`)

```bash
nx serve platform                          # Run Boot app (dev profile)
nx serve platform --configuration=prod     # Run Boot app (prod profile)
nx build platform                          # Build executable JAR
nx test platform                           # Run JUnit 5 tests
```

#### Ingestor Service (`apps/ingestor`)

```bash
nx serve ingestor                          # Run event consumer loop
nx test ingestor                           # Run unit tests
nx lint ingestor                           # Run Ruff lint check
nx format ingestor                         # Auto-format Python code
```

#### Analyzer API (`apps/analyzer`)

```bash
nx serve analyzer                          # Run FastAPI via uvicorn-hmr
nx test analyzer                           # Run pytest suite
nx lint analyzer                           # Run Ruff lint check
nx format analyzer                         # Auto-format Python code
nx debug analyzer                          # Run local debugging
```

---

## 🗄️ Local Infrastructure & Credentials

Managed by Docker Compose:

| Service             | Local Port      | Access Endpoint                                | Credentials                                        |
| :------------------ | :-------------- | :--------------------------------------------- | :------------------------------------------------- |
| **PostgreSQL 16**   | `5432`          | `jdbc:postgresql://localhost:5432/omni`        | `postgres` / `postgres` (DB: `omni`)               |
| **MinIO Storage**   | `9000` / `9001` | [http://localhost:9001](http://localhost:9001) | `minioadmin` / `minioadmin` (Bucket: `stock-data`) |
| **pgAdmin 4**       | `5050`          | [http://localhost:5050](http://localhost:5050) | `admin@admin.com` / `admin`                        |
| **Confluent Kafka** | `9092`          | `PLAINTEXT://localhost:9092`                   | No authentication (local dev only)                 |

---

## 📦 Python Dependency Management

```bash
# Add a package
nx run analyzer:add --name="numpy>=1.26.0"

# Remove a package
nx run ingestor:remove --name="requests"

# Sync virtualenv
nx run <project-name>:sync

# Update lockfile
nx run <project-name>:lock
```

---

## 🗃️ Database Migrations (Flyway)

Schemas are managed in `database/migrations/V*__*.sql` and auto-applied on Platform API startup using Flyway.

**Active Migrations** are maintained under `database/migrations/`. Check that directory for the current version list before adding new migrations.

### Adding New Migrations

Create a new migration file in `database/migrations/`:

```bash
# File naming convention: V<N>__<description>.sql
database/migrations/V4__create_stock_prices_table.sql
```

Flyway automatically detects and applies new migrations on Platform API startup. Migrations are idempotent and run in version order.

### Migration Best Practices

- Use explicit column types and constraints
- Add indexes for foreign keys and frequently queried columns
- Include `created_at` and `updated_at` timestamps
- Use `ON DELETE CASCADE` or `ON DELETE SET NULL` for foreign keys
- Test migrations on a dev database before deploying
