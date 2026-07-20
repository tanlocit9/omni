# Stock Price Sync Workflow

This document is the authoritative workflow and message-contract reference for EOD stock-price synchronization.

## 1. Ownership and Service Boundaries

| Service | Responsibility |
| --- | --- |
| Platform (`apps/core`) | Owns scheduler configuration, job orchestration, parent/child execution tracking, symbol selection, Kafka request production, and status consumption. |
| Ingestor (`apps/ingestor`) | Owns external market-data retrieval, Parquet merge/write to object storage, and job-status production. |
| Analyzer (`apps/analyzer`) | Owns technical-indicator calculation from MinIO/S3 EOD Parquet files. It does not own stock-price persistence or dispatch stock-price sync commands. |
| PostgreSQL | Stores Platform-owned job definitions, execution history, and symbol metadata. |
| MinIO/S3 | Stores Parquet datasets such as `eod/{exchange}/{code}.parquet`. |
| Kafka | Carries Platform-to-Ingestor requests and Ingestor-to-Platform status events. |

## 2. Canonical Identifiers

| Field | Meaning | Scope |
| --- | --- | --- |
| `jobDefinitionId` | Scheduled job configuration ID | Shared by all executions of one job definition |
| `parentExecutionId` | Scheduler-run execution ID | Shared by all child tasks created during one scheduler run |
| `executionId` | Individual task execution ID | Unique per dispatched symbol task |

Legacy aliases are not canonical:

| Legacy field | Canonical field |
| --- | --- |
| `jobId` | `jobDefinitionId` |
| `logId` | `executionId` |

Compatibility aliases should only appear in explicit backward-compatibility sections or historical notes.

## 3. Contract Matrix

| Topic | Java role | Python role | Model / payload | Canonical identifiers | Kafka key |
| --- | --- | --- | --- | --- | --- |
| `topic-sync-stock-prices` | Producer (`SyncStockPriceJobProducer`) | Consumer (`process_stock_price_message`) | Stock-price sync request (`SymbolJobMessage`) | `jobDefinitionId`, `executionId`, `parentExecutionId` | `symbolKey` |
| `topic-sync-symbols` | Producer (`SyncSymbolsJobProducer`) | Consumer (`process_symbols_message`) | Symbol-master sync request (`SyncSymbolsJobMessage`) | `jobDefinitionId`, `executionId`, `parentExecutionId` | `exchange` |
| `topic-sync-indicators` | Producer (`SyncIndicatorsJobProducer`) | Consumer (`IndicatorKafkaService`) | Indicator calculation request (`IndicatorJobMessage`) | `jobDefinitionId`, `executionId`, `parentExecutionId` | `symbolKey` |
| `topic-sync-job-status` | Consumer (`JobStatusConsumer`) | Producer (`build_status` / `IndicatorKafkaService`) | Job status (`JobStatusMessage`) | `jobDefinitionId`, `executionId`, `parentExecutionId` | `symbolKey` for stock-price and indicator tasks; exchange identifier for symbol sync |
| `topic-upsert-symbols` | Consumer (`SymbolUpsertConsumer`) | Producer (`process_symbols_message`) | Full symbol snapshot (`SymbolUpsertMessage`) | `jobDefinitionId`, `executionId`, `parentExecutionId` | `exchange` |

## 4. Scheduler Trigger and Job-Definition Selection

`SyncJobScheduler` scans active due job definitions and dispatches each job to the producer for its job type.

Typical selection:

```sql
SELECT *
FROM job_definition
WHERE next_run <= :now
  AND is_active = TRUE
ORDER BY next_run ASC;
```

For stock-price synchronization, the relevant job type is `SYNC_STOCK_PRICE`.

## 5. Parent Execution Creation

Platform creates a parent `JobExecutionHistory` row for one scheduler run of a job definition.

The parent execution:

- has `id = parentExecutionId`;
- has `job_id = jobDefinitionId`;
- represents the aggregate scheduler run;
- is updated after child completions via parent aggregation logic.

## 6. One Child Execution per Symbol

`SyncStockPriceJobProducer` selects symbols, creates one child execution per symbol, and publishes one Kafka message per child execution.

Symbol selection may use sector filters from the job definition configuration. Offsets are tracked independently per symbol using the Platform execution history metadata.

Each child task:

