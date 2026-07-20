# Stock Analyzer Service (`apps/analyzer`)

The **Stock Analyzer Service** is a FastAPI application and Kafka worker for analytical APIs and technical-indicator calculation.

Analyzer no longer owns stock-price persistence. It does **not** connect directly to PostgreSQL, does **not** read stock prices from PostgreSQL, and does **not** write synchronized stock prices back to PostgreSQL. Stock synchronization is owned by the Platform scheduler and downstream Ingestor flow.

Analyzer does own indicator calculation jobs from `topic-sync-indicators`: it reads EOD Parquet data from MinIO/S3, computes the complete supported indicator set, writes indicator Parquet output, and publishes job status back to Platform.

---

## Current Responsibilities

- **FastAPI API surface**: Provides versioned HTTP endpoints under `/v1`.
- **Compatibility stock endpoints**: Existing stock endpoints remain available, but they report that direct PostgreSQL access has been removed.
- **No direct database access**: Analyzer has no SQLAlchemy engine, PostgreSQL session, repository, generated DB models, or database migration coupling.
- **Indicator Kafka worker**: Consumes Platform-owned `topic-sync-indicators` jobs and publishes status to `topic-sync-job-status`.
- **MinIO/S3 analytical storage**: Reads EOD files and writes indicator files through shared `ParquetStorage`.
- **Infrastructure foundations**:
  - `KafkaEventPublisher` can publish JSON events to Kafka.
  - Shared storage adapters can read and write object-storage data.
- **Sync delegation**: On-demand stock sync should be triggered through the Platform scheduler API instead of Analyzer.

---

## Package Layout

```text
apps/analyzer/
├── app/
│   ├── adapters/
│   │   ├── kafka_publisher.py         # Kafka JSON publisher foundation
│   │   └── minio_storage.py           # MinIO read-only object storage foundation
│   ├── controllers/
│   │   └── v1/
│   │       └── stock.py               # API router for stock compatibility endpoints
│   ├── core/
│   │   └── __init__.py
│   ├── dtos/
│   │   └── stock/
│   ├── ports/
│   │   ├── event_publisher.py         # Publisher boundary
│   │   └── object_storage.py          # Object-storage boundary
│   ├── providers/
│   │   └── stock_provider.py          # FastAPI dependency provider
│   ├── calculations/
│   │   └── indicators.py              # MA20, MA50, RSI, MACD calculations
│   ├── indicators/
│   │   ├── handler.py                 # Reads EOD Parquet and writes indicator Parquet
│   │   ├── kafka.py                   # Kafka consumer/producer lifecycle
│   │   └── messages.py                # Indicator job/status contracts
│   ├── services/
│   │   └── stock_service.py           # Stock compatibility behavior
│   └── settings.py                    # Shared topic/S3 config loader and runtime settings
├── tests/
├── main.py                            # FastAPI application entry point
├── project.json                       # Nx targets
├── pyproject.toml                     # Python dependencies and tooling config
└── uv.lock                            # Locked exact package versions
```

---

## API Endpoints

All endpoints are prefixed with `/v1`.

### 1. Stock Price History Compatibility Endpoint

Reports that Analyzer no longer reads stock prices directly from PostgreSQL.

- **URL**: `GET /v1/stocks/`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol, for example `STB`.

```bash
curl "http://localhost:8000/v1/stocks/?symbol=STB"
```

### 2. Stock Sync Compatibility Endpoint

Reports that Analyzer no longer writes stock prices directly to PostgreSQL and points callers to the Platform scheduler API.

- **URL**: `POST /v1/stocks/sync`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol, for example `STB`.

```bash
curl -X POST "http://localhost:8000/v1/stocks/sync?symbol=STB"
```

Current response semantics:

- `accepted: false`
- message explains that stock sync must be triggered through Platform-owned scheduling.

---

## Shared Configuration

`app/settings.py` loads defaults from shared repository configuration without adding a YAML runtime dependency:

- `configs/shared/topics.yaml`
- `configs/shared/s3-paths.yaml`

### Kafka settings

Pydantic field names and environment-variable overrides:

| Field                     | Environment variable      | Default source                         |
| ------------------------- | ------------------------- | -------------------------------------- |
| `kafka_bootstrap`         | `KAFKA_BOOTSTRAP`         | `kafka.bootstrap-servers`              |
| `topic_sync_stock_prices` | `TOPIC_SYNC_STOCK_PRICES` | `kafka.topics.topic-sync-stock-prices` |
| `topic_sync_symbols`      | `TOPIC_SYNC_SYMBOLS`      | `kafka.topics.topic-sync-symbols`      |
| `topic_upsert_symbols`    | `TOPIC_UPSERT_SYMBOLS`    | `kafka.topics.topic-upsert-symbols`    |
| `topic_sync_job_status`   | `TOPIC_SYNC_JOB_STATUS`   | `kafka.topics.topic-sync-job-status`   |

