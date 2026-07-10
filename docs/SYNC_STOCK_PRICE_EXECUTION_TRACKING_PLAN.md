# Sync Stock Price Execution Tracking – Implementation Plan

## 1. Problem

`SyncStockPriceJobProducer` currently creates one `JobExecutionHistory` before publishing messages, then reuses the same `logId` for every symbol.

```text
JobDefinition: Daily stock price sync
ExecutionHistory: execution-001

Kafka messages:
- HOSE-HPG -> logId: execution-001
- HOSE-FPT -> logId: execution-001
- HOSE-VIX -> logId: execution-001
```

When the ingestor publishes a status for each symbol, `JobStatusConsumer` updates the same history row. The responses therefore overwrite each other.

Current consequences:

- The final status depends on which symbol finishes last.
- `meta_json.symbolKey` only retains the last completed symbol.
- `records_synced`, `new_offset`, timestamps, and errors are overwritten.
- `findLastOffset(jobId, symbolKey)` cannot reliably obtain the offset of every symbol.
- It is impossible to see progress or retry a single failed symbol.
- The documentation says history is tracked per symbol, but the implementation tracks one shared row per scheduler run.

## 2. Target Model

Keep three different identifiers with separate responsibilities:

| Identifier | Meaning | Scope |
| --- | --- | --- |
| `jobDefinitionId` | Scheduled job configuration | Reused across all runs and symbols |
| `parentExecutionId` | One scheduler run | Reused by all symbols dispatched in that run |
| `executionId` | One symbol task | Unique for every symbol message |

Target hierarchy:

```text
JobDefinition
└── Parent execution: scheduled run at 2026-07-10T18:00:00Z
    ├── Child execution: HOSE-HPG
    ├── Child execution: HOSE-FPT
    └── Child execution: HOSE-VIX
```

`JobExecutionHistory.parentLogId` will connect child executions to the parent. No new child-job table is needed in the first implementation.

## 3. Expected Execution Flow

1. Scheduler finds a due `JobDefinition`.
2. `JobService` creates one parent execution with status `PENDING`.
3. Producer loads symbols matching the job configuration.
4. Java creates one child execution for every symbol.
5. Each child stores its `symbolKey` before Kafka publishing.
6. Producer publishes one message per child using the child's ID as `executionId`.
7. Ingestor processes each symbol and returns status using the same `executionId`.
8. `JobStatusConsumer` updates only the corresponding child execution.
9. Java recalculates the parent status and aggregate metrics after every child update.

## 4. Message Contract Changes

Replace ambiguous names in `SymbolJobMessage`:

```java
public record SymbolJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        String symbolKey,
        Instant fromOffset,
        Instant toOffset,
        Map<String, Object> metadata) implements JobMessage {
}
```

Apply the same naming to `JobStatusMessage` and the Python request/status models.

Example request:

```json
{
  "jobDefinitionId": "job-definition-uuid",
  "executionId": "child-execution-uuid",
  "parentExecutionId": "parent-execution-uuid",
  "source": "VND",
  "symbolKey": "HOSE-HPG",
  "fromOffset": "2026-07-09T00:00:00Z",
  "toOffset": "2026-07-10T00:00:00Z",
  "metadata": {}
}
```

Example status:

```json
{
  "jobDefinitionId": "job-definition-uuid",
  "executionId": "child-execution-uuid",
  "parentExecutionId": "parent-execution-uuid",
  "symbolKey": "HOSE-HPG",
  "status": "SUCCESS",
  "recordsInserted": 1,
  "totalRecords": 1500,
  "newOffset": "2026-07-10T00:00:00Z",
  "startedAt": "2026-07-10T18:00:01Z",
  "finishedAt": "2026-07-10T18:00:03Z",
  "durationMs": 2000,
  "errorMessage": null
}
```

If backward compatibility is needed during deployment, temporarily accept aliases:

- `jobId` -> `jobDefinitionId`
- `logId` -> `executionId`

Remove the aliases after both Java and Python have been deployed with the new contract.

## 5. Database Changes

### 5.1 Reuse existing structure

The existing `parent_log_id` column is sufficient for the parent-child relationship.

Store `symbolKey` in every child row at creation time, not only when the ingestor returns status:

```json
{
  "symbolKey": "HOSE-HPG"
}
```

This ensures pending and failed-before-processing tasks remain identifiable.

### 5.2 Recommended indexes

Add an index for loading all children of a parent:

```sql
CREATE INDEX IF NOT EXISTS idx_job_execution_history_parent_log_id
    ON job_execution_history (parent_log_id);
```

Keep or add the per-symbol offset index:

```sql
CREATE INDEX IF NOT EXISTS idx_job_execution_history_symbol_offset
    ON job_execution_history (
        job_id,
        (meta_json ->> 'symbolKey'),
        finished_at DESC
    )
    WHERE status = 'SUCCESS'
      AND new_offset IS NOT NULL;
```

Optional database constraints:

