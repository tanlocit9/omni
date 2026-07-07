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
              [ Kafka Topic: stock-sync ]
                           │
                           ▼ (Payload: {"symbol": "XYZ", "limit": 10})
 ┌────────────────────────────────────────────────────────┐
 │                   ingestor (Python)                    │
 │                                                        │
 │  1. Download historical `parquet/XYZ.parquet` from S3  │
 │  2. Fetch recent incremental records                   │
 │  3. Merge and deduplicate by date                      │
 │  4. Stream updated `.parquet` back to S3               │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           ▼ (Payload: {"symbol": "XYZ", "status": "success", ...})
           [ Kafka Topic: stock-sync-status ]
```

### Kafka Interface Payloads

#### 1. Inbound Topic: `stock-sync`

Expected message payload:

```json
{
  "symbol": "STB",
  "limit": 50
}
```

#### 2. Outbound Topic: `stock-sync-status`

Published payload on processing complete:

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

## ⚙️ Configuration (Environment Variables)

The application utilizes the following environment variables, supported by a local `.env` file:

| Variable           | Default Value       | Description                              |
| :----------------- | :------------------ | :--------------------------------------- |
| `KAFKA_BOOTSTRAP`  | `localhost:9092`    | Bootstrap server hosts for Kafka         |
| `SYNC_TOPIC`       | `stock-sync`        | Kafka topic containing sync requests     |
| `STATUS_TOPIC`     | `stock-sync-status` | Kafka topic to publish results to        |
| `MINIO_ENDPOINT`   | `localhost:9000`    | S3-compatible object storage server host |
| `MINIO_ACCESS_KEY` | `minioadmin`        | Credentials username for MinIO/S3        |
| `MINIO_SECRET_KEY` | `minioadmin`        | Credentials password for MinIO/S3        |
| `MINIO_BUCKET`     | `stock-data`        | S3 bucket containing parquet files       |
| `PARQUET_PREFIX`   | `parquet/`          | Directory path inside bucket             |

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
