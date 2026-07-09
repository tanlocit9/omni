# Stock Data Sync Workflow Documentation

## Overview

The Stock Data Sync workflow is an event-driven, distributed system that automatically synchronizes historical stock price data from external APIs to MinIO object storage. The system uses a scheduled job architecture with Kafka-based async communication between the Platform API (Java) and Ingestor service (Python).

---

## Architecture Components

### 1. Platform API (Java/Spring Boot)
**Location**: `apps/core/src/main/java/com/omni/platform/modules/scheduler/`

**Key Components**:
- `SyncJobScheduler` — Scans for due jobs at fixed intervals
- `SyncStockPriceJobProducer` — Builds and publishes sync messages to Kafka
- `JobStatusConsumer` — Receives completion status from Ingestor
- `JobDefinitionRepository` — Manages job definitions
- `JobExecutionHistoryRepository` — Tracks execution history and offsets
- `SymbolRepository` — Retrieves symbols filtered by sector

### 2. Ingestor Service (Python)
**Location**: `apps/ingestor/app/handlers/stock_prices.py`

**Key Components**:
- `process_stock_price_message` — Main handler for stock price sync messages
- Stock clients (VNDirect, VCI) — External API integrations
- MinIO client — S3-compatible object storage operations
- Parquet handlers — Read/write/merge operations

### 3. Infrastructure
- **Kafka**: Message broker for async job distribution
- **PostgreSQL**: Job definitions, execution history, and symbol metadata
- **MinIO**: S3-compatible object storage for Parquet files

---

## End-to-End Workflow

### Phase 1: Job Scheduling

```
┌─────────────────────────────────────────┐
│      SyncJobScheduler                   │
│                                         │
│  @Scheduled(fixedDelay = 30000ms)       │
│                                         │
│  1. Query job_definition table          │
│     WHERE next_run <= NOW()             │
│     AND is_active = TRUE                │
│                                         │
│  2. For each due job:                   │
│     → Dispatch to appropriate producer  │
│                                         │
└─────────────────────────────────────────┘
```

**Database Query**:
```sql
SELECT * FROM job_definition 
WHERE next_run <= :now 
  AND is_active = TRUE
ORDER BY next_run ASC;
```

**Job Types**:
- `SYNC_STOCK_PRICE` → Stock price history sync
- `SYNC_SYMBOLS` → Symbol metadata sync

---

### Phase 2: Message Production