```sql
ALTER TABLE job_execution_history
    ADD CONSTRAINT fk_job_execution_history_parent
    FOREIGN KEY (parent_log_id)
    REFERENCES job_execution_history(id)
    ON DELETE CASCADE;
```

The foreign key can be postponed if old data may contain invalid `parent_log_id` values.

## 6. Java Implementation

### 6.1 `JobService`

Split execution preparation into explicit responsibilities:

```java
JobExecutionHistory prepareParentExecution(JobDefinition job, Instant now);

List<JobExecutionHistory> createChildExecutions(
        JobExecutionHistory parent,
        List<SymbolKeyProjection> symbols);
```

For every child:

- Copy `job` and `usedSource` from the parent.
- Set `parentLogId` to the parent ID.
- Set `status` to `PENDING`.
- Set `attempt` to `1`.
- Store `symbolKey` in `metaJson`.
- Save all child rows before publishing any message.

Use `saveAll()` to avoid one insert round trip per symbol.

### 6.2 `JobProducer`

The generic producer currently assumes one history row is enough. Adjust the template so specialized producers can create message-level executions.

Recommended approach:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void publish(JobDefinition job, Instant now) {
    JobExecutionHistory parent = jobService.prepareParentExecution(job, now);
    List<KafkaMessage> messages = buildMessages(job, parent, now);
    publishMessages(messages);
    postPublish(job, parent, messages.size(), now);
}
```

`SyncStockPriceJobProducer.buildMessages()` will create children and map one child to one symbol.

### 6.3 `SyncStockPriceJobProducer`

Implementation outline:

```java
List<SymbolKeyProjection> symbols = loadSymbols(job);
List<JobExecutionHistory> children =
        jobService.createSymbolExecutions(parent, symbols);

return IntStream.range(0, symbols.size())
        .mapToObj(index -> {
            SymbolKeyProjection symbol = symbols.get(index);
            JobExecutionHistory child = children.get(index);

            return new KafkaMessage(
                    symbol.symbolKey(),
                    new SymbolJobMessage(
                            job.getId(),
                            child.getId(),
                            parent.getId(),
                            job.getSource().toString(),
                            symbol.symbolKey(),
                            findLastOffset(job, symbol),
                            timestamps.truncatedTo(ChronoUnit.SECONDS),
                            buildMetadata(job)));
        })
        .toList();
```

Avoid relying on list position if `saveAll()` can return entities in a different order. A safer implementation maps child histories by `symbolKey` or constructs a small internal pair record before saving.

### 6.4 Empty symbol list

If the job resolves to zero symbols:

- Do not leave the parent in `PENDING` forever.
- Mark it `SUCCESS` with `recordsSynced = 0`, or `FAILED` if an empty result means invalid configuration.
- Recommended initial behavior: `SUCCESS` with `metaJson.childCount = 0` and a warning log.

### 6.5 Publish failure

Database and Kafka are not part of one atomic transaction. A failure after saving children can leave pending rows whose messages were never published.

Initial safe behavior:

- Catch the publishing exception.
- Mark the parent and unpublished children as `FAILED`.
- Preserve the error message.
- Allow the scheduler to retry on the next run.

Long-term solution:

- Introduce a transactional outbox if message delivery guarantees become important.

## 7. Status Consumer and Parent Aggregation

### 7.1 Update child only

`JobStatusConsumer` should resolve the history using `executionId`.

Validate before updating:

- The execution exists.
- It is a child execution.
- Its stored `symbolKey` matches the response.
- Its `job.id` matches `jobDefinitionId` when the field is present.
- Its `parentLogId` matches `parentExecutionId` when the field is present.

Do not replace the whole `metaJson`. Merge response metrics into the existing map so `symbolKey` and dispatch metadata are preserved.

### 7.2 Idempotency

Kafka may redeliver a status message. Updating the same child with the same terminal status must be safe.

Rules:

- Duplicate `SUCCESS` or `FAILED` responses may update identical values without changing aggregate totals incorrectly.
- Never increment parent totals directly from the incoming event.
- Recalculate totals from persisted child rows.

### 7.3 Parent status rules

Recommended initial rules:

| Child state | Parent state |
| --- | --- |
| All children `PENDING` | `PENDING` |
| At least one child `RUNNING`, none failed | `RUNNING` |
| All children `SUCCESS` | `SUCCESS` |
| Any child `FAILED` and all children terminal | `FAILED` |
| Mixture of `SUCCESS` and `FAILED` while some are active | `RUNNING` |

Parent aggregates:

- `startedAt`: minimum non-null child `startedAt`.
- `finishedAt`: maximum child `finishedAt` when all children are terminal.
- `recordsSynced`: sum of child `recordsSynced`, treating null as zero.
- `recordsSkipped`: sum of child `recordsSkipped`, treating null as zero.
- `newOffset`: leave null because a parent has multiple symbol offsets.
- `error`: summary such as `3/1527 symbol tasks failed`.
- `metaJson`: `childCount`, `successCount`, `failedCount`, `pendingCount`, and `runningCount`.

Do not add `PARTIAL_SUCCESS` in the first change unless the UI or downstream logic has a clear need for it. A failed child should make the parent `FAILED`, while detailed counts show partial completion.

## 8. Repository Queries

Add repository methods for:

```java
List<JobExecutionHistory> findAllByParentLogId(UUID parentLogId);

