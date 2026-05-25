# Kafka Stock Synchronization Plan (Stateless MinIO & Bidirectional Tracking Loop)

This document outlines the design and implementation details for integrating Kafka to drive stock data synchronization. The Python sync service upserts fetched stock data directly into Parquet files in MinIO without database access, and reports execution status back to Java via Kafka to maintain synchronization tracking.

## Bidirectional Architecture Event Loop

```
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. GET /api/stocks/sync?symbol=XYZ                    │
 │  2. Query `update_log` / `sync_config` -> Calc `limit`  │
 │  3. Publish `stock-sync` command                       │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           │ Payload: {"symbol": "XYZ", "limit": 5}
                           ▼
                   [ Topic: stock-sync ]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                      sync app (Python)                 │
 │                                                        │
 │  1. Download existing Parquet from MinIO               │
 │  2. Fetch recent 5 records from VNDirect API           │
 │  3. Perform in-memory merge and drop duplicate dates   │
 │  4. Upload updated Parquet back to MinIO               │
 │  5. Publish `stock-sync-status` response               │
 └─────────────────────────┬──────────────────────────────┘
                           │
                           │ Payload: {"symbol": "XYZ", "status": "success", "inserted": 5, ...}
                           ▼
                [ Topic: stock-sync-status ]
                           │
                           ▼
 ┌────────────────────────────────────────────────────────┐
 │                      platform (Java)                   │
 │                                                        │
 │  1. Consume from `stock-sync-status`                   │
 │  2. Insert tracking log into `update_log` table       │
 │  3. Update `last_success` in `sync_config` table       │
 └────────────────────────────────────────────────────────┘
```

---

## 1. Java Platform (`apps/core`) Configuration

### Gradle Dependencies
Add Spring Kafka support to `apps/core/build.gradle.kts`:
```kotlin
implementation("org.springframework.kafka:spring-kafka")
```

### Application Configuration
Add Kafka Bootstrap, Producer, and Consumer configurations in `apps/core/src/main/resources/application-dev.yaml`:
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      group-id: platform-group
      key-deserializer: org.apache.kafka.common.deserialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.deserialization.StringDeserializer
      auto-offset-reset: earliest
```

### Database Tables (Pre-existing in Flyway V12)
* `sync_config`: Holds metadata about schedules and configurations for each source/table.
* `update_log`: Records each execution run, tracking status, duration, records inserted/updated/skipped, and error messages.

### New Java Classes
1. **Entities**: 
   * `SyncConfig` mapping to `sync_config`.
   * `UpdateLog` mapping to `update_log`.
2. **Repositories**:
   * `SyncConfigRepository`.
   * `UpdateLogRepository`.
3. **Kafka Messages (DTOs)**:
   * `StockSyncRequest`: `{"symbol": "XYZ", "limit": 10}`
   * `StockSyncResponse`: `{"symbol": "XYZ", "status": "success", "recordsInserted": 10, "recordsUpdated": 0, "recordsSkipped": 0, "totalRecords": 10, "durationMs": 1250, "errorMessage": null}`
4. **Producer Service**: `StockSyncProducer` class to compute limits and publish `StockSyncRequest` to `stock-sync`.
5. **Consumer Listener**: `StockSyncResponseListener` to consume responses from `stock-sync-status` and record them in the database.
6. **Controller**: `StockSyncController` to expose `POST /api/stocks/sync` trigger endpoint.

---

## 2. Python Sync Application (`apps/sync`)

A new database-free Python microservice.

### Dependencies (`pyproject.toml`)
* `aiokafka` (for async Kafka consuming & producing)
* `httpx` (VNDirect API client calls)
* `pandas` + `pyarrow` (Parquet formatting and writing)
* `minio` (S3 SDK to download/upload files from/to MinIO)
* `dotenv` (environment configuration)

### Sync & Direct MinIO Upsert Logic
For a given Kafka message `{"symbol": "XYZ", "limit": 50}`:

1. **Read Existing Data**:
   * Attempt to fetch the existing Parquet file from MinIO at `stock-data/parquet/XYZ.parquet`.
   * If found, read into a `pandas.DataFrame` using `pd.read_parquet()`.

2. **Fetch New Data**:
   * Fetch the last `limit` records from VNDirect. If the file did not exist, fetch a wider history or the requested limit.

3. **In-Memory Upsert**:
   * Convert newly fetched records to a DataFrame.
   * Concatenate the existing and new DataFrames.
   * Remove duplicates using `drop_duplicates(subset=["date"])` (preserving the newest updates).
   * Sort the final DataFrame by `date` ascending.

4. **Upload Back to MinIO**:
   * Serialize the updated DataFrame to a Parquet byte buffer.
   * Upload the buffer back to MinIO, overwriting `parquet/XYZ.parquet`.

5. **Publish Success Event**:
   * Publish a status JSON to the `stock-sync-status` topic detailing the results (number of records fetched, duration, status, etc.).

---

## 3. Nx Tasks Integration

* Configure `apps/sync/project.json` with standard tasks: `serve` (run consumer), `lint`, `format`, `test`.
* Modify workspace target in root `project.json` if required to startup all services concurrently under development.