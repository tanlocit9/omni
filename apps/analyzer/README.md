# Stock Analyzer Service (`apps/analyzer`)

Welcome to the **Stock Analyzer Service**, a high-performance Python API designed to serve stock pricing data and orchestrate synchronous on-demand updates from external APIs directly to the database.

---

## 🚀 Key Features

- **FastAPI Framework**: Utilizing async API endpoints with type safety via Pydantic and DI using FastAPI `Depends()`.
- **Database Integration**: Interacts directly with the PostgreSQL database using **SQLAlchemy 2.0 (Async Engine)** and standard Repository/Service patterns.
- **On-Demand Sync**: Synchronizes ticker history directly from VNDirect to the `stock_prices` PostgreSQL table, complete with `ON CONFLICT DO NOTHING` handling to prevent duplication.
- **Boilerplate Model Generation**: Employs an automated script (`app/scripts/gen_models.py`) to generate SQLAlchemy declarative models directly reflecting Flyway database migrations.

---

## 📁 Package Layout

```
apps/analyzer/
├── app/
│   ├── clients/
│   │   └── vndirect_client.py        # External VNDirect HTTP API Client
│   ├── controllers/v1/
│   │   └── stock.py                  # API Router (exposes endpoints)
│   ├── core/
│   │   └── database.py               # Async engine and Base metadata definition
│   ├── dtos/stock/
│   │   └── sync_stock_dto.py         # Request and Response payloads
│   ├── models/
│   │   └── models.py                 # Auto-generated SQLAlchemy Models
│   ├── providers/
│   │   └── stock_provider.py         # FastAPI dependency injection suppliers
│   ├── repositories/
│   │   └── stock_prices_repository.py# Database operations for stock prices
│   ├── scripts/
│   │   └── gen_models.py             # SQLAlchemy models generator script
│   └── services/
│       └── stock_service.py          # Business logic for stock fetching and sync
├── tests/
│   └── test_hello.py                 # Service tests
├── main.py                           # Application entry point
├── project.json                      # Nx targets definition
├── pyproject.toml                    # Poetry/PEP 518 dependencies config
└── uv.lock                           # Locked exact package versions
```

---

## ⚡ API Endpoints

All endpoints are prefixed with `/v1`.

### 1. Retrieve Stock Price History

Returns the recorded historical price list for a specified ticker.

- **URL**: `GET /v1/stocks/`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol (e.g. `STB`).
- **Example Request**:
  ```bash
  curl "http://localhost:8000/v1/stocks/?symbol=STB"
  ```

### 2. Synchronize Ticker On-Demand

Pulls missing historical quotes since the last recorded date from the VNDirect API and upserts them directly to the database.

- **URL**: `POST /v1/stocks/sync`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol (e.g. `STB`).
- **Example Request**:
  ```bash
  curl -X POST "http://localhost:8000/v1/stocks/sync?symbol=STB"
  ```

---

## 💻 Development Commands

All development tasks should be run through **Nx** from the workspace root to take advantage of caching and standardized workspace environments:

```bash
# Serve FastAPI locally with hot-reloading (uvicorn-hmr)
nx serve analyzer

# Run the pytest test suite
nx test analyzer

# Check code for linting violations via Ruff
nx lint analyzer

# Auto-format codebase using Ruff rules
nx format analyzer

# Run the local python debug script
nx debug analyzer
```

---

## 📦 Managing Python Dependencies

Do not run `pip` or standard `uv` commands directly in the `apps/analyzer` directory. Instead, orchestrate dependencies via workspace-aware Nx commands:

```bash
# Add a package (e.g., pandas)
nx run analyzer:add --name="pandas>=2.2.0"

# Remove a package
nx run analyzer:remove --name="pandas"

# Update lockfile (uv.lock)
nx run analyzer:lock

# Sync python virtual environment with locked dependencies
nx run analyzer:sync
```

---

## 🛠️ Auto-Generating Database Models

If database migrations (`database/migrations/`) are added or modified, update the SQLAlchemy models in `app/models/models.py` to match:

```bash
nx run analyzer:run-script app/scripts/gen_models.py
```

_(This scans the database schema and rewrites declarative classes, ensuring perfect alignment between Java Flyway migrations and Python SQLAlchemy models)._