```
┌─────────────────────────────────────────────────────────┐
│         SyncStockPriceJobProducer                       │
│                                                         │
│  1. Create JobExecutionHistory record                   │
│     - status: PENDING                                   │
│     - started_at: now()                                 │
│                                                         │
│  2. Query symbols by sector filter                      │
│     - Extract sectors from job.configJson               │
│     - Query: symbol WHERE sector IN (...)               │
│                                                         │
│  3. For each symbol:                                    │
│     a. Calculate fromOffset                             │
│        → Query last successful sync offset              │
│        → SELECT new_offset FROM job_execution_history   │
│          WHERE job_id = :jobId                          │
│          AND meta_json->>'symbolKey' = :symbolKey       │
│          AND status = 'SUCCESS'                         │
│          ORDER BY finished_at DESC LIMIT 1              │
│                                                         │
│     b. Build Kafka message:                             │
│        {                                                │
│          "jobId": "uuid",                               │
│          "logId": "uuid",                               │
│          "source": "vnd",                               │
│          "symbolKey": "HOSE-HPG",                       │
│          "fromOffset": "2024-01-01T00:00:00Z",          │
│          "toOffset": "2024-12-31T23:59:59Z",            │
│          "metadata": {                                  │
│            "bucket": "stock-data",                      │
│            "objectName": "EOD/HOSE-HPG.parquet"         │
│          }                                              │
│        }                                                │
│                                                         │
│  4. Publish all messages to Kafka                       │
│     → Topic: topic-sync-stock-prices                    │
│     → Key: symbolKey (for partitioning)                 │
│                                                         │
│  5. Update job.next_run based on cron expression        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Configuration Options** (in `job_definition.config_json`):

```json
{
  "sector": ["FINANCIALS", "TECHNOLOGY"],
  "bucket": "stock-data",
  "objectName": "custom/path/{symbol}.parquet"
}
```

---

### Phase 3: Data Processing (Ingestor)

```
┌──────────────────────────────────────────────────────────┐
│      process_stock_price_message (Python)                │
│                                                          │
│  1. Parse Kafka message                                  │
│     - Extract: symbolKey, source, fromOffset, toOffset   │
│     - Parse dates: fromDate, toDate                      │
│                                                          │
│  2. Calculate fetch limit                                │
│     if fromOffset is None:                               │
│       limit = 10,000  # Full history                     │
│     else:                                                │
│       limit = (toDate - fromDate).days + 1               │
│                                                          │
│  3. Fetch new data from external API                     │
│     client = get_or_create_client(source)                │
│     records = await client.fetch_recent_stock(           │
│       symbol=code,                                       │
│       size=limit                                         │
│     )                                                    │
│     new_df = pd.DataFrame(records)                       │
│                                                          │
│  4. Download existing Parquet from MinIO                 │
│     object_name = f"EOD/{symbolKey}.parquet"             │
│     existing_df = read_existing_parquet(                 │
│       minio_client,                                      │
│       object_name,                                       │
│       bucket=bucket                                      │
│     )                                                    │
│                                                          │
│  5. Merge and deduplicate                                │
│     combined = pd.concat([existing_df, new_df])          │
│     combined = combined.drop_duplicates(subset=["date"]) │
│     combined = combined.sort_values("date")              │
│                                                          │
│  6. Upload updated Parquet to MinIO                      │
│     write_parquet_to_minio(                              │
│       minio_client,                                      │
│       combined,                                          │
│       object_name,                                       │
│       bucket=bucket                                      │
│     )                                                    │
│                                                          │
│  7. Build status message                                 │
│     status = {                                           │
│       "jobId": "uuid",                                   │
│       "logId": "uuid",                                   │
│       "symbolKey": "HOSE-HPG",                           │
│       "status": "success",                               │
│       "recordsInserted": len(new_df),                    │
│       "totalRecords": len(combined),                     │
│       "newOffset": toDate.isoformat(),                   │
│       "startedAt": started_at.isoformat(),               │
│       "finishedAt": datetime.now(UTC).isoformat(),       │
│       "durationMs": duration_in_milliseconds,            │
│       "errorMessage": None                               │
│     }                                                    │
│                                                          │
│  8. Publish status to Kafka                              │
│     → Topic: sync-job-status                             │
│     → Key: symbolKey                                     │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Parquet File Structure**:
```
stock-data/
└── EOD/
    ├── HOSE-HPG.parquet
    ├── HOSE-FPT.parquet
    ├── HNX-SHS.parquet
    └── ...
```

**DataFrame Schema**:
```python
{
    "date": "2024-01-15",
    "open": 25000.0,
    "high": 25500.0,
    "low": 24800.0,
    "close": 25300.0,
    "volume": 1250000,
    "value": 31625000000,
    # ... additional columns from API
}
```

---

### Phase 4: Status Update