long countByParentLogIdAndStatus(UUID parentLogId, JobStatus status);
```

For small or medium symbol counts, loading all child rows and aggregating in Java is acceptable. If executions later contain tens of thousands of tasks, replace this with a grouped aggregate projection query.

The offset query must only read child executions that:

- Belong to the same `JobDefinition`.
- Have matching `meta_json.symbolKey`.
- Have status `SUCCESS`.
- Have a non-null `new_offset`.
- Are ordered by `finished_at DESC`.

## 9. Python Ingestor Changes

Update the stock-price message schema:

- Rename `job_id` to `job_definition_id`.
- Rename `log_id` to `execution_id`.
- Add `parent_execution_id`.

When publishing status:

- Echo all three identifiers unchanged.
- Use `execution_id` as the correlation ID.
- Continue using `symbolKey` as the Kafka key so messages for the same symbol remain ordered within a partition.

The ingestor should not aggregate parent state. Parent aggregation belongs to Java because Java owns scheduler state and PostgreSQL execution history.

## 10. Tests

### 10.1 Producer tests

- One parent and N children are created for N symbols.
- Every message has the common `jobDefinitionId`.
- Every message has the common `parentExecutionId`.
- Every message has a unique `executionId`.
- Child `metaJson` contains the correct `symbolKey` before publishing.
- Sector filtering only creates children for matching symbols.
- Empty symbol selection completes the parent correctly.
- Previous offsets are resolved independently per symbol.

### 10.2 Consumer tests

- A status response updates only its child execution.
- Statuses for two symbols do not overwrite each other.
- Existing child metadata is preserved.
- Duplicate status events are idempotent.
- Mismatched execution and symbol IDs are rejected.
- Missing execution IDs fail without updating another row.

### 10.3 Aggregation tests

- All success -> parent `SUCCESS`.
- One failed after all terminal -> parent `FAILED`.
- Some running -> parent `RUNNING`.
- Aggregate record counts equal the sum of children.
- Parent finish time is only populated after all children are terminal.

### 10.4 Integration test

Publish at least three symbol messages and return their statuses in a different order. Verify:

- Three independent child rows remain.
- Each child keeps its own offset and metrics.
- Parent metrics are correct.
- A later run starts from each symbol's own previous offset.

## 11. Deployment Order

Recommended backward-compatible rollout:

1. Add database index and repository support.
2. Make Java status consumer accept both old and new field names.
3. Deploy Java consumer changes.
4. Deploy Python models that accept old requests and publish the new status contract.
5. Deploy Java producer that creates child executions and publishes the new request contract.
6. Verify parent/child rows and independent offsets in production-like data.
7. Remove temporary message aliases in a later cleanup release.

If Java and Python are always deployed together, steps 2–5 can be released atomically and compatibility aliases can be skipped.

## 12. Implementation Phases

### Phase 1 – Correctness

- Create one child execution per symbol.
- Publish unique execution IDs.
- Update status per child.
- Preserve per-symbol offsets.
- Aggregate parent status.
- Add unit and integration tests.

### Phase 2 – Reliability

- Detect executions stuck in `PENDING` or `RUNNING`.
- Add retry rules using `attempt` and `parentLogId`.
- Add DLQ handling.
- Consider transactional outbox.

### Phase 3 – Observability and UI

- Show parent run summary.
- Expand a parent to display symbol-level tasks.
- Filter failed symbols.
- Retry selected failed children.
- Display progress as `terminalChildren / totalChildren`.

## 13. Definition of Done

- A scheduler run with N symbols creates exactly one parent and N child histories.
- Every Kafka request carries a unique child execution ID.
- Status messages never overwrite another symbol's history.
- Every symbol maintains an independent successful offset.
- Parent status and metrics reflect all child executions.
- Duplicate Kafka status messages are safe.
- Java and Python message contracts are aligned.
- Unit and integration tests cover out-of-order completion and partial failure.
- `docs/STOCK_SYNC_WORKFLOW.md` is updated to match the implemented parent-child model.

## 14. Main Files to Change

### Java

- `apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducer.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/SyncStockPriceJobProducer.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging/SymbolJobMessage.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging/JobStatusMessage.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobExecutionHistoryRepository.java`
- Scheduler unit and integration tests.

### Python

- Stock-price request model.
- Stock-price status model.
- `apps/ingestor/app/handlers/stock_prices.py`.
- Kafka serialization and handler tests.

### Database and documentation

- Flyway migration for indexes and optional parent foreign key.
- `docs/STOCK_SYNC_WORKFLOW.md`.