- has its own `executionId`;
- references the shared `parentExecutionId`;
- uses `symbolKey` in the form `{EXCHANGE}-{CODE}`, for example `HOSE-HPG`;
- computes `fromOffset` from the last successful offset for the same `jobDefinitionId` and `symbolKey`;
- uses the scheduler timestamp as `toOffset`;
- is published with Kafka key `symbolKey`.

## 7. Stock-Price Request Contract

Topic: `topic-sync-stock-prices`

Producer: Platform `SyncStockPriceJobProducer`

Consumer: Ingestor `process_stock_price_message`

Kafka key: `symbolKey`

Canonical JSON example:

```json
{
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "22222222-2222-4222-8222-222222222222",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "source": "VCI",
  "symbolKey": "HOSE-HPG",
  "fromOffset": "2024-01-01T00:00:00Z",
  "toOffset": "2026-07-12T12:00:00Z",
  "metadata": {
    "sectors": ["MATERIALS"]
  }
}
```

Field notes:

| Field | Required | Meaning |
| --- | --- | --- |
| `jobDefinitionId` | Yes | Platform job definition ID. |
| `executionId` | Yes | Child execution ID for this symbol task. |
| `parentExecutionId` | Yes for scheduled fan-out | Parent scheduler-run execution ID. |
| `source` | Yes | External data source identifier used by Ingestor client selection. |
| `symbolKey` | Yes | `{EXCHANGE}-{CODE}` correlation and partitioning key. |
| `fromOffset` | Optional | Last successful symbol offset. `null` means fetch from default/full-history behavior. |
| `toOffset` | Yes | Upper offset for the sync run. |
| `metadata` | Optional | Non-routing metadata. Do not put `bucket` or `objectName` here for normal operation. |

S3 object names are derived by Ingestor path builders, not by Kafka message metadata:

```text
eod/{exchange}/{code}.parquet
```

Example:

```text
eod/hose/hpg.parquet
```

## 8. Ingestor Processing and Parquet Write

For each stock-price request, Ingestor:

1. parses `symbolKey`;
2. selects an external stock client from `source`;
3. fetches recent or full-history stock prices based on offset state;
4. resolves the EOD object path with `settings.get_eod_path(exchange, code)`;
5. reads any existing Parquet file from MinIO/S3;
6. merges, deduplicates, and sorts records;
7. writes the complete ticker-owned Parquet file back to MinIO/S3;
8. publishes a status event to `topic-sync-job-status`.

Each ticker owns one complete EOD Parquet file. The workflow does not use temporal partitions such as `dt=` or `run_id=`.

## 9. Indicator Calculation Contract

Topic: `topic-sync-indicators`

Producer: Platform `SyncIndicatorsJobProducer`

Consumer: Analyzer `IndicatorKafkaService`

Kafka key: `symbolKey`

Canonical JSON example:

```json
{
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "22222222-2222-4222-8222-222222222222",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "source": "ANALYZER",
  "symbolKey": "HOSE-HPG",
  "timeframe": "1d",
  "indicators": ["MA20", "MA50", "RSI", "MACD"],
  "metadata": {}
}
```

Field notes:

| Field | Required | Meaning |
| --- | --- | --- |
| `jobDefinitionId` | Yes | Platform job definition ID. |
| `executionId` | Yes | Child execution ID for this symbol task. |
| `parentExecutionId` | Yes for scheduled fan-out | Parent scheduler-run execution ID. |
| `source` | Yes | Fixed to `ANALYZER` for indicator jobs. |
| `symbolKey` | Yes | `{EXCHANGE}-{CODE}` correlation and partitioning key. |
| `timeframe` | Yes | Canonical indicator timeframe. v1 allows only `1d`. |
| `indicators` | Yes | Complete fixed v1 set: `MA20`, `MA50`, `RSI`, `MACD`. Partial sets are rejected. |
| `metadata` | Optional | Non-routing metadata. Do not put `bucket` or `objectName` here for normal operation. |

Analyzer derives both object paths through shared path builders:

```text
eod/{exchange}/{code}.parquet
indicators/{timeframe}/{exchange}/{code}.parquet
```

For each indicator request, Analyzer reads the complete EOD file, recomputes the full indicator series deterministically, replaces the indicator Parquet file, and publishes `recordsProcessed` to `topic-sync-job-status`.

## 10. Status Contract and Correlation

Topic: `topic-sync-job-status`

Producer: Ingestor `build_status`

Consumer: Platform `JobStatusConsumer`

