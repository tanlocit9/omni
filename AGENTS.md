<!-- nx configuration start-->
<!-- Leave the start & end comments to automatically receive updates. -->

# General Guidelines for working with Nx

- When running tasks (for example build, lint, test, e2e, etc.), always prefer running the task through `nx` (i.e. `nx run`, `nx run-many`, `nx affected`) instead of using the underlying tooling directly
- You have access to the Nx MCP server and its tools, use them to help the user
- When answering questions about the repository, use the `nx_workspace` tool first to gain an understanding of the workspace architecture where applicable.
- When working in individual projects, use the `nx_project_details` mcp tool to analyze and understand the specific project structure and dependencies
- For questions around nx configuration, best practices or if you're unsure, use the `nx_docs` tool to get relevant, up-to-date docs. Always use this instead of assuming things about nx configuration
- If the user needs help with an Nx configuration or project graph error, use the `nx_workspace` tool to get any errors
- For Nx plugin best practices, check `node_modules/@nx/<plugin>/PLUGIN.md`. Not all plugins have this file - proceed without it if unavailable.

<!-- nx configuration end-->

---

# Codebase Instructions for Claude

## Workspace Overview

This is an **Nx 22.5 monorepo** named `omni`. It contains three applications and no shared libraries yet.

```
omni/
├── apps/
│   ├── core/          # Java 21 / Spring Boot 4 — Platform API & Storage Orchestration
│   ├── analyzer/      # Python 3.14 / FastAPI — Analytical REST API & Direct DB Sync
│   └── ingestor/      # Python 3.14 / Async Event Worker — Parquet Data Lake Sync
├── database/
│   └── migrations/    # Flyway SQL migrations (V1–V12)
├── externals/
│   └── vnstock-etl/   # Git submodule — ETL pipeline for stock data
├── docker-compose.yaml
├── nx.json
├── package.json       # Root — Nx + tooling only, no app code
└── project.json       # Root-level Nx targets (init, dev)
```

---

## Running the Workspace

### First-time setup

```bash
nx run omni:init
# Runs: git submodule sync/update, then docker compose up -d
```

### Run all apps together (development)

```bash
nx run omni:dev
# Starts platform (Java), analyzer (Python/FastAPI), and ingestor (Python/worker) concurrently
# Logs prefixed with [JAVA], [ANALYZER], [INGESTOR]
```

### Infrastructure only

```bash
docker compose up -d
# Starts: PostgreSQL 16 (5432), Kafka (9092), MinIO (9000/9001), pgAdmin (5050)
```

---

## App: `platform` (apps/core)

### Stack

- Java 21 (Adoptium JVM), Spring Boot 4.0.1
- Spring Modulith (modular monolith)
- Spring Data JPA + Hibernate + PostgreSQL
- Spring Security
- Flyway (database migrations)
- MinIO client (S3-compatible object storage)
- Lombok
- Build: Gradle 8 via `@nx/gradle` plugin

### Architecture

Spring Modulith modular monolith. Package layout under `com.omni.platform`:

```
application/
  controllers/
    StorageController.java   # REST controller for file storage API
    StockController.java     # REST controller — triggers stock sync via Kafka
  dtos/
    FileDeleteResult.java
    FileUploadResult.java
  usecases/
    FileUseCase.java
    FileUseCaseService.java
core/
  adapters/        # AbstractStorageAdapter + StorageProviderRegistry
  configs/         # Spring configuration classes
  constants/       # Shared constants
  enums/           # Shared enums
  events/          # Domain events (e.g. FileUploadedEvent)
  exceptions/      # Custom exceptions
  ports/           # Interfaces: WritablePort, ReadablePort, DeletablePort,
                   #             ListablePort, ShareablePort
modules/
  minio/           # MinIO storage adapter implementation
  kafka/           # Kafka producer (stock-sync) + consumer (@KafkaListener stock-sync-status)
  thumbnails/      # Thumbnail generation module
```

Key patterns:

