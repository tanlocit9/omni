# P1-I4 Execution Identity Hard Cutover

This runbook deploys the breaking `workType`/`workKey` execution contract. It
does not provide a dual-read, dual-write, or legacy-message compatibility
window.

## Release boundary

Deploy these artifacts as one release:

- Platform, including Flyway migration
  [`V9__backfill_execution_work_identity.sql`](../../database/migrations/V9__backfill_execution_work_identity.sql);
- Ingestor;
- Analyzer;
- the matching `py-common` package bundled in both Python images.

Do not deploy any one service independently. Every dispatched job payload and
every worker status now requires `workType` and `workKey`.

## Preflight

1. Record the exact image digests and database backup target.
2. Save the currently active job definitions so their state can be restored.
3. Set every `job_definitions.is_active` value to `false` and disable the manual
   trigger allow-list. Keep Platform running while the outbox drains.
4. Wait until all of the following are zero:

```sql
SELECT count(*) AS pending_outbox
FROM scheduler_outbox_messages
WHERE status = 'PENDING';

SELECT count(*) AS active_executions
FROM job_execution_histories
WHERE status IN ('PENDING', 'RUNNING');
```

5. Verify consumer lag is zero for every scheduler-owned job topic and
   `topic-sync-job-status`. Database counts alone cannot prove that Kafka is
   drained.
6. After taking the required backup, manually archive or delete execution history
   according to the approved retention procedure. V9 does not delete records.
7. Require zero execution-history rows before starting the new Platform image:

```sql
SELECT count(*) AS execution_history
FROM job_execution_histories;
```

Stop the cutover if any database query or consumer-lag check is non-zero.

## Cutover

1. Stop Platform, Ingestor, and Analyzer.
2. Take and verify a PostgreSQL snapshot or logical backup.
3. Start the new Platform image alone. Flyway V9 must finish successfully. It
   aborts if pending outbox work or any execution-history row remains.
4. Verify execution history remains empty after migration:

```sql
SELECT count(*) AS execution_history
FROM job_execution_histories;
```

5. Start the matching Ingestor and Analyzer images.
6. Restore the saved `is_active` values and manual-trigger allow-list.
7. Trigger one job for each scope and verify the child execution metadata and
   terminal status:

| Scope    | Example job                  | Expected identity               |
| -------- | ---------------------------- | ------------------------------- |
| Symbol   | `SYNC_STOCK_PRICE`           | `SYMBOL`, normalized symbol key |
| Exchange | `SYNC_SYMBOLS`               | `EXCHANGE`, exchange code       |
| Sector   | `PRECOMPUTE_SECTOR_FEATURES` | `SECTOR`, sector code           |
| Global   | `SYNC_METADATA`              | `GLOBAL`, stable job-owned key  |

8. Verify offset lookup, parent aggregation, one terminal notification, and no
   consumer deserialization or identity-mismatch warnings.

`SYNC_SYMBOLS` is intentionally mixed: its scheduled exchange child is
`EXCHANGE`, while symbol-upsert children containing domain metadata `code` and
`exchange` are `SYMBOL`. New code creates both shapes with canonical identity;
V9 does not infer or rewrite historical identity.

## Pre-deployment migration evidence

The repository harness
[`database/tests/p1_i4_work_identity_migration.sql`](../../database/tests/p1_i4_work_identity_migration.sql)
runs V9 twice against empty execution history, verifies canonical indexes, and
proves that remaining execution history or pending outbox work aborts migration.
No production database is used. This test evidence does not replace the
maintenance-window preflight, snapshot, Kafka-lag check, manual retention action,
or production postcondition.

## Rollback

Do not roll back only one service and do not reintroduce legacy compatibility
code.

1. Disable jobs and stop all three services.
2. Restore the pre-cutover PostgreSQL snapshot.
3. Deploy the previous Platform, Ingestor, and Analyzer image digests together.
4. Restore saved active-job and allow-list configuration.
5. Resume only after the old service set and database snapshot are consistent.

Kafka/outbox messages created by the new contract must not be consumed by the
old release. If any were published, drain or restore the queue state according
to the operator's broker recovery procedure before resuming.
