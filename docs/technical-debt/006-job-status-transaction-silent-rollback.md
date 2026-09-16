# Job Status Transaction Silent Rollback

## Summary

[`JobStatusConsumer.handleSyncStatus()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java:42) is not transactional but calls transactional [`JobService.applyStatus()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java:329). When transaction callbacks (event listeners, notification dispatch) throw exceptions, Spring marks the transaction as rollback-only, causing database changes to be silently rolled back without clear error propagation to the consumer.

This debt affects job status persistence, parent aggregation, and notification reliability.

## Current Behavior

### Transaction Boundary Mismatch

[`JobStatusConsumer.java:42-64`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java:42)

```java
@KafkaListener(topics = "${kafka.topics.topic-sync-job-status}", ...)
public void handleSyncStatus(ConsumerRecord<String, String> record) {
    try {
        // ... parsing and validation ...
        jobService.applyStatus(response);  // @Transactional, creates new tx
        // ... logging ...
    } catch (Exception e) {
        publishMessageProcessingFailed(record, e);
        throw new RuntimeException("Failed to process stock-sync-status message", e);
    }
}
```

- Consumer method has **no** `@Transactional` annotation.
- Calls `applyStatus()` which has `@Transactional` (line 329).
- Spring creates a new transaction for `applyStatus()`.

### Transaction Callback Exception Path

[`JobService.java:329-410`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java:329)

```java
@Transactional
public void applyStatus(JobStatusMessage response) {
    // ... validation and early returns ...
    jobExecutionHistoryRepository.saveAndFlush(history);
    log.info("Saved job status child...");

    // Potential callback trigger point
    UUID persistedParentLogId = history.getParentLogId();
    if (persistedParentLogId != null) {
        aggregateParentExecution(persistedParentLogId);  // May throw, tx marked rollback-only
    }
}
```

1. `saveAndFlush()` persists child execution.
2. `aggregateParentExecution()` is called, which has its own `@Transactional(propagation=REQUIRES_NEW)`.
3. Transaction callbacks (event listeners) may fire at any point.
4. If callback throws exception:
   - Spring catches and marks the outer transaction as rollback-only.
   - Changes to `history` are rolled back silently.
   - Consumer exception handler catches the exception and rethrows as `RuntimeException`.

### Event Listener Exception

[`NotificationEventListener.java:54-64`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/listeners/NotificationEventListener.java:54)

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onSignalDigestNotification(SignalDigestNotificationEvent event) {
    try {
        // ... rendering and notification ...
        notificationService.send(signalNotificationTemplate.render(event));  // May throw
    } catch (Exception exc) {
        log.warn("Signal digest notification handling failed: {}", exc.getMessage(), exc);
    }
}
```

- Listener is async and transactional.
- If `notificationService.send()` throws exception (Telegram API error, render failure):
  - Exception is caught and logged (line 61).
  - But Spring may still mark outer `applyStatus()` transaction as rollback-only.

### Silent Rollback Message

When Spring rolls back due to callback exception:

```
Transaction silently rolled back because it has been marked as rollback-only
```

- This message appears in logs but the actual root cause (notification failure) may not be visible.
- Database changes are not persisted.
- Consumer reprocesses the same message, creating infinite retry loop or gaps.

## Impact

1. **Silent persistence failure**: Job status updates are rolled back without clear error to consumer. Message may be retried indefinitely or marked as poison pill.

2. **Parent aggregation gaps**: If `aggregateParentExecution()` throws or callback fails, parent status is never updated, leaving parent in `RUNNING` state indefinitely.

3. **Notification reliability**: Notification failures silently cause transaction rollback, making it impossible to distinguish:

   - "Notification failed, but job status was persisted" vs.
   - "Notification failed AND job status was rolled back."

4. **Diagnostic difficulty**: Logs show "Transaction silently rolled back" but not the root cause (callback exception). Operator must correlate with separate notification handler logs.

## Root Causes

1. **Transaction propagation boundary**: Consumer is non-transactional; service is transactional. Spring creates isolated tx boundary, making callback exceptions visible only within that boundary.

2. **Callback exception swallowing**: [`NotificationEventListener`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/listeners/NotificationEventListener.java:48) catches exceptions but doesn't re-throw. Spring may still mark tx as rollback-only.

3. **No transaction state inspection**: Consumer has no visibility into whether transaction was rolled back. Exception handling assumes persistence succeeded.

4. **Async listener + sync transaction**: Async listeners can fail independently of the outer transaction, causing state inconsistency.

## Affected Components

- [`JobStatusConsumer.handleSyncStatus()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java:42): non-transactional.
- [`JobService.applyStatus()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java:329): transactional.
- [`JobService.aggregateParentExecution()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java:439): nested transactional.
- [`NotificationEventListener`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/listeners/NotificationEventListener.java:54): async transactional callback.
- [`TelegramNotificationService.send()`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/services/TelegramNotificationService.java:43): catches exceptions, silent on failure.

