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

- **Tech Stack**: Java 21, Spring Boot 4.0.1, Spring Modulith, Spring Data JPA + Hibernate, PostgreSQL, Flyway, MinIO client.
- **Role**: Central command orchestrator. Manages user accounts, portfolio tracking, screener alerts, and metadata synchronization.
- **Design Pattern**: Clean Ports & Adapters architecture. Storage providers are resolved dynamically at runtime using the `StorageProviderRegistry`.
- **Database Migrations**: Automated SQL-based migrations via Flyway on service startup.

### 2. Stock Ingestor Service (`apps/ingestor`)

- **Tech Stack**: Python 3.14, `aiokafka` (async Kafka consumer), `boto3` (S3-compatible client), `pandas`, `pyarrow`, `FastAPI` (HTTP entry point).
- **Role**: Async event-driven worker that subscribes to synchronization commands and performs high-speed in-memory updates over the Parquet-based S3 data lake.
- **Design Pattern**: Ports & Adapters — Kafka client is abstracted behind `EventConsumer` interface, making the transport layer swappable without touching business logic.
- **Ingestion Pipeline**:
  1. Consumes event requests from the `stock-sync` topic.
  2. Downloads the existing `.parquet` history from object storage.
  3. Fetches the latest incremental records using the internal generator.
  4. Merges old and new records in-memory using Pandas and deduplicates by `date`.
  5. Streams the updated history back to object storage as a clean Parquet chunk.
  6. Emits processing metrics back to the `stock-sync-status` topic.
- **Concurrency**: Uses `asyncio.Semaphore` to process multiple messages concurrently (bounded by configurable `MAX_CONCURRENT_TASKS`), safe for I/O-bound workloads.

### 3. Stock Analyzer API (`apps/analyzer`)

- **Tech Stack**: Python 3.14, FastAPI, SQLAlchemy 2.0 (Async Engine), PostgreSQL, VNDirect API HTTP client.
- **Role**: Exposes analytical endpoints and on-demand DB-to-API synchronization.
- **Endpoints**:
  - `GET /v1/stocks/` — Queries current stock pricing history from PostgreSQL.
  - `POST /v1/stocks/sync` — Triggers on-demand historical prices retrieval from the VNDirect API and commits them directly to the `stock_prices` table with conflict avoidance.

---

## 🔄 End-to-End Event-Driven Sync Pipeline

Heavy file-based synchronization runs completely asynchronously over a bidirectional event loop:

```
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. POST /api/stocks/sync?symbol=XYZ                   │
 │  2. Query `update_log` / `sync_config` -> Calc `limit` │
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
                           │ Payload: {"symbol": "XYZ", "status": "success", ...}
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

#### Sync Request Topic (`stock-sync`)

```json
{ "symbol": "STB", "limit": 50 }
```

#### Sync Status Topic (`stock-sync-status`)

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

## 🏗️ Ingestor: Ports & Adapters Design

The `ingestor` abstracts its transport layer so business logic remains cloud-agnostic:

```
ingestor/
├── ports/
│   └── event_consumer.py      # Abstract interface: poll() / publish()
├── adapters/
│   ├── kafka_consumer.py      # aiokafka — used on self-hosted Kafka (default)
│   └── upstash_consumer.py    # HTTP REST — used on Upstash free tier
├── handlers/
│   └── stock_sync.py          # Business logic — never imports transport directly
└── main.py                    # Event router + concurrency control
```

**Event router pattern:**

```python
# main.py
HANDLERS = {
    "stock-sync": handle_stock_sync,
}

sem = asyncio.Semaphore(MAX_CONCURRENT_TASKS)

async def dispatch(msg):
    async with sem:
        handler = HANDLERS.get(msg["topic"])
        if handler:
            await handler(msg["value"])

async def run():
    while True:
        messages = await consumer.poll()
        if messages:
            await asyncio.gather(
                *[dispatch(msg) for msg in messages],
                return_exceptions=True
            )
        else:
            await asyncio.sleep(2)