### MinIO settings

| Field              | Environment variable | Default source                         |
| ------------------ | -------------------- | -------------------------------------- |
| `minio_endpoint`   | `MINIO_ENDPOINT`     | `min-io.endpoint`                      |
| `minio_access_key` | `MINIO_ACCESS_KEY`   | `min-io.access-key`                    |
| `minio_secret_key` | `MINIO_SECRET_KEY`   | `min-io.secret-key`                    |
| `minio_bucket`     | `MINIO_BUCKET`       | `min-io.bucket` or `stock-data.bucket` |
| `minio_secure`     | `MINIO_SECURE`       | `false`                                |

### S3 path builders

Analyzer uses the same path-builder convention as Ingestor:

```python
settings.get_symbols_path("HOSE")                  # symbols/hose.parquet
settings.get_eod_path("HOSE", "HPG")               # eod/hose/hpg.parquet
settings.get_indicators_path("1d", "HOSE", "HPG")  # indicators/1d/hose/hpg.parquet
```

Exchange names and ticker codes are lowercased in object names. Keep official uppercase symbols in API responses and metadata. Indicator path construction validates canonical timeframe values and v1 currently enables only `1d`.

### Indicator Kafka runtime

Analyzer starts the indicator Kafka worker during FastAPI startup by default. Disable it for tests or local API-only runs with:

```bash
ANALYZER_INDICATOR_KAFKA_ENABLED=false nx serve analyzer
```

Contract summary:

| Topic | Direction | Purpose |
| --- | --- | --- |
| `topic-sync-indicators` | Consume | Platform requests full-series indicator calculation for one `symbolKey`. |
| `topic-sync-job-status` | Publish | Analyzer reports `SUCCESS` or `ERROR` with `recordsProcessed`. |

---

## Infrastructure Foundations

### `KafkaEventPublisher`

`app/adapters/kafka_publisher.py` provides `publish_json(topic, payload)` using `aiokafka.AIOKafkaProducer`.

Current maturity:

- available as an adapter;
- not currently called by the stock compatibility endpoints;
- should only be used after an explicit Platform-owned event contract is agreed.

### `MinioObjectStorage`

`app/adapters/minio_storage.py` provides `read_bytes(object_name)` using the configured MinIO bucket.

Current maturity:

- available as a read-only adapter;
- not currently called by `StockService`;
- future analytical reads should go through this port or another agreed analytical boundary.

---

## Database Boundary

Analyzer must remain independent from PostgreSQL persistence concerns.

Do not add:

- SQLAlchemy engines or sessions.
- PostgreSQL drivers such as `asyncpg` or `psycopg`.
- Database repositories for Platform-owned tables.
- Generated ORM models from Flyway migrations.
- Direct writes to stock-price tables.

If Analyzer needs persisted market data in the future, prefer one of these patterns:

1. Read analytical data from MinIO/data-lake paths through an object-storage port.
2. Call a Platform-owned API that exposes the required read model.
3. Add a clearly owned analytical query boundary after agreeing on the service responsibility split.

Stock sync commands should continue to flow through the Platform scheduler and Ingestor contracts rather than direct Analyzer database writes.

---

## Indicator Calculation Boundary

Indicator jobs must keep producer and consumer contracts synchronized:

- Platform produces `IndicatorJobMessage` to `topic-sync-indicators`.
- Analyzer consumes that message and validates `timeframe`, `symbolKey`, and the complete v1 indicator set.
- Analyzer reads `eod/{exchange}/{code}.parquet`, writes `indicators/1d/{exchange}/{code}.parquet`, and publishes `recordsProcessed` status.
- Platform consumes `topic-sync-job-status`, maps `ERROR` to failed execution state, and aggregates parent executions.

Do not add bucket or object-name routing fields to indicator Kafka messages; object paths are derived from shared path builders.

---

## Development Commands

All development tasks should be run through **Nx** from the workspace root:

```bash
nx serve analyzer
nx test analyzer
nx lint analyzer
nx format analyzer
nx debug analyzer
```

Do not run `pip`, `uv`, `pytest`, or `uvicorn` directly. Use Nx targets such as:

```bash
nx run analyzer:add --name="pandas>=2.2.0"
nx run analyzer:remove --name="pandas"
nx run analyzer:lock
nx run analyzer:sync
```
