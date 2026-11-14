# TD-019 - Historical Recalculation and Outcome Invalidation

Status: OPEN. Priority: P1 when correcting historical inputs or interpreting
previously evaluated signals after a refresh. Recorded 2026-11-14 from static source
inspection; no corruption reproduction, provider replay or live-data audit was run.

## Confirmed behavior

- Backfill computes requested business dates. It does not automatically expand
  beyond the requested range to repair later dates affected by changed history.
  See [GraphWorkExpansion](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/expansion/GraphWorkExpansion.java).
- [cutoff_frame and write_day](../../libs/py-common/py_common/storage/atomic_work.py)
  cut inputs at the business date and replace that day's output while preserving
  other dates. Exact-day coverage remains required where requested; missing data
  is not proof of no trading. These safeguards do not establish freshness of later
  calculations after historical corrections.
- [SignalHistoryRepository](../../apps/analyzer/app/signals/storage.py), in
  `_upsert_signal_row`, copies existing `OUTCOME_COLUMNS` into a recalculated row
  with the same signal identity. `update_outcomes` fills missing outcome windows;
  it does not invalidate already populated values when inputs or the signal change.

For example, correcting EOD on D and recalculating only D can leave indicators
and signals on D+1 and later based on the old history. Refreshing a signal can
also retain outcomes calculated from its previous signal price or EOD version.
The risk depends on actual input changes; rerunning identical inputs is not proof
of a data defect. Backfill does recalculate within its requested scope: the missing
capability is propagation and invalidation across existing results.

## Bounded follow-up requiring owner approval

1. Detect actual upstream-version/content changes and define affected business
   dates using indicator lookback, signal state and outcome horizons. Decide how
   rolling state and recursive indicators bound or extend the repair range.
2. Invalidate/rebuild affected outcomes when signal inputs or outcome-price inputs
   change. Preserve unaffected dates and retain auditable versions and revisions.
3. Schedule the bounded repair through existing atomic ownership, enrollment,
   dependency guards and outbox. Retain frozen proofs for running consumers;
   registration acceptance must not bypass READY validation.

Acceptance needs integration coverage for changed versus identical inputs,
overlapping ranges, outcome horizons, retry/idempotency, concurrent frozen readers,
failure before READY and preservation of unaffected history. No runtime repair,
automatic forward expansion or new persistence schema is authorized by this record.

## Impact and related scope

Platform would own repair-range expansion; Analyzer would own indicator/signal and
outcome recomputation; Ingestor would supply corrected EOD. Shared Python storage,
contracts, readiness persistence and operations need review before implementation.
Query Service and Console may expose existing stale results but are unchanged here.
No API, Kafka message, storage path, dataset ownership, configuration, deployment
or runtime behavior changes in this documentation task. Tests above are future
acceptance requirements, not executed evidence. I10 completion is not asserted.

- [TD-005](005-job-status-empty-output-semantics.md): valid-empty / NO_DECISION and
  warmup failures are separate from recalculation propagation.
- [TD-016](016-parquet-readiness-reconciliation.md): reconstructing coverage does
  not prove that already calculated values reflect corrected historical inputs.
- [Plan 028](../plans/028-reusable-date-range-backfill.md) and
  [Plan 030](../plans/030-static-graph-dispatch-planner.md) retain their existing
  scope, schedules and acceptance gates.