- **Ports & Adapters**: Storage operations are defined as port interfaces; modules implement them.
- **Registry pattern**: `StorageProviderRegistry` resolves the correct adapter at runtime.
- **Event-driven async**: `@EnableAsync` + Spring events for post-upload processing.
- **Kafka integration**: Platform publishes `stock-sync` commands and consumes `stock-sync-status` results to update `update_log` and `sync_config`.

### Nx targets

```bash
nx serve platform                          # bootRun with dev profile (default)
nx serve platform --configuration=prod     # bootRun with prod profile
nx build platform                          # Gradle build
nx test platform                           # JUnit 5 tests
```

### Configuration files

- `apps/core/src/main/resources/application.yaml` — base config
- `apps/core/src/main/resources/application-dev.yaml` — dev profile (local DB, MinIO, Kafka)
- `apps/core/src/main/resources/application-test.yaml` — test profile (H2 in-memory)

### Database

- PostgreSQL 16 in production/dev; H2 in-memory for tests
- Migrations live in `database/migrations/V*.sql` (V1–V12):
  - `V1__create_users.sql`: User accounts and management.
  - `V2__create_reference_data.sql`: Static lookup / reference data.
  - `V3__create_stocks.sql`: Public companies and stock tickers.
  - `V4__create_company_info.sql`: Company metadata and profiles.
  - `V5__create_price_data.sql`: Historical stock price transactions.
  - `V6__create_financial_statements.sql`: Balance sheets, income statements, cash flows.
  - `V7__create_financial_ratios.sql`: Computed financial and fundamental ratios.
  - `V8__create_technical_indicators.sql`: Technical analysis indicators (MA, RSI, etc.).
  - `V9__create_events_news.sql`: News articles, corporate events, market announcements.
  - `V10__create_portfolio.sql`: User investment portfolios and transactions.
  - `V11__create_screener_alerts.sql`: Watchlists and screener alert rules.
  - `V12__create_sync_ops.sql`: Background ETL sync operation audit log.

---

## App: `analyzer` (apps/analyzer)

### Stack

- Python 3.14+, FastAPI 0.128.8
- Uvicorn with hot-reload (`uvicorn-hmr`)
- Package manager: `uv`
- SQLAlchemy 2.0 (Async Engine) + PostgreSQL
- httpx + requests (HTTP clients)
- VNDirect API HTTP client
- Ruff (linter + formatter)
- pytest + pytest-cov + pytest-html

### Architecture

Layered architecture under `app/`:

```
app/
  clients/
    vndirect_client.py        # External VNDirect API client
  controllers/v1/
    stock.py                  # FastAPI router — stock endpoints
  core/
    database.py               # Database engine, session maker, Base model setup
  dtos/stock/
    sync_stock_dto.py         # Pydantic DTOs for stock syncing
  models/
    models.py                 # SQLAlchemy declarative models
  providers/
    stock_provider.py         # FastAPI Depends() providers
  repositories/
    stock_prices_repository.py
  services/
    stock_service.py          # Business logic for stock analytics & data fetching
main.py                       # FastAPI app entry point, router registration
debug.py                      # Debug entry point
```

Key patterns:

- **Dependency injection**: FastAPI `Depends()` wires providers into controllers.
- **Async endpoints**: Use `async def` for all route handlers and service methods.
- **Versioned routing**: All routes are prefixed `/v1`.

### Endpoints

- `GET /v1/stocks/` — Query stock pricing history from PostgreSQL.
- `POST /v1/stocks/sync` — On-demand historical price retrieval from VNDirect API, committed to `stock_prices` with conflict avoidance.

### Nx targets

```bash
nx serve analyzer           # uvicorn-hmr with hot-reload
nx test analyzer            # pytest with coverage
nx lint analyzer            # ruff check
nx format analyzer          # ruff format
nx build analyzer           # build dist package
nx debug analyzer           # run debug.py
nx run analyzer:add         # add a Python dependency
nx run analyzer:remove      # remove a Python dependency
nx run analyzer:lock        # update uv.lock
nx run analyzer:sync        # sync virtualenv from lock file
```

