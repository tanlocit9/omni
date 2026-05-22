# Omni Monorepo Workspace

Welcome to **Omni**, a high-performance monorepo utilizing an **Nx 22.5** workspace. This repository hosts a modular and analytical stock platform built on modern enterprise frameworks.

The workspace comprises two primary applications:
*   **Platform API (`apps/core`)**: Java 21 / Spring Boot 4 Modular Monolith (Spring Modulith) managing platform logic, user security, object storage, and metadata orchestration.
*   **Analytics API (`apps/analytics`)**: Python 3.14 / FastAPI analytics service specializing in Vietnamese stock market data processing and analysis.

---

## 🛠️ Prerequisites

Before setting up the project, ensure your local development machine has the following tools installed:

1.  **Git**: For cloning the repository and managing submodules.
2.  **Node.js (v18.x or v20.x+) & npm**: Used to run Nx workspace orchestrators, formatters, and dependency checkers.
3.  **Java Development Kit (JDK) 21**: Recommended Adoptium Temurin JVM for building and running the platform backend.
4.  **Python 3.14+**: Required for the analytics environment.
5.  **`uv`**: A fast, modern Python package installer and resolver. Install it via:
    *   *macOS/Linux:* `curl -LsSf https://astral.sh/uv/install.sh | sh`
    *   *Windows (PowerShell):* `powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"`
6.  **Docker & Docker Compose**: Necessary to spin up local database and object storage infrastructure.

---

## 🚀 Getting Started (First-Time Setup)

Setting up the entire project is streamlined through Nx targets. Follow these steps to get everything up and running:

### 1. Clone the Repository
Clone the project along with its submodules:
```bash
git clone --recursive <repository-url>
cd omni
```

### 2. Install Workspace Tooling (Node/Nx)
Install root dependencies that manage Nx commands:
```bash
npm install
```

### 3. Initialize the Workspace
Run the automated initialization target to sync the submodules and spin up Docker containers (PostgreSQL, MinIO, pgAdmin) in the background:
```bash
nx run omni:init
```
*(This command runs `git submodule sync/update` and starts up Docker Compose in detached mode).*

### 4. Setup Python Environment
Create the Python virtual environment and synchronize dependencies for the `analytics` application:
```bash
nx run analytics:sync
```

---

## 💻 Running the Applications

### Run both apps concurrently (Development Mode)
To boot up both the **Java Platform API** and the **FastAPI Analytics API** simultaneously with hot-reload enabled, run:
```bash
nx run omni:dev
```
This leverages the `concurrently` tool to stream logs from both backend processes to a single terminal window with distinct prefixes (`[JAVA]` and `[ANALYTICS]`).

### Run applications individually

#### ☕ Java Platform API (`apps/core`)
```bash
# Serve with the dev profile (default)
nx serve platform

# Serve with the prod profile
nx serve platform --configuration=prod

# Run JUnit 5 test suite
nx test platform

# Build executable JAR
nx build platform
```

#### 🐍 Python Analytics API (`apps/analytics`)
```bash
# Serve FastAPI with hot-reloading (uvicorn-hmr)
nx serve analytics

# Run pytest suite with coverage reports
nx test analytics

# Format codebase using Ruff
nx format analytics

# Lint codebase using Ruff
nx lint analytics
```

---

## 🗄️ Local Infrastructure & Credentials

Local database and cloud infrastructure services are automatically managed using Docker Compose. Here are the access points and default credentials:

| Service | Image | Local Port | Access Endpoint / GUI | Credentials |
| :--- | :--- | :--- | :--- | :--- |
| **PostgreSQL 16** | `postgres:16` | `5432` | `jdbc:postgresql://localhost:5432/omni` | **User:** `postgres`<br>**Password:** `postgres`<br>**Database:** `omni` |
| **MinIO Object Storage** | `quay.io/minio/minio` | `9000` (API)<br>`9001` (Console) | [http://localhost:9001](http://localhost:9001) | **User:** `minioadmin`<br>**Password:** `minioadmin` |
| **pgAdmin 4** | `dpage/pgadmin4` | `5050` (Web UI) | [http://localhost:5050](http://localhost:5050) | **Email:** `admin@admin.com`<br>**Password:** `admin` |

---

## 🔄 Database Migrations (Flyway)

The database schema is managed via **Flyway SQL migrations**. 
*   Migration files are located in `database/migrations/V*__*.sql`.
*   Migrations are automatically executed when the **Java Platform API (`platform`)** is served or built.
*   **To add a new migration**: Simply place a new SQL script in `database/migrations/` using the convention: `V<N>__<description>.sql` (e.g. `V13__add_new_table.sql`). Flyway will apply it automatically on the next server startup.

---

## 📦 Analytics Python Package Management

The Python analytics workspace uses Astral's **`uv`** package manager. Do not run `uv` or `pip` directly; instead, use the provided Nx scripts:

*   **Add a dependency**:
    ```bash
    nx run analytics:add --name="<package-name-with-version>"
    # Example: nx run analytics:add --name="pandas>=2.2.0"
    ```
*   **Remove a dependency**:
    ```bash
    nx run analytics:remove --name="<package-name>"
    ```
*   **Synchronize venv with lock file**:
    ```bash
    nx run analytics:sync
    ```
*   **Generate/update the lock file**:
    ```bash
    nx run analytics:lock
    ```

---

## ❓ Troubleshooting

### 1. Docker Port Conflicts
If you see an error like `bind: address already in use` when initializing the workspace:
*   Ensure you do not have another local instance of PostgreSQL running on port `5432`.
*   Ensure port `9000` or `9001` is not being occupied by another service or an active MinIO daemon.
*   Stop conflicting services, or run `docker compose down` inside `omni` to purge existing dead containers.

### 2. Git Submodules are missing or out of sync
If files in `externals/vnstock-etl` are blank or throw import errors during startup:
*   Run the submodule updater command manually:
    ```bash
    git submodule update --init --recursive
    ```

### 3. Python Virtual Environment (`.venv`) issues
If the analytics service fails due to missing Python libraries or pathing problems:
*   Clear the local virtual environment and rebuild it:
    ```bash
    rm -rf apps/analytics/.venv
    nx run analytics:sync
    ```
*   Verify that `uv` is installed and globally discoverable by your system.