# Job Status Transaction Silent Rollback

## Status

Resolved as written; retained as historical evidence.

## Original Observation

A runtime investigation previously associated job-status processing with the message
`Transaction silently rolled back because it has been marked as rollback-only` and
suspected that notification callbacks could roll back
[`JobService.applyStatus()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java).

The concern was operationally important because a rolled-back child status can leave a
parent execution in `RUNNING` and cause Kafka redelivery. However, the notification
rollback path described by the original record no longer matches current source.

## Current Source Assessment

- [`JobStatusConsumer.handleSyncStatus()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java)
  remains non-transactional and delegates persistence to transactional
  [`JobService.applyStatus()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java).
- Current notification handling uses an asynchronous ordinary event listener that
  catches its own delivery failures. It is not the former synchronous transactional
  callback described by this record and cannot mark the caller transaction rollback-only
  from its separate thread.
- Signal digest notification intent is persisted through the notification outbox from
  the job-status transaction, separating durable enqueue from external Telegram
  delivery.
- A synchronous exception inside child persistence or parent aggregation can still roll
  back `applyStatus()`, but that is normal transaction behavior and is not evidence of
  the historical notification-callback defect.
- Static source inspection cannot establish whether the historical log message still
  occurs through another path.

## Recommended Actions

1. Close this specific notification-callback theory as resolved/superseded; do not use
   it to justify making asynchronous delivery synchronous.
2. If rollback-only behavior recurs, capture the exact root exception, transaction name,
   execution ID, Kafka coordinates, and synchronous call path before opening a new
   defect.
3. Keep job-status persistence and durable notification-outbox enqueue in the same
   database transaction while external delivery remains outside it.
4. Add focused rollback tests only for verified synchronous failure paths such as child
   persistence, parent locking/aggregation, and notification-outbox enqueue.
5. Coordinate Kafka acknowledgment semantics with Phase 12 rather than adding
   `@Transactional` to the listener without a concrete database/Kafka transaction
   design.

## Contract Impact

- Kafka/service-to-service protobuf: unchanged.
- Object-storage JSON manifests: unchanged.
- Storage paths/dataset ownership: unchanged.
- Public Java/Python APIs: unchanged.
- Configuration/environment: unchanged.

## Closure Criteria

This historical record can remain closed while current source keeps external
notification delivery isolated from the job-status transaction. Any new rollback issue
must be tracked as a separate evidence-based defect naming the actual synchronous
failure path.

## Verification Status

Static source inspection only. Build, test, lint, format, Kafka integration, and runtime
rollback reproduction were not run under the repository verification approval gate.
