# Omni Monorepo Workspace

Welcome to **Omni**, a high-performance, cloud-portable stock analytics and data integration platform built inside an **Nx 22.5** monorepo. This workspace coordinates robust, enterprise-grade backends and stateless event-driven consumers, providing a highly automated and optimized ecosystem for financial data tracking.

---

## 📐 System Architecture & Services

Omni consists of three main services organized inside the `apps/` directory, supported by local infrastructure:

```
omni/
├── apps/
│   ├── core/          # Java 21 / Spring Boot 4 — Platform API & Storage Orchestration
│   ├── analyzer/      # Python 3.14 / FastAPI — Analytical REST API & Direct DB Sync
│   └── ingestor/      # Python 3.14 / Async Event Worker — Stateless Parquet Data Lake Sync
├── database/
│   └── migrations/    # Flyway SQL migrations (V1–V3)
├── externals/
│   └── vnstock-etl/   # Git submodule — ETL pipeline for stock data
├── docker-compose.yaml
├── nx.json
├── package.json
└── project.json
```

### 1. Platform Core API (`apps/core`)

- **Tech Stack**: Java 21, Spring Boot 4.0.1, Spring Modulith, Spring Data JPA + Hibernate, PostgreSQL, Flyway, MinIO client.
- **Role**: Central command orchestrator. Manages user accounts, portfolio tracking, screener alerts, and metadata synchronization.
- **Design Pattern**: Clean Ports & Adapters architecture. Storage providers are resolved dynamically at runtime using the `StorageProviderRegistry`.
- **Database Migrations**: Automated SQL-based migrations via Flyway on service startup.

### 2. Stock Ingestor Service (`apps/ingestor`)

- **Tech Stack**: Python 3.14, `aiokafka` 0.12.0+ (async Kafka consumer/producer), `minio` 7.2.0 (S3-compatible client), `pandas` 2.2.0, `pyarrow` 15.0.0.
- **Role**: Async event-driven worker that subscribes to stock synchronization commands and performs high-speed in-memory Parquet updates to MinIO object storage.
- **Design Pattern**: Ports & Adapters — Kafka client and stock data sources are abstracted behind port interfaces, making the transport layer swappable without touching business logic. Registry pattern for stock clients (VNDirect default).
- **Ingestion Pipeline**:
  1. Consumes sync requests from the `topic-sync-stock-prices` topic (details: `symbol`, `source`, `fromOffset`, `toOffset`, `jobId`, `logId`).
  2. Downloads the existing `.parquet` history from MinIO (path prefix: `EOD/{symbol}.parquet`).
  3. Fetches the latest incremental records using the configured stock client (VNDirect API).
  4. Merges old and new records in-memory using Pandas and deduplicates by `date`.
  5. Streams the updated history back to MinIO as a clean Parquet chunk.
  6. Publishes processing metrics to `topic-sync-job-status` topic (details: `symbol`, `jobId`, `logId`, `status`, `recordsInserted`, `totalRecords`, `durationMs`, `errorMessage`).
- **Concurrency**: Uses `asyncio.Semaphore` to process multiple messages concurrently (bounded by configurable `MAX_CONCURRENT_TASKS`), safe for I/O-bound workloads.

### 3. Stock Analyzer API (`apps/analyzer`)

- **Tech Stack**: Python 3.14, FastAPI 0.128.8, SQLAlchemy 2.0 (Async Engine), asyncpg, PostgreSQL, VNDirect API HTTP client.
- **Role**: REST-only analytical API for querying stock data and on-demand synchronization from external sources.
- **Endpoints** (all under `/v1`):
  - `GET /v1/stocks/?symbol=STB` — Queries stock pricing history from PostgreSQL.
  - `POST /v1/stocks/sync?symbol=STB` — Triggers on-demand historical price retrieval from the VNDirect API and commits them directly to the database with conflict avoidance.
- **Note**: Analyzer is independent of the Kafka pipeline and operates as a pure REST API layer over PostgreSQL.

---

## 🔄 End-to-End Event-Driven Sync Pipeline

Heavy file-based synchronization runs completely asynchronously over a bidirectional event loop. The Platform API orchestrates syncs through Kafka; the Analyzer API operates independently:

```
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. POST /api/stocks/sync?symbol=XYZ                   │
 │  2. Query `update_log` / `sync_config` → Calc params   │
 │  3. Publish `topic-sync-stock-prices` command                 │
 └─────────────────────────┬──────────────────────────────┘
                           │
     ┌─────────────────────┴──────────────────────┐
     │                                            │
     ▼                                            ▼
[Topic: topic-sync-stock-prices]                  [Analyzer API]
     │                                    (independent)
     │ Payload: {"symbol": "XYZ",         GET /v1/stocks/
     │           "source": "vnd",          POST /v1/stocks/sync
     │           "jobId", "logId", ...}    → Queries PostgreSQL directly
     │                                     → Fetches from VNDirect
     ▼                                        on-demand (no Kafka)
 ┌────────────────────────────────────────────────────────┐
 │                    ingestor (Python)                   │
 │                                                        │
 │  1. Fetch existing `EOD/XYZ.parquet` from MinIO        │
 │  2. Fetch recent incremental records via stock client  │
 │  3. Merge (pd.concat) & Deduplicate (drop_duplicates)  │
 │  4. Stream updated `.parquet` back to MinIO            │
 │  5. Publish `topic-sync-job-status` metrics                  │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           │ Payload: {"symbol": "XYZ", "jobId", "logId",
                           │           "status": "success", "recordsInserted",
                           │           "totalRecords", "durationMs", ...}
                           ▼
                [ Topic: topic-sync-job-status ]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. Consume status (if listener configured)            │
 │  2. Persist audit log into PostgreSQL `update_log`     │
 │  3. Update `sync_config` metadata                      │
 └────────────────────────────────────────────────────────┘
```

### Kafka Message Formats

#### Sync Request Topic (`topic-sync-stock-prices`)

```json
{
  "symbol": "STB",
  "source": "vnd",
  "fromOffset": 0,
  "toOffset": 50,
  "jobId": "job-123",
  "logId": "log-456"
}
```

#### Sync Status Topic (`topic-sync-job-status`)

```json
{
  "symbol": "STB",
  "jobId": "job-123",
  "logId": "log-456",
  "status": "success",
  "recordsInserted": 50,
  "totalRecords": 1550,
  "durationMs": 350,
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
- **REST Independence**: Analyzer API operates independently from the event pipeline for flexibility in querying and on-demand syncs.

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

| Topic                     | Direction | Consumer Group | Description                                                                                                          |
| :------------------------ | :-------- | :------------- | :------------------------------------------------------------------------------------------------------------------- |
| `topic-sync-stock-prices` | Inbound   | ingestor       | Sync command from Platform: `{"symbol", "source", "fromOffset", "toOffset", "jobId", "logId"}`                       |
| `topic-sync-job-status`   | Outbound  | —              | Result from Ingestor: `{"symbol", "jobId", "logId", "status", "recordsInserted", "totalRecords", "durationMs", ...}` |

**Configuration** (environment variables):

- `SYNC_SYMBOLS_JOBS` — Inbound topic name (default: `topic-sync-stock-prices`)
- `STATUS_TOPIC` — Outbound topic name (default: `topic-sync-job-status`)
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

**Active Migrations** (V1–V3):

- `V1__create_job_definition_table.sql` — Job type definitions and configuration templates
- `V2__create_job_execution_history_table.sql` — Audit log of all job executions with status and metrics
- `V3__create_symbol_table.sql` — Stock ticker symbols with exchange and metadata

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