```
┌─────────────────────────────────────────────────────┐
│          JobStatusConsumer                          │
│                                                     │
│  @KafkaListener(topics = "sync-job-status")         │
│                                                     │
│  1. Consume status message from Kafka               │
│                                                     │
│  2. Parse message and extract:                      │
│     - logId (JobExecutionHistory primary key)       │
│     - status (success/error)                        │
│     - metrics (records, duration, etc.)             │
│                                                     │
│  3. Update job_execution_history record:            │
│     UPDATE job_execution_history                    │
│     SET status = :status,                           │
│         error = :errorMessage,                      │
│         started_at = :startedAt,                    │
│         finished_at = :finishedAt,                  │
│         records_synced = :recordsInserted,          │
│         new_offset = :newOffset,                    │
│         meta_json = :metadata                       │
│     WHERE id = :logId;                              │
│                                                     │
│  4. Commit transaction                              │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**Status Values**:
- `PENDING` — Job dispatched, waiting for processing
- `SUCCESS` — Data synced successfully
- `ERROR` — Sync failed (error details in `error` column)

---

## Message Formats

### Sync Request Message (topic-sync-stock-prices)

```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "logId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "source": "vnd",
  "symbolKey": "HOSE-HPG",
  "fromOffset": "2024-01-01T00:00:00Z",
  "toOffset": "2024-12-31T23:59:59Z",
  "metadata": {
    "bucket": "stock-data",
    "objectName": "EOD/HOSE-HPG.parquet",
    "sector": ["FINANCIALS"]
  }
}
```

### Status Response Message (sync-job-status)

```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "logId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "symbolKey": "HOSE-HPG",
  "status": "success",
  "recordsInserted": 250,
  "totalRecords": 1550,
  "newOffset": "2024-12-31",
  "startedAt": "2024-12-31T10:15:30.123Z",
  "finishedAt": "2024-12-31T10:15:32.456Z",
  "durationMs": 2333,
  "errorMessage": null
}
```

---

## Database Schema

### job_definition

Defines scheduled sync jobs.

```sql
CREATE TABLE job_definition (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(255) NOT NULL,           -- 'vnd', 'vci'
    job_type VARCHAR(50) NOT NULL,          -- 'SYNC_STOCK_PRICE', 'SYNC_SYMBOLS'
    cron_expr VARCHAR(255),                 -- '0 0 18 * * MON-FRI'
    is_active BOOLEAN DEFAULT TRUE,
    next_run TIMESTAMP WITH TIME ZONE,
    config_json JSONB                       -- {"sector": ["FINANCIALS"]}
);
```

### job_execution_history

Tracks individual sync executions per symbol.

```sql
CREATE TABLE job_execution_history (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    job_id UUID NOT NULL REFERENCES job_definition(id),
    used_source VARCHAR(255) NOT NULL,      -- 'vnd', 'vci'
    attempt INTEGER NOT NULL DEFAULT 1,
    parent_log_id UUID,
    status VARCHAR(255) NOT NULL,           -- 'PENDING', 'SUCCESS', 'ERROR'
    triggered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    records_synced INTEGER,
    records_skipped INTEGER,
    new_offset VARCHAR(255),                -- '2024-12-31'
    error TEXT,
    meta_json JSONB                         -- {"symbolKey": "HOSE-HPG", "totalRecords": 1550, "durationMs": 2333}
);

-- Index for querying by symbolKey (stored in meta_json)
CREATE INDEX idx_job_execution_history_symbol 
ON job_execution_history ((meta_json ->> 'symbolKey'))
WHERE meta_json ? 'symbolKey';

-- Index for offset queries per symbol
CREATE INDEX idx_job_execution_history_symbol_offset 
ON job_execution_history (
    job_id,
    (meta_json ->> 'symbolKey'),
    finished_at DESC
)
WHERE new_offset IS NOT NULL;
```

**Note**: `symbolKey` is stored in `meta_json` as a JSON field, not as a direct column. This provides flexibility to store additional metadata per execution.

### symbol

Stock symbol master data.

```sql
CREATE TABLE symbol (
    id UUID PRIMARY KEY,
    symbol_key VARCHAR(50) NOT NULL UNIQUE, -- 'HOSE-HPG'
    exchange VARCHAR(10) NOT NULL,          -- 'HOSE', 'HNX', 'UPCOM'
    code VARCHAR(20) NOT NULL,              -- 'HPG'
    sector VARCHAR(100),                    -- 'FINANCIALS'
    industry VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE
);
```

---

## Configuration

### Platform (application.yaml)

```yaml
app:
  scheduler:
    global:
      fixedDelayString: 30000  # 30 seconds
    sync-stock-prices:
      bucket: stock-data
      object-name: EOD/{symbolKey}.parquet

