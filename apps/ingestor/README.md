# Stock Ingestor Service (`apps/ingestor`)

The **Stock Ingestor Service** is an asynchronous, event-driven Python microservice responsible for maintaining the historical stock price Parquet files inside S3-compatible Object Storage (Data Lake).

It acts as a worker in a bidirectional sync loop, processing synchronization commands offloaded by the Java Platform API.

---

## 🚀 Key Features

- **Asynchronous Kafka Worker**: Uses `aiokafka` to consume sync requests concurrently and publish completion metrics without blocking the main event loop.
- **Direct Object Storage Updates**: Connects to S3-compatible object storage (MinIO locally) using the `minio` SDK to stream files.
- **In-Memory Pandas Processing**: Merges old parquet histories with newly fetched incremental rows using `pandas.concat()` and performs highly efficient, in-memory deduplication via `drop_duplicates(subset=["date"])`.
- **Zero Local Disk Usage**: Reads existing Parquet files into memory, performs deduplication, and streams updated Parquet buffers back to MinIO completely stateless.

---

## 🔄 Ingestion & Sync Pipeline

```
        [ Kafka Topic: topic-sync-stock-prices ]
                           │
                           ▼ (Payload: {"symbolKey": "HOSE:HPG", "jobDefinitionId": 1, "executionId": 10})
 ┌────────────────────────────────────────────────────────┐
 │                   ingestor (Python)                    │
 │                                                        │
 │  1. Derive object paths from shared S3 path builders   │
 │  2. Download eod/{exchange}/{code}.parquet from S3     │
 │  3. Fetch recent incremental records                   │
 │  4. Merge and deduplicate by date                      │
 │  5. Stream updated `.parquet` back to S3               │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           ▼ (Payload: {"executionId": 10, "status": "SUCCESS", ...})
          [ Kafka Topic: topic-sync-job-status ]
```

### Kafka Interface Payloads

Topic names and consumer groups are centralized in `configs/shared/topics.yaml`. The ingestor consumes stock-price and symbol sync commands and publishes job-status and symbol-upsert results. When changing a Kafka contract, update both producer and consumer documentation, code, and tests in the same change.

#### 1. Inbound Topic: `topic-sync-stock-prices`

Expected message payload:

```json
{
  "symbolKey": "HOSE:HPG",
  "jobDefinitionId": 1,
  "executionId": 10,
  "parentExecutionId": null
}
```

#### 2. Outbound Topic: `topic-sync-job-status`

Published payload on processing complete:

```json
{
  "jobDefinitionId": 1,
  "executionId": 10,
  "parentExecutionId": null,
  "status": "SUCCESS",
  "recordsProcessed": 50,
  "durationMs": 350,
  "message": null,
  "errorMessage": null
}
```

---

## ⚙️ Configuration (Environment Variables)

Ingestor loads stable defaults from `configs/shared/topics.yaml` and `configs/shared/s3-paths.yaml`, then applies runtime overrides from env files. Copy `.env.example` to `.env` for shared local settings, and copy `apps/ingestor/.env.example` to `apps/ingestor/.env` only when the ingestor needs service-specific overrides.

Use the flat shared env contract only:

| Variable | Default Value | Description |
| :------- | :------------ | :---------- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list for consumers and producers. |
| `MINIO_ENDPOINT` | `http://localhost:9000` | S3-compatible object storage endpoint. |
| `MINIO_ACCESS_KEY` | `minioadmin` | Object storage access key. |
| `MINIO_SECRET_KEY` | `minioadmin` | Object storage secret key. |
| `MINIO_BUCKET` | `stock-data` | Bucket containing shared Parquet files. |
| `KAFKA_RETRY_INTERVAL_SECONDS` | `3` | Retry interval for Kafka worker reconnection. |
| `DEFAULT_STOCK_SOURCE` | `VND` | Default upstream stock-data source. |

Topic names such as `topic-sync-stock-prices` and object paths such as `eod/hose/hpg.parquet` come from shared config files, not Kafka message fields. Do not add bucket or object-name routing fields to sync messages.

---

## 💻 Development Commands

Manage microservice tasks through **Nx** in the workspace root:

```bash
# Serve the event-driven consumer daemon
nx serve ingestor

# Run unit tests
nx test ingestor

# Run Ruff linter to verify code quality
nx lint ingestor

# Run Ruff formatter to clean code style
nx format ingestor
```

---

## 📦 Managing Python Dependencies

Dependencies are managed in a standardized workspace pattern using **`uv`**:

```bash
# Add a Python dependency
nx run ingestor:add --name="pandas>=2.2.0"

# Remove a Python dependency
nx run ingestor:remove --name="requests"

# Update lockfile (uv.lock)
nx run ingestor:lock

# Synchronize python virtualenv with locked dependencies
nx run ingestor:sync
```
