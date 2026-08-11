# Backend/Core Stabilization Implementation Plan

## Goal

Stabilize the current scheduler and sector-analysis pipeline before expanding additional backend features.

## Priority 0 — Correctness

### 1. Fix scheduler due-job query

Current JPQL must require `isActive = true` for both overdue and first-run jobs.

```sql
WHERE j.isActive = true
  AND (j.nextRun <= :now OR j.nextRun IS NULL)
```

Add repository tests for:

- active + overdue => selected;
- active + `nextRun = null` => selected;
- inactive + overdue => not selected;
- inactive + `nextRun = null` => not selected.

### 2. Complete the multi-sector feature universe

Sector Transition currently targets:

- `FINANCIAL_SERVICES`
- `REAL_ESTATE`
- `BASIC_RESOURCES`
- `BANKS`
- `OIL_AND_GAS`

Precompute the required symbol and sector features for the same universe before Sector Transition runs.

Preferred direction:

- one shared configured sector universe;
- avoid independent hard-coded lists between stock sync, feature precompute, and transition jobs;
- validate that every requested transition sector has a non-empty `sector-features` dataset for the evaluation date.

### 3. Remove concurrent read-modify-write risk for Sector Transition Parquet

Do not run one writer per focus sector against the same prediction/decision/probability Parquet files.

Preferred V1:

```text
1 SECTOR_TRANSITION_ANALYZE job
  -> universe = configured primary sectors
  -> focusSectorCodes = configured primary sectors
  -> calculate once
  -> merge/write each output dataset once
```

Use the same pattern for outcome evaluation.

This keeps the existing shared-Parquet decision while avoiding lost updates and repeated full-file rewrites.

## Priority 1 — Scheduler Hardening

### 4. Add atomic job claiming

Current flow is effectively:

```text
find due jobs -> publish -> advance nextRun
```

Move toward:

```text
find due jobs -> claim atomically -> dispatch
```

Implementation options:

- pessimistic lock with `SKIP LOCKED` where practical; or
- optimistic version field with guarded update.

Acceptance criteria:

- two core instances cannot dispatch the same scheduled execution;
- retry after failed dispatch is explicit and observable.

### 5. Promote data dependencies from documentation to validation

Reuse the current metadata:

```json
{
  "dependsOnJobs": [],
  "dependsOnDatasets": [],
  "producesDatasets": []
}
```

Add a lightweight dataset readiness/freshness guard before dispatching dependent analytical jobs.

V1 does not need a full DAG orchestrator. It only needs to prevent a job from running when required Parquet datasets are missing or stale for the requested evaluation date/timeframe.

### 6. Clean child execution semantics

`createChildExecution` should use generic metadata:

```json
{
  "workKey": "...",
  "workType": "SYMBOL|SECTOR|EXCHANGE|STRATEGY"
}
```

Only symbol-level producers should populate `symbolKey`.

Do not map every `workKey` into `symbolKey`.

### 7. Tighten notification types

Replace `Optional<Object>` with a common notification event abstraction.

Keep:

- default operational policy;
- signal digest policy;
- sector transition diagnostic policy.

Also support outcome-evaluation failures with the same actionable sector-transition diagnostics where applicable.

## Verification

After implementation:

1. Run affected Java repository/service/scheduler tests.
2. Run analyzer sector-wave and sector-transition tests.
3. Run Nx targeted lint/test/build targets.
4. Run `nx affected` when shared contracts or configuration are changed.
5. Verify one end-to-end daily pipeline for all configured primary sectors.

## Definition of Done

- Disabled jobs are never dispatched.
- All transition sectors have required upstream feature data.
- Shared Sector Transition Parquet outputs have a single logical writer per execution.
- Scheduler dispatch is safe for multiple core instances.
- Dataset dependencies prevent stale/missing analytical runs.
- Child execution metadata is domain-correct and no longer assumes every task is a symbol.
