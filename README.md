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
│   └── migrations/    # Flyway SQL migrations (V1–V12)
├── externals/
│   └── vnstock-etl/   # Git submodule — ETL pipeline for stock data
├── docker-compose.yaml
├── nx.json
├── package.json
└── project.json
```

### 1. Platform Core API (`apps/core`)
*   **Tech Stack**: Java 21, Spring Boot 4.0.1, Spring Modulith, Spring Data JPA + Hibernate, PostgreSQL, Flyway, and MinIO client.
*   **Role**: Serves as the central command orchestrator. It manages user accounts, portfolio tracking, screener alerts, and metadata synchronization.
*   **Design Pattern**: Clean Ports & Adapters architecture. Storage providers are resolved dynamically at runtime using the `StorageProviderRegistry`.
*   **Database Migrations**: Handles automated SQL-based database migrations via Flyway on service startup.

### 2. Stock Ingestor Service (`apps/ingestor`)
*   **Tech Stack**: Python 3.14, `aiokafka` (async Kafka consumer & producer), `minio` (S3-compatible SDK), `pandas`, and `pyarrow`.
*   **Role**: A stateless, highly-scalable consumer that subscribes to synchronization commands. It performs high-speed in-memory updates directly over the Parquet-based S3 data lake.
*   **Ingestion Pipeline**:
    1. Consumes event requests from the `stock-sync` topic.
    2. Downloads the existing `.parquet` history from MinIO.
    3. Fetches the latest incremental records using the internal generator.
    4. Merges old and new records in-memory using Pandas and deduplicates by `date`.
    5. Streams the updated history back to MinIO as a clean Parquet chunk.
    6. Emits processing metrics back to the `stock-sync-status` topic.

### 3. Stock Analyzer API (`apps/analyzer`)
*   **Tech Stack**: Python 3.14, FastAPI, SQLAlchemy 2.0 (Async Engine), PostgreSQL, and a VNDirect API HTTP client.
*   **Role**: Exposes analytical endpoints and on-demand DB-to-API synchronization.
*   **Endpoints**:
    *   `GET /v1/stocks/`: Queries current stock pricing history from PostgreSQL.
    *   `POST /v1/stocks/sync`: Triggers on-demand historical prices retrieval from the VNDirect API and commits them directly to the `stock_prices` table in PostgreSQL with conflict avoidance.

---

## 🔄 End-to-End Event-Driven Sync Pipeline

To keep the database clean and workloads optimized, heavy file-based synchronization runs completely asynchronously over a bidirectional event loop:

```
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. POST /api/stocks/sync?symbol=XYZ                    │
 │  2. Query `update_log` / `sync_config` -> Calc `limit`  │
 │  3. Publish `stock-sync` command                       │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           │ Payload: {"symbol": "XYZ", "limit": 100}
                           ▼
                   [ Topic: stock-sync ]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                    ingestor (Python)                   │
 │                                                        │
 │  1. Fetch existing `parquet/XYZ.parquet` from S3       │
 │  2. Fetch recent incremental records                   │
 │  3. Merge (pd.concat) & Deduplicate (drop_duplicates)  │
 │  4. Stream updated `.parquet` back to S3               │
 │  5. Publish `stock-sync-status` metrics                │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           │ Payload: {"symbol": "XYZ", "status": "success", "recordsInserted": 100, ...}
                           ▼
                [ Topic: stock-sync-status ]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. Consume status via `@KafkaListener`                │
 │  2. Persist audit log into PostgreSQL `update_log`     │
 │  3. Update `last_success` in `sync_config`             │
 └────────────────────────────────────────────────────────┘
```

### Kafka Message Formats

#### 1. Sync Request Topic (`stock-sync`)
Published by the Java Platform to request historical synchronization:
```json
{
  "symbol": "STB",
  "limit": 50
}
```

#### 2. Sync Status Topic (`stock-sync-status`)
Emitted by the Python Ingestor upon finishing data updates:
```json
{
  "symbol": "STB",
  "status": "success",
  "recordsInserted": 50,
  "totalRecords": 1550,
  "durationMs": 350,
  "errorMessage": null
}
```

---

## ⚙️ Key Rules & Cloud-Agnostic Guardrails

*   **S3 Abstraction**: All file-based operations must utilize standard S3 configurations (e.g., using `boto3` or `minio` in Python). Swapping the storage tier from MinIO to AWS S3, Cloudflare R2, or Google Cloud Storage is done solely via environmental variables.
*   **Infrastructure Portability**: Services must remain completely decoupled from specific cloud vendor runtimes (e.g., no AWS Lambda-specific zip-packing in code). Microservices must remain standard containerized OCI images.
*   **12-Factor Settings**: All API credentials, database strings, broker hosts, and object store buckets must be fully configurable through runtime Environment Variables.

---

## 🛠️ Prerequisites

*   **Node.js (v18+ or v20+) & npm**: Workspace orchestration.
*   **Java JDK 21**: Temurin JVM recommended.
*   **Python 3.14+**: Application runtime.
*   **`uv`**: Ultra-fast Python package resolver.
    *   *macOS/Linux:* `curl -LsSf https://astral.sh/uv/install.sh | sh`
    *   *Windows:* `powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"`
