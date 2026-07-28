# Omni Monorepo Workspace

Omni is an **Nx 22.5** monorepo for stock analytics and data integration. It coordinates a Java Platform API, Python analytical API, Python event ingestor, PostgreSQL, Kafka, and MinIO/S3-compatible object storage.

For detailed system design, service boundaries, Kafka workflow, deployment architecture, S3 path rules, and guardrails, see [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Workspace Layout

```text
omni/
├── apps/
│   ├── core/          # Java 21 / Spring Boot 4 — Platform API, scheduler, orchestration
│   ├── analyzer/      # Python 3.14 / FastAPI — Analytical API boundary
│   └── ingestor/      # Python 3.14 / Async Event Worker — Parquet Data Lake Sync
├── configs/shared/    # Shared Kafka topic and S3 path configuration
├── database/
│   └── migrations/    # Flyway SQL migrations
├── externals/         # Git submodules and external references
├── libs/              # Shared libraries
├── docker-compose.yaml
├── docker-compose.infra.yaml
├── docker-compose.services.yaml
├── nx.json
├── package.json
└── project.json
```

---

## Services

| Project | Path | Purpose |
| :--- | :--- | :--- |
| `platform` | `apps/core` | Java/Spring Platform API. Owns orchestration, scheduler jobs, database migrations, Kafka command production, and Kafka status consumption. |
| `analyzer` | `apps/analyzer` | Python/FastAPI analytical API boundary. Does not own stock-sync execution or direct stock-price persistence. |
| `ingestor` | `apps/ingestor` | Python async worker. Consumes stock-sync messages, fetches market data, writes Parquet snapshots, and publishes job status. |
| `py-common` | `libs/py-common` | Shared Python runtime, storage, messaging, and configuration utilities. |

---

## Prerequisites

- Node.js 18+ or 20+ and npm.
- Java JDK 21.
- Python 3.14+.
- `uv` for Python dependency and environment management.
- Docker and Docker Compose.

---

## Quick Start

```bash
git clone --recursive <repository-url>
cd omni
npm install
cp .env.example .env
nx run omni:init
```

Synchronize Python environments:

```bash
nx run analyzer:sync
nx run ingestor:sync
nx run py-common:sync
```

Run all applications together:

```bash
nx run omni:dev
```

Start only local infrastructure:

```bash
docker compose -f docker-compose.infra.yaml up -d
```

Start the complete Docker stack:

```bash
docker compose --env-file .env up -d
```

---

## Nx Commands

Nx is the canonical entry point for workspace tasks. Inspect targets before running unfamiliar project operations:

```bash
nx show project <project-name>
```

Common targets:

```bash
nx run platform:serve
nx run platform:build
nx run platform:test

nx run analyzer:serve
nx run analyzer:test
nx run analyzer:lint
nx run analyzer:format
nx run analyzer:debug

nx run ingestor:serve
nx run ingestor:test
nx run ingestor:lint
nx run ingestor:format

nx run py-common:test
nx run py-common:lint
nx run py-common:format
```

Python dependency operations also go through Nx:

```bash
nx run analyzer:add --name="numpy>=1.26.0"
nx run ingestor:remove --name="requests"
nx run <project-name>:sync
nx run <project-name>:lock
```

---

## Local Infrastructure

| Service | Local Port | Endpoint | Credentials |
| :--- | :--- | :--- | :--- |
| PostgreSQL 16 | `5432` | `jdbc:postgresql://localhost:5432/omni` | `postgres` / `postgres` |
| MinIO | `9000`, `9001` | [http://localhost:9001](http://localhost:9001) | `minioadmin` / `minioadmin` |
| pgAdmin 4 | `5050` | [http://localhost:5050](http://localhost:5050) | `admin@admin.com` / `admin` |
| Kafka | `9092` | `PLAINTEXT://localhost:9092` | No authentication for local development |

Local defaults are stored in `.env.example`. Deployment placeholders are stored in `.env.deploy.example`.

---

## Kafka Topic Overview

Canonical topic configuration lives in [configs/shared/topics.yaml](configs/shared/topics.yaml).

| Topic | Direction from Ingestor | Description |
| :--- | :--- | :--- |
| `topic-sync-stock-prices` | Inbound | Stock-price sync command from Platform. |
| `topic-sync-symbols` | Inbound | Symbol-master sync command from Platform. |
| `topic-sync-job-status` | Outbound | Job status result from Ingestor to Platform. |
| `topic-upsert-symbols` | Outbound | Full symbol snapshot from Ingestor to Platform. |

When changing a Kafka topic, schema, serialization rule, validation rule, field meaning, or error-handling contract, update both producer and consumer sides together, including tests and documentation. See [ARCHITECTURE.md](ARCHITECTURE.md#kafka-topics-and-contracts).

---

## S3 Data Lake Paths

Canonical path configuration lives in [configs/shared/s3-paths.yaml](configs/shared/s3-paths.yaml). The stock-data bucket uses lowercase, centralized paths such as:

```text
symbols/hose.parquet
eod/hose/hpg.parquet
```

Kafka messages must not include bucket or object path metadata. Consumers derive storage paths from shared path builders. See [ARCHITECTURE.md](ARCHITECTURE.md#s3-data-lake-structure).

---

## Database Migrations

Flyway migrations are stored in `database/migrations/V*__*.sql` and are applied by Platform on startup.

Before adding a migration:

1. Check the current highest migration version in `database/migrations/`.
2. Add the next version using `V<N>__<description>.sql`.
3. Use explicit column types, constraints, indexes, timestamps, and foreign-key behavior.
4. Test against a development database before deployment.

---

## Important References

- [ARCHITECTURE.md](ARCHITECTURE.md) — detailed architecture, service boundaries, Kafka workflow, deployment model, S3 rules, and guardrails.
- [AGENTS.md](AGENTS.md) — repository workflow and agent rules.
- [apps/analyzer/README.md](apps/analyzer/README.md) — Analyzer-specific notes.
- [apps/ingestor/README.md](apps/ingestor/README.md) — Ingestor-specific notes.
- [configs/shared/topics.yaml](configs/shared/topics.yaml) — Kafka topic configuration.
- [configs/shared/s3-paths.yaml](configs/shared/s3-paths.yaml) — S3 path configuration.
