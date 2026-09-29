# Job Status Empty Output Semantics

## Summary

Stock-price sync, indicator sync, and signal sync jobs currently report `SUCCESS` even when output is empty, missing, or non-persisted. The semantic gap between "no exception thrown" and "valid output produced" is not documented and causes operational confusion when many symbols have missing or `NO_DECISION` results but parent jobs report `SUCCESS`.

This debt affects diagnostics, job dependency guards, and operator visibility into data completeness.

## Current Behavior

### Stock-Price Sync

[`apps/ingestor/app/handlers/stock_prices.py:92-96`](../../apps/ingestor/app/handlers/stock_prices.py:92)

```python
status = build_status(
    payload,
    started_at,
    JobStatus.SUCCESS,
    records_inserted=len(new_df),
    total_records=len(combined),
)
```

- When both existing and new data are empty, `combined` is an empty DataFrame.
- Parquet write succeeds with zero rows.
- Status is hardcoded `JobStatus.SUCCESS` regardless of `len(new_df)` or `len(combined)`.
- Child execution shows `recordsInserted=0`, `totalRecords=0` but status remains `SUCCESS`.

### Indicator Sync

[`apps/analyzer/app/indicators/kafka.py:64-70`](../../apps/analyzer/app/indicators/kafka.py:64)

```python
records_processed = await self._handler.handle(raw)
status = self._build_status(
    message=message,
    started_at=started_at,
    finished_at=utc_now(),
    status=JobStatus.SUCCESS,
    records_processed=records_processed,
)
```

- Handler returns record count; if `0`, status is still `JobStatus.SUCCESS`.
- No validation that output dataset exists or is non-empty.

### Signal Sync

[`apps/analyzer/app/signals/kafka.py:182-189`](../../apps/analyzer/app/signals/kafka.py:182)

```python
meta_json["recordsProcessed"] = 1
return JobStatusMessage(
    ...
    status=JobStatus.SUCCESS,
    ...
    records_processed=1,
)
```

- Hard-coded `recordsProcessed=1` and `JobStatus.SUCCESS` regardless of:
  - Whether indicator object exists (triggers `MISSING_INDICATOR_OBJECT` → `NO_DECISION`).
  - Whether signal was persisted (empty metadata, zero reason codes).
  - Whether signal is `NO_DECISION` due to missing/stale data.
- Signal `NO_DECISION` transitions are not persisted, so symbol has no output, but status is `SUCCESS`.

### Parent Aggregation

[`apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java:476-482`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java:476)

```java
if (!allTerminal) {
    parentStatus = JobStatus.RUNNING;
} else if (failedCount > 0) {
    parentStatus = JobStatus.FAILED;
} else {
    parentStatus = JobStatus.SUCCESS;
}
```

- Parent is `SUCCESS` if all children are terminal and none are `FAILED`/`ERROR`.
- No check for:
  - `recordsProcessed=0` children.
  - Children with `NO_DECISION` metadata.
  - Dataset completeness or currentness.

## Impact

1. **Operator diagnostics**: Seeing parent job `SUCCESS` does not guarantee all symbols have valid data. Operator must inspect `recordsProcessed`, metadata reason codes, and dataset content separately.

2. **Job dependency guards**: A downstream job depending on "dataset is ready" cannot distinguish between "all symbols succeeded and have data" vs. "all children ran but many have empty output."

3. **False confidence**: Automated monitoring that treats `SUCCESS` as "data is complete" will miss missing or stale symbol data.

4. **Notification suppression**: Digest notifications are suppressed for unchanged/`NO_DECISION` signals, but parent job notification still reports `SUCCESS`.

## Required Clarification

### Decision 1: Status Semantics

Define whether `JobStatus.SUCCESS` means:

- **Option A (Current)**: No exception thrown; all children are terminal and not `FAILED`/`ERROR`.
- **Option B (Proposed)**: Output is valid and complete for the execution scope; empty/zero output may trigger `PARTIAL_SUCCESS` or require explicit validation.

### Decision 2: Empty Output Handling

For stock-price, indicator, and signal sync:

- Should zero-record output be:
  - Still `SUCCESS` with operator-visible `recordsProcessed=0`?
  - A new status like `PARTIAL_SUCCESS` or `NO_DATA`?
  - A `BLOCKED` state indicating upstream dependency failure?

### Decision 3: Diagnostic Requirement

Should operator always inspect:

- `recordsProcessed` / `recordsInserted` metadata to detect zero-output jobs?
- Signal metadata reason codes to detect `NO_DECISION` transitions?
- Actual Parquet dataset to detect empty files?

Or should job status provide direct signal of output completeness?

## Affected Components

- Ingestor stock-price handler.
- Analyzer indicator consumer.
- Analyzer signal consumer and handler.
- Platform job aggregation and parent status logic.
- Job dependency guard (P4-I4) evaluation.
- Notification policies and digest generation.
- Dashboard and operator visibility.

## Current Source Assessment

- **Current:** stock-price and indicator workers can report `SUCCESS` with zero output.
- **Current:** signal workers report one processed record even when `NO_DECISION` is not
  persisted.
- **Current:** parent aggregation uses terminal/failure states and does not interpret
  dataset completeness.
- **Current:** dependency readiness is manifest/version based and should not infer
  completeness solely from worker status.
- **Evidence-dependent:** the operational frequency and user impact of zero-output
  success require representative execution evidence.

## Recommended Actions

1. Define status semantics per job family before introducing a new shared status:
   distinguish valid empty result, unavailable upstream input, usable-but-incomplete
   output, and invalid output.
2. Align any `PARTIAL_SUCCESS` decision with
   [`001-p3-i5-metadata-reconciliation.md`](001-p3-i5-metadata-reconciliation.md) so
   Platform persistence, aggregation, APIs, and notifications use one contract.
3. Correct signal accounting so `recordsProcessed`, `persisted`, reason codes, and
   terminal status do not imply a written row when the result is `NO_DECISION`.
4. Keep dependency decisions manifest/readiness and exact-`dataVersion` based; add a
   completeness field only through an approved manifest-contract change, not an ad hoc
   row-count read.
5. Add operator-visible zero-output/incomplete counts and regression fixtures before
   changing parent aggregation semantics.
6. Update [`docs/flows/001-job-execution.md`](../flows/001-job-execution.md) with the
   approved distinction between execution success and dataset completeness.

## Historical Reference

Related: [`docs/technical-debt/001-p3-i5-metadata-reconciliation.md`](001-p3-i5-metadata-reconciliation.md) (P3-I5 `PARTIAL_SUCCESS` contract).

Not blocked by this debt; independent clarification opportunity.
