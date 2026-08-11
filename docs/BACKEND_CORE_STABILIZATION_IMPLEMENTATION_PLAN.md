# Backend/Core Stabilization Implementation Plan

## Goal

Stabilize scheduler, job execution and sector-analysis correctness before expanding data frequency.

## Outcome

After this phase Omni has:

- correct due-job selection;
- one aligned multi-sector universe;
- one logical writer per shared Sector Transition dataset;
- multi-instance-safe scheduled-job claiming;
- dataset dependency/freshness validation based on MinIO READY manifests;
- domain-correct child execution metadata;
- type-safe notification routing contracts.

## Dataset Outputs

No new analytical dataset is required by this stabilization phase.

Existing analytical datasets become safer and should progressively publish MinIO metadata manifests under:

```text
stock-data/_metadata/datasets/...
```

See `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`.

## Algorithm Feature Outputs

No new formula is required.

This phase protects correctness/freshness of existing outputs such as:

```text
symbol-features
sector-features
breadth_above_ma20
leader_contribution
laggard_contribution
sector transition predictions/probabilities/outcomes
```

## Algorithms Unlocked

No new algorithm directly. It makes existing sector-wave, sector-transition, signal and future intraday algorithms safer to run automatically.

## Priority 0 — Correctness

### 1. Fix scheduler due-job query

```sql
WHERE j.isActive = true
  AND (j.nextRun <= :now OR j.nextRun IS NULL)
```

Tests:

- active + overdue => selected;
- active + null `nextRun` => selected;
- inactive + overdue => not selected;
- inactive + null `nextRun` => not selected.

### 2. Align the multi-sector feature universe

Sector Transition targets:

```text
FINANCIAL_SERVICES
REAL_ESTATE
BASIC_RESOURCES
BANKS
OIL_AND_GAS
```

Use one shared configured universe across stock sync, symbol features, sector features and transition jobs.

Every requested transition sector must have a READY `sector-features` manifest for the evaluation date.

### 3. Remove shared-Parquet multi-writer risk

Preferred V1:

```text
1 SECTOR_TRANSITION_ANALYZE job
  -> all configured focus sectors
  -> calculate once
  -> validate
  -> write each shared output once
  -> publish READY manifest
```

Use the same pattern for outcome evaluation.

## Priority 1 — Scheduler Hardening

### 4. Add atomic job claiming

Move from:

```text
find due jobs -> publish -> advance nextRun
```

to:

```text
find due jobs -> claim atomically -> dispatch
```

Use pessimistic/`SKIP LOCKED` or optimistic guarded update as appropriate.

### 5. Promote dataset dependencies to runtime validation

Reuse:

```json
{
  "dependsOnJobs": [],
  "dependsOnDatasets": [],
  "producesDatasets": []
}
```

For `dependsOnDatasets`, resolve the required MinIO manifest and validate:

```text
manifest exists
status == READY
expected evaluation date/partition exists
schema version supported
freshness acceptable
```

Do not scan the full Parquet prefix on every dependency check when a manifest exists.

V1 does not need a full DAG orchestrator.

### 6. Clean child execution semantics

Use:

```json
{
  "workKey": "...",
  "workType": "SYMBOL|SECTOR|EXCHANGE|STRATEGY"
}
```

Only symbol-level producers populate `symbolKey`.

### 7. Tighten notification types

Replace `Optional<Object>` with a common notification event abstraction.

Keep:

- default operational policy;
- signal digest policy;
- sector transition diagnostic policy.

Outcome-evaluation failures should receive actionable diagnostics where applicable.

## Verification

1. Run Java repository/service/scheduler tests.
2. Run analyzer sector-wave/sector-transition tests.
3. Run targeted Nx lint/test/build.
4. Run `nx affected` when shared contracts/configuration change.
5. Verify one end-to-end daily pipeline for all configured primary sectors.
6. Verify produced datasets publish valid READY manifests and dependent jobs reject stale/missing manifests.

## Acceptance Criteria

- Disabled jobs are never dispatched.
- All transition sectors have required upstream data.
- Shared Sector Transition outputs have one logical writer.
- Scheduled dispatch is safe across multiple core instances.
- Dataset dependency checks use MinIO manifests as readiness/freshness source.
- No PostgreSQL/Redis dataset-statistics cache is introduced for V1.
- Child execution metadata is domain-correct.
