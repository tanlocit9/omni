# Job Dependency Guard Implementation Plan

## Goal

Turn existing job dependency metadata from documentation-only information into a lightweight runtime gate based primarily on object-storage dataset manifests.

Do not introduce Airflow/Dagster/full DAG orchestration in V1.

## Outcome

After this phase:

- cron means "eligible to check/run", not "run regardless of upstream state";
- downstream jobs do not start against missing/stale datasets;
- dependency checks require only small manifest reads instead of Parquet scans;
- dataset dependencies can coordinate jobs across different machines sharing S3/R2;
- a dependency that is not ready blocks/defer execution without creating false FAILED job history noise;
- lineage can detect when a READY downstream dataset was built from an outdated upstream data version.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

Extend dataset manifests with deterministic data-version lineage:

```json
{
  "status": "READY",
  "dataVersion": "sha256:...",
  "inputs": [
    {
      "dataset": "eod",
      "partition": { "date": "2026-08-11" },
      "dataVersion": "sha256:..."
    }
  ]
}
```

`dataVersion` identifies the canonical dataset contents/version, not merely the execution time.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No new algorithm directly. It makes signals, sector-wave, sector-transition, intraday and backtest runs deterministic with respect to their declared inputs.

## Dependency Priority

Prefer:

```text
dependsOnDatasets = hard data dependency
```

over:

```text
dependsOnJobs = operational/traceability dependency
```

A downstream job should normally care that the required canonical dataset is READY/current, not which machine or exact job execution produced it.

## V1 Conditions

Support a small explicit condition set:

```text
EXISTS
READY
PARTITION_MATCH
MIN_ROW_COUNT
SUPPORTED_SCHEMA_VERSION
MAX_FRESHNESS_LAG
CURRENT_INPUTS
```

Do not introduce an arbitrary expression language in V1.

## Example

`SYNC_SIGNALS` becomes eligible at 18:35 but requires:

```text
eod(date=T) READY
indicators(date=T,timeframe=1d) READY
indicators CURRENT_INPUTS relative to current EOD
```

If indicators complete at 18:42:

```text
18:35 dependency check -> BLOCKED
18:37 dependency check -> BLOCKED
18:42 dependency check -> READY
18:42 dispatch job
```

The 5-minute cron gap is a scheduling hint, not the correctness mechanism.

## Runtime Model

```text
job becomes due
     |
     v
DependencyGuard
     |
     +-- resolve DatasetRef
     +-- read manifest
     +-- evaluate conditions
     |
     +-- READY ------> atomic claim -> create execution -> dispatch
     |
     +-- BLOCKED ----> record reason -> retry later
```

Do not create a FAILED execution merely because an upstream dataset is not ready yet.

## Dependency Result

Suggested Java abstraction:

```java
public interface JobDependencyGuard {
    DependencyCheckResult check(JobDefinition job, JobExecutionContext context);
}
```

Result statuses:

```text
READY
MISSING
NOT_READY
STALE
EMPTY
INVALID_SCHEMA
INPUT_VERSION_MISMATCH
```

Persist/log the current blocking reason for observability, but avoid creating a new history row on every scheduler poll.

## Retry / Deferral

Use bounded retry/backoff for due-but-blocked jobs, for example:

```text
30s -> 1m -> 2m -> max 5m
```

No Kafka message is needed merely to wait for a dependency.

A later optimization may trigger immediate re-check from a dataset-ready event, but polling small manifests is sufficient for V1.

## Data Version / Current Inputs

`READY` alone is insufficient.

Example:

```text
current EOD dataVersion          = B
Indicators.inputs[eod]           = A

Indicators is physically READY
but logically STALE relative to current EOD.
```

`CURRENT_INPUTS` compares the data versions recorded in the downstream manifest against current upstream manifests.

This produces lightweight lineage:

```text
EOD A
  -> Indicators B (input=A)
      -> Signals C (inputs=A,B)
```

## Cross-Machine Coordination

Because manifests are centralized in S3/R2:

```text
Machine A writes EOD + READY manifest
Machine B checks EOD manifest
Machine B can safely run Indicators
```

Platform job history is not required as the cross-machine data readiness source.

## Proto3 Contract Interaction

Use the shared protobuf `DatasetRef`/`DatasetOutput` types at service boundaries.

The dependency guard itself reads JSON `DatasetManifest` objects from S3/R2.

Do not duplicate the logical dataset naming/partition conventions in unrelated Java/Python DTOs.

See `CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md`.

## Implementation Steps

1. Add `dataVersion` and `inputs[]` to the DatasetManifest contract.
2. Generate deterministic `dataVersion` after successful data validation.
3. Expand job dependency config from documentation-only names to typed/structured conditions.
4. Implement shared manifest resolver/read client in Platform.
5. Implement `JobDependencyGuard` and condition evaluators.
6. Integrate guard before atomic scheduler claim/execution creation.
7. Add blocked-reason/backoff state without job-history spam.
8. Add operations/Internal Tools visibility for blocked dependencies.
9. Migrate core analytical jobs incrementally from `DOCUMENTATION_ONLY` to enforced mode.

## Repository Guidance Updates

Implementation must update:

```text
AGENTS.md
CLAUDE.md when agent-specific scheduler/tool instructions change
.roo/rules/
docs/flows/job-execution.md
docs/data/data-lake.md when manifest lineage semantics change
```

Agent/rule guidance must explicitly state that:

- cron gaps are not dependency guarantees;
- dataset manifests are the hard readiness source;
- BLOCKED dependency is not FAILED execution;
- full Parquet scans are not allowed for normal readiness checks;
- `CURRENT_INPUTS` uses upstream `dataVersion` lineage.

## Verification

- repository tests for every dependency status;
- scheduler tests for due-but-blocked vs due-and-ready;
- multi-instance claim test after dependencies pass;
- stale-input/dataVersion mismatch test;
- S3/R2 manifest read failure/retry test;
- Nx targeted/affected checks;
- graph impact check for scheduler/shared contracts.

## Acceptance Criteria

- `dependencyMode` can be enforced for selected jobs.
- Missing/stale dependencies defer jobs without false failure history.
- READY/current checks use manifests instead of Parquet scans.
- `CURRENT_INPUTS` detects downstream outputs built from old upstream versions.
- jobs on separate hosts can coordinate through centralized manifests.
- no full DAG orchestrator is required.
- agent guidance and Zoo Code rules reflect the dependency semantics.
