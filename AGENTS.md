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

This is an **Nx 22.5 monorepo** named `omni`. It contains two applications and no shared libraries yet.

```
omni/
├── apps/
│   ├── core/          # Java 21 / Spring Boot 4 — file storage & platform API
│   └── analytics/     # Python 3.14 / FastAPI — Vietnamese stock analytics
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

### Run both apps together (development)
```bash
nx run omni:dev
# Starts platform (Java) and analytics (Python) concurrently with hot-reload
```

### Infrastructure only
```bash
docker compose up -d
# Starts: PostgreSQL 16 (5432), MinIO (9000/9001), pgAdmin (5050)
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
- Thumbnailator (image processing)
- Lombok
- Build: Gradle 8 via `@nx/gradle` plugin

### Architecture
Spring Modulith modular monolith. Package layout under `com.omni.platform`:

```
application/
  controllers/
    StorageController.java   # REST controller for file storage API (upload/delete/etc.)
  dtos/
    FileDeleteResult.java    # DTO for file deletion response
    FileUploadResult.java    # DTO for file upload response
  usecases/
    FileUseCase.java         # Interface for file storage operations
    FileUseCaseService.java  # Implementation of FileUseCase business logic
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
  thumbails/       # Thumbnail generation module
```

Key patterns:
- **Ports & Adapters**: Storage operations are defined as port interfaces; modules implement them.
- **Registry pattern**: `StorageProviderRegistry` resolves the correct adapter at runtime.
- **Event-driven async**: `@EnableAsync` + Spring events for post-upload processing.
- **Shared module**: `core` is declared as a shared module in `@Modulith`.

### Nx targets
```bash
nx serve platform                          # bootRun with dev profile (default)
nx serve platform --configuration=prod     # bootRun with prod profile
nx build platform                          # Gradle build
nx test platform                           # JUnit 5 tests
```

### Configuration files
- `apps/core/src/main/resources/application.yaml` — base config
- `apps/core/src/main/resources/application-dev.yaml` — dev profile (local DB, MinIO)
- `apps/core/src/main/resources/application-test.yaml` — test profile (H2 in-memory)

### Database
- PostgreSQL 16 in production/dev; H2 in-memory for tests
- Migrations live in `database/migrations/V*.sql` (V1–V12) as Flyway SQL scripts:
  - `V1__create_users.sql`: Creates tables for user accounts and management.
  - `V2__create_reference_data.sql`: Handles static lookup or reference data.
  - `V3__create_stocks.sql`: Creates tables for listing public companies and stock tickers.
  - `V4__create_company_info.sql`: Defines schemas for company metadata and profiles.
  - `V5__create_price_data.sql`: Holds historical stock price/quote transactions.
  - `V6__create_financial_statements.sql`: Represents balance sheets, income statements, and cash flows.
  - `V7__create_financial_ratios.sql`: Stores computed financial and fundamental ratios.
  - `V8__create_technical_indicators.sql`: Stores technical analysis indicators (moving averages, RSI, etc.).
  - `V9__create_events_news.sql`: Stores news articles, corporate events, and market announcements.
  - `V10__create_portfolio.sql`: Tracks user investment portfolios and transactions.
  - `V11__create_screener_alerts.sql`: Configures watchlists and customized screener alert rules.
  - `V12__create_sync_ops.sql`: Audits and tracks background ETL sync operation metadata.
- Schema covers: users, reference data, stocks, company info, price data, financial statements, financial ratios, technical indicators, events/news, portfolio, screener alerts, sync operations

---

## App: `analytics` (apps/analytics)

### Stack
- Python 3.14+, FastAPI 0.128.8
- Uvicorn with hot-reload (`uvicorn-hmr`)
- Package manager: `uv`
- vnstock 0.2.9.2.3 (Vietnamese stock market data)
- httpx + requests (HTTP clients)
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
    database.py               # Database engine, session maker, and Base model setup
  dtos/stock/
    sync_stock_dto.py         # Pydantic DTOs for stock syncing
  models/
    models.py                 # SQLAlchemy declarative models representing the database schema
  providers/
    stock_provider.py         # FastAPI Depends() providers (DI for DB sessions/services)
  repositories/
    stock_prices_repository.py # Repository managing stock price data persistence
  scripts/
    gen_models.py             # Script to automatically generate/update database models
  services/
    stock_service.py          # Business logic for stock analytics & data fetching
main.py                       # FastAPI app entry point, router registration
debug.py                      # Debug entry point
```

Key patterns:
- **Dependency injection**: FastAPI `Depends()` wires providers into controllers.
- **Async endpoints**: Use `async def` for all route handlers and service methods.
- **Versioned routing**: All routes are prefixed `/v1`.

### Nx targets
```bash
nx serve analytics          # uvicorn-hmr with hot-reload
nx test analytics           # pytest with coverage
nx lint analytics           # ruff check
nx format analytics         # ruff format
nx build analytics          # build dist package
nx debug analytics          # run debug.py
nx run analytics:add        # add a Python dependency
nx run analytics:remove     # remove a Python dependency
nx run analytics:lock       # update uv.lock
nx run analytics:sync       # sync virtualenv from lock file
```

### Code style
- Line length: 88 characters
- Ruff rules: E, F, UP, B, SIM, I (pycodestyle, Pyflakes, pyupgrade, bugbear, simplify, isort)
- All rules are auto-fixable via `nx format analytics`

### Testing
- Tests live in `apps/analytics/tests/`
- Coverage reports: `coverage/apps/analytics/`
- HTML test reports: `reports/apps/analytics/unittests/`
- Run single-pass (no watch): `nx test analytics`

---

## Infrastructure (docker-compose.yaml)

| Service   | Image            | Port(s)       | Credentials                        |
|-----------|------------------|---------------|------------------------------------|
| postgres  | postgres:16      | 5432          | user: postgres / pass: postgres / db: omni |
| minio     | quay.io/minio/minio | 9000, 9001 | user: minioadmin / pass: minioadmin |
| pgadmin   | dpage/pgadmin4   | 5050          | email: admin@admin.com / pass: admin |

All services have health checks and use named Docker volumes for persistence.

---

## External Submodule

`externals/vnstock-etl` is a Git submodule. After cloning, run `nx run omni:init` to initialize it. Do not edit files inside `externals/` directly unless working specifically on the ETL pipeline.

---

## Common Workflows

### Add a Python dependency to analytics
```bash
nx run analytics:add --name=<package>
# example: nx run analytics:add --name="sqlalchemy[asyncio]>=2.0"
# no need to run lock separately — uv updates uv.lock automatically on add
```

### Run all tests
```bash
nx run-many -t test
```

### Run affected tests only (after changes)
```bash
nx affected -t test
```

### Lint and format analytics
```bash
nx lint analytics
nx format analytics
```

### Apply a new database migration
Add a new file `database/migrations/V<N>__<description>.sql` following the existing naming convention. Flyway picks it up automatically on next app start.

---

## What NOT to do

- Do not run `gradle`, `uvicorn`, `pytest`, or `uv` directly — always go through `nx`.
- Do not run `npm run dev` or any long-running watcher commands in the agent — tell the user to run them manually.
- Do not commit secrets. The `docker-compose.yaml` credentials are for local dev only.
- Do not modify files inside `externals/vnstock-etl/` unless explicitly working on the ETL submodule.
- Do not add shared libraries to `packages/` without first discussing the dependency graph impact with the user.