```

**HTTP entry point** (for manual trigger and health check, doubles as serverless entry point when migrating to Cloud Run / Lambda):

```python
# api.py — runs alongside consumer loop
@app.post("/trigger/{symbol}")
async def manual_trigger(symbol: str):
    await handle_stock_sync({"symbol": symbol, "limit": 100})
    return {"status": "ok"}

@app.get("/health")
async def health():
    return {"status": "ok"}
```

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

The `docker-compose.yaml` runs as-is on the Oracle VM — no architecture changes needed. Self-hosted Kafka (KRaft mode), MinIO, and PostgreSQL all run on the same instance, which is the closest environment to a real production setup while remaining at zero cost.

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

| Component      | Oracle VM (Free)    | AWS          | GCP       |
| :------------- | :------------------ | :----------- | :-------- |
| Kafka          | Self-hosted (KRaft) | MSK          | Pub/Sub   |
| Object Storage | MinIO               | S3           | GCS       |
| PostgreSQL     | Self-hosted         | RDS          | Cloud SQL |
| Compute        | Docker Compose      | ECS / Lambda | Cloud Run |

`boto3` S3 client in `ingestor` and `analyzer` requires only `endpoint_url` change when switching between MinIO, AWS S3, or Cloudflare R2:

```bash
S3_ENDPOINT_URL=http://minio:9000          # Oracle VM (MinIO)
S3_ENDPOINT_URL=https://<id>.r2.cloudflarestorage.com  # Cloudflare R2
# Unset for AWS S3 (boto3 default)
```

When migrating to FaaS (Lambda / Cloud Run), the HTTP entry point in `ingestor/api.py` becomes the new invocation target — business logic in `handlers/` remains unchanged.

---

## ⚙️ Key Rules & Cloud-Agnostic Guardrails

- **S3 Abstraction**: All file-based operations use standard S3 configuration via `boto3`. Storage tier is swapped solely via `S3_ENDPOINT_URL` environment variable.
- **Infrastructure Portability**: Services are standard containerized OCI images with no cloud-vendor runtime dependencies.
- **12-Factor Settings**: All credentials, connection strings, broker hosts, and bucket names are configured through runtime environment variables.
- **Transport Abstraction**: Kafka client is isolated behind the `EventConsumer` port — swap `kafka_consumer.py` for `upstash_consumer.py` (or any future adapter) without touching handlers.

---

## 🛠️ Prerequisites

- **Node.js (v18+ or v20+) & npm**: Workspace orchestration.
- **Java JDK 21**: Temurin JVM recommended.
- **Python 3.14+**: Application runtime.
- **`uv`**: Ultra-fast Python package resolver.
  - _macOS/Linux:_ `curl -LsSf https://astral.sh/uv/install.sh | sh`
  - _Windows:_ `powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"`
- **Docker & Docker Compose**: Local database, Kafka, and storage.

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

## 💻 Running the Monorepo

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

| Service           | Local Port      | Access Endpoint                                | Credentials                                        |
| :---------------- | :-------------- | :--------------------------------------------- | :------------------------------------------------- |
| **PostgreSQL 16** | `5432`          | `jdbc:postgresql://localhost:5432/omni`        | `postgres` / `postgres` (DB: `omni`)               |
| **MinIO Storage** | `9000` / `9001` | [http://localhost:9001](http://localhost:9001) | `minioadmin` / `minioadmin` (Bucket: `stock-data`) |
| **pgAdmin 4**     | `5050`          | [http://localhost:5050](http://localhost:5050) | `admin@admin.com` / `admin`                        |
| **Apache Kafka**  | `9092`          | `localhost:9092`                               | Listener: `PLAINTEXT://kafka:29092`                |

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

Schemas (`users`, `stocks`, `stock_prices`, `sync_config`, `update_log`) are managed in `database/migrations/V*__*.sql` and auto-applied on Platform API startup. To add new tables, add a migration script and boot the server.