spring:
  kafka:
    bootstrap-servers: localhost:9092
    topics:
      topic-sync-stock-prices: topic-sync-stock-prices
      topic-sync-job-status: sync-job-status
```

### Ingestor (.env)

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
TOPIC_SYNC_STOCK_PRICES=topic-sync-stock-prices
TOPIC_SYNC_JOB_STATUS=sync-job-status

# MinIO/S3 Configuration
S3_ENDPOINT_URL=http://minio:9000
S3_ACCESS_KEY=minioadmin
S3_SECRET_KEY=minioadmin
S3_BUCKET=stock-data

# Parquet Configuration
EOD_PREFIX=EOD/

# Stock API Configuration
VND_API_URL=https://api.vndirect.com.vn
VCI_API_URL=https://api.vci.com.vn
```

---

## Error Handling

### Ingestor Error Scenarios

1. **External API Failure**
   - Retry with exponential backoff (client-level)
   - If all retries fail, publish error status
   - Error logged with full stack trace

2. **MinIO Connection Failure**
   - Fail fast and publish error status
   - Admin notified via logs

3. **Parquet Corruption**
   - Skip existing file, treat as new sync
   - Log warning and continue

4. **Invalid Message Format**
   - Log error and skip message
   - Dead letter queue (future enhancement)

### Platform Error Handling

1. **Job Scheduling Failure**
   - Logged but does not prevent other jobs from running
   - Job remains in `due` state for next scan

2. **Kafka Publish Failure**
   - Transaction rolled back
   - Job's `next_run` not updated
   - Will retry on next scan

3. **Status Consumer Failure**
   - Kafka consumer rebalance
   - Message reprocessed (idempotent update)

---

## Monitoring & Observability

### Key Metrics

1. **Job Scheduler**
   - Due jobs per scan
   - Job dispatch latency
   - Failed dispatches per hour

2. **Ingestor**
   - Messages processed per second
   - Processing duration (P50, P95, P99)
   - Error rate by symbol
   - Records synced per job

3. **Database**
   - Execution history growth rate
   - Failed executions per job type
   - Average offset lag per symbol

### Logging Strategy

**Platform**:
```java
log.info("Found {} due job(s)", dueJobs.size());
log.info("Published sync job [{}] for source [{}]", jobId, source);
log.error("Failed to dispatch job [{}]: {}", jobId, error);
```

**Ingestor**:
```python
logger.info(f"Processing stock-price sync: {symbol_key}")
logger.info(f"Synced {len(new_df)} records, total: {len(combined)}")
logger.exception(f"Failed to process stock-price sync: {exc}")
```

---

## Performance Considerations

### Throughput

- **Job Scheduler**: Scans every 30 seconds, processes unlimited jobs per scan
- **Kafka Throughput**: ~10,000 messages/second (limited by network)
- **Ingestor Concurrency**: Bounded by `MAX_CONCURRENT_TASKS` (default: 10)
- **Parquet Operations**: In-memory merge, ~1-2 seconds per symbol

### Scalability

**Horizontal Scaling**:
- Ingestor: Deploy multiple replicas with same consumer group
- Kafka: Increase partition count for `topic-sync-stock-prices`
- Platform: Stateless, can scale horizontally

**Vertical Scaling**:
- Ingestor: Increase `MAX_CONCURRENT_TASKS` for more parallel processing
- Platform: Increase JVM heap for more concurrent Kafka producers

### Optimization Tips

1. **Reduce Parquet File Size**
   - Archive old data periodically
   - Use columnar compression (snappy, gzip)

2. **Batch Processing**
   - Group symbols by sector for better cache locality
   - Use Kafka batching for status messages

3. **Offset Management**
   - Use incremental syncs (`fromOffset` → `toOffset`)
   - Avoid full history syncs unless necessary

---

## Operational Procedures

### Adding a New Job

