# Stock Analyzer Service (`apps/analyzer`)

The **Stock Analyzer Service** is a FastAPI application for analytical APIs.

Analyzer no longer owns stock price persistence. It does **not** connect directly to PostgreSQL, does **not** read stock prices from PostgreSQL, and does **not** write synchronized stock prices back to PostgreSQL. Stock synchronization is owned by the platform scheduler and downstream ingestion flow.

---

## 🚀 Current Responsibilities

- **FastAPI API surface**: Provides versioned HTTP endpoints under `/v1`.
- **Compatibility stock endpoints**: Existing stock endpoints remain available, but they report that direct PostgreSQL access has been removed.
- **No direct database access**: Analyzer has no SQLAlchemy engine, PostgreSQL session, repository, generated DB models, or database migration coupling.
- **Sync delegation**: On-demand stock sync should be triggered through the platform scheduler API instead of Analyzer.

---

## 📁 Package Layout

```text
apps/analyzer/
├── app/
│   ├── controllers/
│   │   └── v1/
│   │       └── stock.py              # API router for stock compatibility endpoints
│   ├── core/
│   │   └── __init__.py
│   ├── dtos/
│   │   ├── __init__.py
│   │   └── stock/
│   ├── providers/
│   │   └── stock_provider.py         # FastAPI dependency provider
│   └── services/
│       └── stock_service.py          # Stock compatibility behavior
├── tests/
│   └── test_hello.py                 # Service behavior tests
├── main.py                           # FastAPI application entry point
├── project.json                      # Nx targets
├── pyproject.toml                    # Python dependencies and tooling config
└── uv.lock                           # Locked exact package versions
```

---

## ⚡ API Endpoints

All endpoints are prefixed with `/v1`.

### 1. Stock Price History Compatibility Endpoint

Reports that Analyzer no longer reads stock prices directly from PostgreSQL.

- **URL**: `GET /v1/stocks/`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol, for example `STB`.
- **Example Request**:
  ```bash
  curl "http://localhost:8000/v1/stocks/?symbol=STB"
  ```

### 2. Stock Sync Compatibility Endpoint

Reports that Analyzer no longer writes stock prices directly to PostgreSQL and points callers to the platform scheduler API.

- **URL**: `POST /v1/stocks/sync`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol, for example `STB`.
- **Example Request**:
  ```bash
  curl -X POST "http://localhost:8000/v1/stocks/sync?symbol=STB"
  ```

---

## 💻 Development Commands

All development tasks should be run through **Nx** from the workspace root to take advantage of caching and standardized workspace environments:

```bash
# Serve FastAPI locally with hot-reloading
nx serve analyzer

# Run the pytest test suite
nx test analyzer

# Check code for linting violations via Ruff
nx lint analyzer

# Auto-format codebase using Ruff rules
nx format analyzer

# Run the local Python debug script
nx debug analyzer
```

---

## 📦 Managing Python Dependencies

Do not run `pip` or standard `uv` commands directly in the `apps/analyzer` directory. Instead, orchestrate dependencies via workspace-aware Nx commands:

```bash
# Add a package
nx run analyzer:add --name="pandas>=2.2.0"

# Remove a package
nx run analyzer:remove --name="pandas"

# Update lockfile
nx run analyzer:lock

# Sync Python virtual environment with locked dependencies
nx run analyzer:sync
```

---

## Database Boundary

Analyzer must remain independent from PostgreSQL persistence concerns.

Do not add:

- SQLAlchemy engines or sessions.
- PostgreSQL drivers such as `asyncpg` or `psycopg`.
- Database repositories for platform-owned tables.
- Generated ORM models from Flyway migrations.
- Direct writes to stock price tables.

If Analyzer needs persisted market data in the future, prefer one of these patterns:

1. Call a platform-owned API that exposes the required read model.
2. Read from a dedicated analytical store or data lake abstraction.
3. Add a clearly owned query boundary after agreeing on the service responsibility split.

Stock sync commands should continue to flow through the platform scheduler and ingestor contracts rather than direct Analyzer database writes.