*   **Docker & Docker Compose**: Local database, Kafka, and storage.

---

## 🚀 Getting Started & Initialization

### 1. Initialize Workspace Infrastructure
Clone this monorepo along with its submodules, install workspace dependencies, and initialize the background services (PostgreSQL, Kafka, MinIO, pgAdmin):
```bash
git clone --recursive <repository-url>
cd omni
npm install
nx run omni:init
```

### 2. Synchronize Python Environments
Set up virtual environments and pull lockfile dependencies for both Python applications:
```bash
# Sync analyzer
nx run analyzer:sync

# Sync ingestor
nx run ingestor:sync
```

---

## 💻 Running the Monorepo

### Running Concurrently (Development Mode)
To boot all three core components (Java Platform API, FastAPI Analyzer API, and the event-driven Ingestor Service) concurrently with hot-reload enabled, simply run:
```bash
nx run omni:dev
```
Logs are automatically streamed together and labeled with prefixes `[JAVA]`, `[ANALYZER]`, and `[INGESTOR]`.

### Individual Application Tasks

#### ☕ Platform API (`apps/core`)
```bash
nx serve platform                          # Run Boot app (dev profile)
nx serve platform --configuration=prod     # Run Boot app (prod profile)
nx build platform                          # Build executable JAR
nx test platform                           # Run JUnit 5 tests
```

#### 📥 Ingestor Service (`apps/ingestor`)
```bash
nx serve ingestor                          # Run event consumer loop
nx test ingestor                           # Run unit tests
nx lint ingestor                           # Run Ruff lint check
nx format ingestor                         # Auto-format Python code
```

#### 🐍 Analyzer API (`apps/analyzer`)
```bash
nx serve analyzer                          # Run FastAPI via uvicorn-hmr
nx test analyzer                           # Run pytest suite
nx lint analyzer                           # Run Ruff lint check
nx format analyzer                         # Auto-format Python code
nx debug analyzer                          # Run local debugging
```

---

## 🗄️ Local Infrastructure & Credentials

Managed by Docker Compose under standard local ports:

| Service | Local Port | Access Endpoint | Credentials |
| :--- | :--- | :--- | :--- |
| **PostgreSQL 16** | `5432` | `jdbc:postgresql://localhost:5432/omni` | `postgres` / `postgres` (DB: `omni`) |
| **MinIO Storage** | `9000` / `9001` | [http://localhost:9001](http://localhost:9001) | `minioadmin` / `minioadmin` (Bucket: `stock-data`) |
| **pgAdmin 4** | `5050` | [http://localhost:5050](http://localhost:5050) | `admin@admin.com` / `admin` |
| **Apache Kafka** | `9092` | `localhost:9092` | Listener: `PLAINTEXT://kafka:29092` |

---

## 📦 Python Dependency Management

Dependency management in `analyzer` and `ingestor` goes through standard Nx commands utilizing **`uv`**:

```bash
# Add a Python package to the analyzer (e.g. numpy)
nx run analyzer:add --name="numpy>=1.26.0"

# Remove a Python package from the ingestor
nx run ingestor:remove --name="requests"

# Sync packages in your virtualenv
nx run <project-name>:sync

# Update lockfile
nx run <project-name>:lock
```
*(Substitute `<project-name>` with either `analyzer` or `ingestor` as needed).*

---

## 🗃️ Database Migrations (Flyway)
The relational database schemas (covering tables like `users`, `stocks`, `stock_prices`, `sync_config`, and `update_log`) are managed inside `database/migrations/V*__*.sql`. Migrations are auto-applied on Platform API startup. To add new tables, add a standard migration script under `database/migrations/` and boot the server.