```sql
INSERT INTO job_definition (
    id, title, source, job_type, cron_expr, 
    is_active, next_run, config_json
) VALUES (
    gen_random_uuid(),
    'Daily HOSE Financials Sync',
    'vnd',
    'SYNC_STOCK_PRICE',
    '0 0 18 * * MON-FRI',  -- 6 PM weekdays
    TRUE,
    NOW(),
    '{"sector": ["FINANCIALS"]}'::jsonb
);
```

### Manually Triggering a Sync

Update `next_run` to force immediate execution:

```sql
UPDATE job_definition
SET next_run = NOW()
WHERE id = 'job-uuid';
```

### Checking Job Status

```sql
SELECT 
    jd.title,
    jd.source,
    jeh.meta_json->>'symbolKey' AS symbol_key,
    jeh.status,
    jeh.records_synced,
    jeh.started_at,
    jeh.finished_at,
    jeh.error
FROM job_execution_history jeh
JOIN job_definition jd ON jeh.job_id = jd.id
WHERE jd.id = 'job-uuid'
ORDER BY jeh.started_at DESC
LIMIT 50;
```

### Reprocessing Failed Syncs

1. Identify failed executions:
```sql
SELECT * FROM job_execution_history 
WHERE status = 'ERROR' 
AND started_at > NOW() - INTERVAL '24 hours';
```

2. Reset status and clear offset:
```sql
UPDATE job_execution_history
SET status = 'PENDING', 
    new_offset = NULL,
    error = NULL
WHERE id = 'log-uuid';
```

3. Manually publish Kafka message or wait for next scheduled run

---

## Troubleshooting Guide

### Issue: Jobs Not Running

**Symptoms**: `next_run` is in the past but job not executing

**Diagnosis**:
1. Check scheduler is running: `grep "SyncJobScheduler" platform.log`
2. Verify `is_active = TRUE`
3. Check Kafka connectivity

**Resolution**:
```sql
UPDATE job_definition SET next_run = NOW() WHERE id = 'job-uuid';
```

### Issue: Ingestor Not Processing Messages

**Symptoms**: Messages piling up in Kafka topic

**Diagnosis**:
1. Check ingestor logs: `docker logs ingestor`
2. Verify consumer group status: `kafka-consumer-groups --describe --group ingestor`
3. Check external API connectivity

**Resolution**:
- Restart ingestor: `docker restart ingestor`
- Check external API status
- Verify credentials in `.env`

### Issue: High Processing Time

**Symptoms**: `durationMs > 10000` for most symbols

**Diagnosis**:
1. Check external API latency
2. Verify MinIO storage performance
3. Check Parquet file sizes

**Resolution**:
- Reduce `limit` parameter for incremental syncs
- Archive old data to separate bucket
- Increase ingestor resources

### Issue: Duplicate Records in Parquet

**Symptoms**: Same date appears multiple times

**Diagnosis**:
- Check deduplication logic in `process_stock_price_message`
- Verify external API not returning duplicates

**Resolution**:
- Already handled by `drop_duplicates(subset=["date"])`
- If persists, investigate external API

---

## Future Enhancements

1. **Dead Letter Queue**: Failed messages moved to DLQ for manual inspection
2. **Retry Policy**: Configurable retry with exponential backoff
3. **Rate Limiting**: Respect external API rate limits
4. **Data Validation**: Schema validation before writing to MinIO
5. **Metrics Dashboard**: Grafana dashboard for real-time monitoring
6. **Alert System**: PagerDuty/Slack integration for critical failures
7. **Incremental Parquet**: Append-only Parquet files for better performance
8. **Multi-Region Replication**: S3 cross-region replication for disaster recovery

---

## Related Documentation

- [README.md](../README.md) — Workspace overview and setup
- [AGENTS.md](../AGENTS.md) — Codebase instructions for AI agents
- [docker-compose.yaml](../docker-compose.yaml) — Infrastructure configuration
- [configs/shared/topics.yaml](../configs/shared/topics.yaml) — Kafka topic definitions

---

**Last Updated**: 2026-07-09  
**Version**: 1.0.0  
**Maintainer**: Platform Team