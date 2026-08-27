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

See [`DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`](DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md).

## Metadata Outputs

P1-I4 changes only Platform operational execution metadata:

```json
{
  "workType": "SYMBOL|SECTOR|EXCHANGE|GLOBAL",
  "workKey": "..."
}
```

Operators manually clear historical execution rows during the approved maintenance
window. V9 validates that history is empty and installs canonical indexes without
deleting or rewriting records. No dataset manifest, READY pointer, schema metadata,
or lineage output changes in this phase.

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

### 5. Preserve dataset dependencies for Phase 4 runtime validation

Reuse:

```json
{
  "dependsOnJobs": [],
  "dependsOnDatasets": [],
  "producesDatasets": []
}
```

For `dependsOnDatasets`, Phase 4 will resolve the required MinIO manifest and validate:

```text
manifest exists
status == READY
expected evaluation date/partition exists
schema version supported
freshness acceptable
```

Do not scan the full Parquet prefix on every dependency check when a manifest exists.

Phase 1 keeps dependency metadata documentation-only and must not block, reorder, or fail dispatch based on dependency manifests. Runtime dependency enforcement belongs to Phase 4 and does not need a full DAG orchestrator.

### 6. Clean child execution semantics

Use:

```json
{
  "workKey": "...",
  "workType": "SYMBOL|SECTOR|EXCHANGE|GLOBAL"
}
```

`workType` and `workKey` are the only generic execution identity. Do not keep a
dual-write or fallback to `symbolKey`. Before deployment, pause dispatch, drain
outbox/in-flight work, snapshot PostgreSQL, manually clear execution history,
validate zero remaining rows, deploy every status producer/consumer together, and
remove old compatibility code. `symbolKey` may remain only inside symbol-domain
commands where it has business meaning; it is not copied into generic execution
metadata.

### 7. Tighten notification types

Replace `Optional<Object>` with a common notification event abstraction.

Keep:

- default operational policy;
- signal digest policy;
- sector transition diagnostic policy.

Outcome-evaluation failures should receive actionable diagnostics where applicable.

## Contract Impact

- Kafka/service-to-service protobuf: active JSON job commands and status events use
  required `workType`/`workKey`; Platform, Analyzer, Ingestor, and shared Python
  contracts change together. Existing Proto3 foundation schemas are unchanged.
- Object-storage JSON manifests: unchanged; READY-last and `dataVersion` lineage
  semantics remain mandatory.
- Storage paths/dataset ownership: unchanged; physical object paths remain outside
  Kafka business messages.
- Public Java/Python APIs: shared execution/status DTOs and publishers require
  canonical work identity. Domain `symbolKey` remains in genuine symbol commands
  and notification content only.
- Configuration/environment: Kafka topic names and defaults are unchanged. P1-I4
  deployment requires a maintenance window, drained scheduler/outbox/Kafka work,
  verified PostgreSQL snapshot, and coordinated Platform/Ingestor/Analyzer images.

## Repository Guidance Updates

Canonical Kafka, database, job-execution, and P1-I4 deployment documentation is
synchronized with the implementation. Existing [`AGENTS.md`](../AGENTS.md),
[`CLAUDE.md`](../CLAUDE.md), and [`.roo/rules`](../.roo/rules) already encode the
contract-review, graph-impact, migration, and hard-cutover safeguards, so no new
agent rule is required.

## Verification

1. Run Java repository/service/scheduler tests.
2. Run Analyzer sector-wave/sector-transition tests.
3. Run targeted Nx lint/test/build through project-defined targets.
4. Run `nx affected` when shared contracts/configuration change.
5. Run V9 twice against disposable PostgreSQL with empty-history and rejection
   fixtures; require zero execution-history rows and never use production for tests.
6. Run graph change detection, impact review, and static searches for obsolete
   generic `symbolKey` compatibility paths.
7. Verify one end-to-end daily pipeline for all configured primary sectors during
   the owner-confirmed production maintenance window, not during implementation.
8. In Phase 4, verify produced datasets publish valid READY manifests and dependent jobs reject stale/missing manifests.

P1-I4 local evidence is recorded canonically in the
[Phase 1 roadmap increment](../plans/roadmap/phase-1-backend-core-stabilization.md#increment-p1-i4--worktypeworkkey-hard-cutover-and-notification-event-ownership)
and [`execution-log.md`](../plans/roadmap/execution-log.md). It remains
`verification_pending` until the final pushed head has successful exact-head CI and
all repository gates are green.

## Acceptance Criteria

- Disabled jobs are never dispatched.
- All transition sectors have required upstream data.
- Shared Sector Transition outputs have one logical writer.
- Scheduled dispatch is safe across multiple core instances.
- Dataset dependency checks remain metadata-only until Phase 4 promotes MinIO manifests to readiness/freshness enforcement.
- No PostgreSQL/Redis dataset-statistics cache is introduced for V1.
- Child execution metadata is domain-correct.