Kafka key for stock-price sync: `symbolKey`

Canonical success example:

```json
{
  "symbolKey": "HOSE-HPG",
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "22222222-2222-4222-8222-222222222222",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "status": "SUCCESS",
  "recordsInserted": 12,
  "totalRecords": 2500,
  "newOffset": "2026-07-12T12:00:00Z",
  "startedAt": "2026-07-12T12:00:01.000000+00:00",
  "finishedAt": "2026-07-12T12:00:08.000000+00:00",
  "durationMs": 7000,
  "errorMessage": null
}
```

Canonical error example:

```json
{
  "symbolKey": "HOSE-HPG",
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "22222222-2222-4222-8222-222222222222",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "status": "ERROR",
  "recordsInserted": 0,
  "totalRecords": 0,
  "newOffset": null,
  "startedAt": "2026-07-12T12:00:01.000000+00:00",
  "finishedAt": "2026-07-12T12:00:03.000000+00:00",
  "durationMs": 2000,
  "errorMessage": "External provider request failed"
}
```

Field notes:

| Field | Required | Meaning |
| --- | --- | --- |
| `symbolKey` | Yes for stock-price sync | Correlates the status to the symbol task. |
| `jobDefinitionId` | Yes | Echoed from request. |
| `executionId` | Yes | Primary correlation ID for the child execution. |
| `parentExecutionId` | Optional but expected for scheduled fan-out | Parent scheduler-run execution. |
| `status` | Yes | `SUCCESS` or `ERROR` from Python workers. Platform maps `ERROR` to failed execution state. |
| `recordsInserted` | Required for stock-price/symbol sync | Count of newly fetched/inserted records for this run. |
| `recordsProcessed` | Required for indicator sync | Count of rows in the calculated indicator output. Platform also accepts this field before falling back to `recordsInserted`. |
| `totalRecords` | Yes | Count of records in the resulting Parquet dataset. |
| `newOffset` | Optional | Offset stored for the next run. |
| `startedAt` | Yes | ISO-8601 processing start timestamp. |
| `finishedAt` | Yes | ISO-8601 processing finish timestamp. |
| `durationMs` | Yes | Processing duration in milliseconds. |
| `errorMessage` | Optional | Error detail for failed tasks. |

Platform updates the child execution identified by `executionId`. If `parentExecutionId` is present, Platform aggregates the parent execution after applying the child status.

## 11. Parent Aggregation Rules

Parent aggregation is Platform-owned. A parent execution represents the scheduler run and is derived from child statuses.

Expected behavior:

- parent remains running while any child is still pending or running;
- parent succeeds when all tracked children succeed;
- parent fails or is marked partially failed according to Platform aggregation rules when one or more children fail;
- children may complete out of order because each symbol task is independent.

## 11. Independent Per-Symbol Offsets

Offsets are tracked per `jobDefinitionId` and `symbolKey`.

This prevents one symbol's completion from advancing another symbol's offset and allows independent retry or backfill behavior per ticker.

The producer reads the last successful offset for the same symbol before publishing the next request.

## 12. Failure, Retry, Idempotency, and Out-of-Order Completion

- **Failure isolation**: one failed symbol task does not block other symbol tasks from completing.
- **Retry scope**: retries should target the failed `executionId` or the affected symbol according to Platform scheduler policy.
- **Idempotency**: Ingestor rewrites one complete ticker Parquet file after merge/deduplication; repeated successful processing should not duplicate rows.
- **Out-of-order completion**: status messages can arrive in any symbol order and are correlated by `executionId`.
- **Offset safety**: offsets advance only from successful child status updates.

## 13. Backward Compatibility

Current Ingestor status-building code still accepts legacy request aliases:

| Accepted alias | Canonical field |
| --- | --- |
| `jobId` | `jobDefinitionId` |
| `logId` | `executionId` |

Platform `JobMessage` also exposes Java compatibility accessors `jobId()` and `logId()` that return the canonical identifiers.

Do not use these aliases in new examples or new producers. They are compatibility only, not the canonical contract.

## 14. Related Documents

- `docs/SECTOR_SYNC_WORKFLOW.md`
- `docs/S3_PATH_CONFIGURATION.md`
- `docs/SYNC_STOCK_PRICE_EXECUTION_TRACKING_PLAN.md`
- `configs/shared/topics.yaml`
- `configs/shared/s3-paths.yaml`