## Recommended Follow-Up

### Short-Term (Documentation & Diagnostics)

1. **Add transaction state logging** in `applyStatus()`:

   ```java
   log.info("Transaction state before callback: active={} rollbackOnly={}",
       TransactionSynchronizationManager.isActualTransactionActive(),
       TransactionSynchronizationManager.getCurrentTransactionStatus().isRollbackOnly());
   ```

2. **Add callback exception propagation** in `NotificationEventListener`:

   - Don't swallow exceptions; let them propagate to trigger rollback detection.
   - Or use synchronous notification dispatch within transaction boundary.

3. **Document transaction boundary** in [`docs/flows/001-job-execution.md`](../../docs/flows/001-job-execution.md):
   - Clarify that job status persistence depends on successful transaction commit.
   - Explain that notification failures may cause rollback.
   - Recommend operator to check transaction logs for "rollback-only" patterns.

### Medium-Term (Architecture)

1. **Make consumer transactional** (if appropriate):

   ```java
   @KafkaListener(...)
   @Transactional
   public void handleSyncStatus(...) { ... }
   ```

   - Unifies transaction boundary.
   - Makes callback exceptions visible to Kafka consumer error handler.
   - Risk: Requires careful handling of Kafka offset semantics.

2. **Separate notification dispatch from job status transaction**:

   - Use outbox pattern: persist job status + notification intent in same transaction.
   - Dispatch notifications asynchronously in separate service.
   - Decouple notification failure from job status persistence.

3. **Add transaction completion listener**:
   ```java
   TransactionSynchronizationManager.registerSynchronization(
       new TransactionSynchronization() {
           @Override
           public void afterCompletion(int status) {
               if (status == STATUS_ROLLED_BACK) {
                   log.error("Job status transaction rolled back: executionId={}");
               }
           }
       }
   );
   ```

### Long-Term (P-Future)

Align with P3-I1 (Notification Outbox) and P8-I3 (Durable Notification Delivery):

- Implement outbox pattern for all job status notifications.
- Separate critical path (job status persistence) from delivery (notification dispatch).
- Add transaction explicit verification before acknowledging Kafka message.

## Related Technical Debt

- [`docs/technical-debt/002-telegram-notification-deduplication.md`](002-telegram-notification-deduplication.md): Deduplication is process-local, doesn't survive restarts.
- [`docs/plans/008-omni-metadata-console-dashboard-execution-plan.md`](../../docs/plans/008-omni-metadata-console-dashboard-execution-plan.md): P6-I3 depends on reliable job status history.
- [`docs/plans/022-notification-outbox.md`](../../docs/plans/022-notification-outbox.md): P8-I2 notification outbox pattern (not yet implemented).

## Historical Reference

First observed: message "Transaction silently rolled back because it has been marked as rollback-only" during job status processing with concurrent notification dispatch.

Severity: **Medium** — affects operational reliability and diagnostics, but not data correctness (job status is eventually re-attempted).