### Code style

- Line length: 88 characters
- Ruff rules: E, F, UP, B, SIM, I (pycodestyle, Pyflakes, pyupgrade, bugbear, simplify, isort)
- All rules are auto-fixable via `nx format analyzer`

---

## App: `ingestor` (apps/ingestor)

### Stack

- Python 3.14+, `aiokafka` (async Kafka consumer & producer)
- `boto3` (S3-compatible client — MinIO, AWS S3, Cloudflare R2)
- `pandas`, `pyarrow` (in-memory Parquet processing)
- FastAPI (HTTP entry point for manual trigger and health check)
- Package manager: `uv`
- Ruff (linter + formatter)
- pytest

### Architecture

Ports & Adapters under `app/`:

```
app/
  ports/
    event_consumer.py         # Abstract interface: poll() / publish()
  adapters/
    kafka_consumer.py         # aiokafka — default (self-hosted Kafka / MSK)
    upstash_consumer.py       # HTTP REST — Upstash free tier alternative
  handlers/
    stock_sync.py             # Business logic: download parquet → merge → upload
  api.py                      # FastAPI HTTP entry point (manual trigger + health)
main.py                       # Event router + asyncio concurrency control
```

Key patterns:

- **Transport abstraction**: All Kafka interaction goes through the `EventConsumer` port. Swap adapters via env var without touching handlers.
- **Event router**: `main.py` dispatches messages to the correct handler by topic name.
- **Bounded concurrency**: `asyncio.Semaphore(MAX_CONCURRENT_TASKS)` limits parallel processing; safe for I/O-bound Parquet workloads.
- **HTTP entry point**: `api.py` exposes `/trigger/{symbol}` and `/health` — doubles as the FaaS invocation target when migrating to Lambda or Cloud Run.

### Kafka topics

| Topic               | Direction | Description                                                                                                      |
| :------------------ | :-------- | :--------------------------------------------------------------------------------------------------------------- |
| `stock-sync`        | Consume   | Sync command from platform: `{"symbol": "STB", "limit": 50}`                                                     |
| `stock-sync-status` | Publish   | Result back to platform: `{"symbol", "status", "recordsInserted", "totalRecords", "durationMs", "errorMessage"}` |

### Nx targets

```bash
nx serve ingestor           # Run event consumer loop
nx test ingestor            # pytest
nx lint ingestor            # ruff check
nx format ingestor          # ruff format
nx run ingestor:add         # add a Python dependency
nx run ingestor:remove      # remove a Python dependency
nx run ingestor:lock        # update uv.lock
nx run ingestor:sync        # sync virtualenv from lock file
```

---

## Infrastructure (docker-compose.yaml)

| Service  | Image                | Port(s)    | Credentials                                     |
| -------- | -------------------- | ---------- | ----------------------------------------------- |
| postgres | postgres:16          | 5432       | user: postgres / pass: postgres / db: omni      |
| minio    | quay.io/minio/minio  | 9000, 9001 | user: minioadmin / pass: minioadmin             |
| pgadmin  | dpage/pgadmin4       | 5050       | email: admin@admin.com / pass: admin            |
| kafka    | apache/kafka (KRaft) | 9092       | PLAINTEXT://kafka:29092 (no auth for local dev) |

All services have health checks and use named Docker volumes for persistence.

---

## Deployment: Oracle Always Free (Default Target)

The `docker-compose.yaml` runs as-is on an Oracle Always Free ARM VM (4 Ampere A1 cores, 24 GB RAM). No architecture changes are needed for deployment.

```bash
# On Oracle VM (Ubuntu 22.04 ARM)
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
git clone --recursive <repository-url>
cd omni
docker compose up -d
```

### Cloud portability

All external service endpoints are configured via environment variables. To migrate to AWS/GCP, only env vars change — no code changes required:

| Component      | Oracle VM         | AWS          | GCP       |
| :------------- | :---------------- | :----------- | :-------- |
| Kafka          | Self-hosted KRaft | MSK          | Pub/Sub   |
| Object Storage | MinIO             | S3           | GCS       |
| PostgreSQL     | Self-hosted       | RDS          | Cloud SQL |
| Compute        | Docker Compose    | ECS / Lambda | Cloud Run |

S3 endpoint switching (no code change):

```bash
S3_ENDPOINT_URL=http://minio:9000                          # Oracle VM
S3_ENDPOINT_URL=https://<id>.r2.cloudflarestorage.com     # Cloudflare R2
# Unset entirely for AWS S3
```

---

## S3 Data Lake Path Configuration

The stock-data bucket follows a centralized, configuration-driven path structure defined in `configs/shared/s3-paths.yaml`.

### Path Structure

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

### Path Builder Functions (Python)

The ingestor's `Settings` class provides path builder methods:

```python
from app.settings import settings

# Symbol metadata path
settings.get_symbols_path("HOSE")  # → symbols/hose.parquet

# EOD price data path
settings.get_eod_path("HOSE", "HPG")  # → eod/hose/hpg.parquet
```

### Key Rules

1. **Lowercase normalization**: Exchange names and ticker codes are automatically converted to lowercase in paths
   - Exchange: `HOSE` → `hose`, `HNX` → `hnx`, `UPCOM` → `upcom`
   - Ticker: `HPG` → `hpg`, `FPT` → `fpt`

2. **No bucket/objectName in Kafka messages**: The Java producer (`SyncStockPriceJobProducer`) does not send bucket or objectName metadata. The ingestor derives paths from the symbolKey using path builders.

3. **Pattern-based configuration**: Paths are defined using `{variable}` placeholders in YAML:
   ```yaml
   eod:
     base: "eod/"
     pattern: "{exchange}/{code}.parquet"
   ```

4. **No temporal partitioning**: Files are merged/overwritten in place. No `dt=` or `run_id=` folders.

5. **Future expansion ready**: Configuration includes placeholder paths for financials, corporate-actions, ownership, news, etc.

**See:** `docs/S3_PATH_CONFIGURATION.md` for complete documentation.

---

## External Submodule

`externals/vnstock-etl` is a Git submodule. After cloning, run `nx run omni:init` to initialize it. Do not edit files inside `externals/` directly unless working specifically on the ETL pipeline.

---

## Common Workflows

### Add a Python dependency

```bash
nx run analyzer:add --name="sqlalchemy[asyncio]>=2.0"
nx run ingestor:add --name="aiokafka>=0.11"
# uv updates uv.lock automatically — no separate lock step needed
```

### Run all tests

```bash
nx run-many -t test
```

### Run affected tests only (after changes)

```bash
nx affected -t test
```

### Lint and format Python apps

```bash
nx lint analyzer && nx format analyzer
nx lint ingestor && nx format ingestor
```

### Apply a new database migration

Add `database/migrations/V<N>__<description>.sql` following the existing naming convention. Flyway picks it up automatically on next platform startup.

---

## What NOT to do

- Do not run `gradle`, `uvicorn`, `pytest`, `uv`, or `kafka-*` CLI tools directly — always go through `nx`.
- Do not use `npx` — use locally installed binaries via `nx` targets only.
- Do not run `npm run dev` or any long-running watcher commands in the agent — tell the user to run them manually.
- Do not commit secrets. All credentials in `docker-compose.yaml` are for local dev only.
- Do not modify files inside `externals/vnstock-etl/` unless explicitly working on the ETL submodule.
- Do not add shared libraries to `packages/` without first discussing the dependency graph impact with the user.
- Do not import cloud-vendor SDKs (boto3 session with hardcoded regions, AWS-specific clients, etc.) directly in handler business logic — always go through the port interface.
- Do not call Kafka broker directly from `handlers/` — always use the `EventConsumer` port.
