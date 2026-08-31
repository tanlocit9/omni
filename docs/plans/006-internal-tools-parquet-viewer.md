# Internal Tools / Parquet Viewer Plan — Compatibility Pointer

Status: Superseded name and execution sequence

## Goal

Preserve existing links while directing implementation to the canonical Omni Console plan.

## Outcome

No implementation should create `apps/internal-tools` or treat Parquet Viewer as a standalone product. The application is Omni Console at `apps/omni-console`; Dataset Explorer, Parquet Viewer, and Data Health Dashboard are features.

Use these canonical plans:

1. [`plans/omni-metadata-console-dashboard-execution-plan.md`](../../plans/omni-metadata-console-dashboard-execution-plan.md) — milestone-gated delivery sequence and approved private-access boundary.
2. [`plans/roadmap/phase-6-omni-console.md`](../../plans/roadmap/phase-6-omni-console.md) — roadmap increments for Omni Console.
3. [`docs/plans/003-dataset-metadata-manifest.md`](003-dataset-metadata-manifest.md) — persisted JSON metadata contract.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output. Omni Console consumes canonical manifests; it does not define a second manifest layout or statistics store.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

The canonical plan enables dataset QA, schema/freshness inspection, bounded Parquet exploration, lineage diagnosis, and data-health monitoring.

## Contract Impact

- Kafka/service-to-service protobuf: none.
- Object-storage JSON manifest: consumed read-only from the canonical metadata contract.
- Storage path/dataset ownership: unchanged.
- Public Java/Python API: unchanged by this pointer.
- Configuration/environment: defined by the canonical Omni Console plan.
- Platform HTTP API: read-only metadata and allow-listed short-lived data-access resolution are defined by the canonical plan.

## Repository Guidance Updates

No additional guidance update is required by this compatibility pointer. Implementation must follow the synchronization list in the canonical plan.

## Verification

Verify links resolve and ensure no active plan instructs creation of `apps/internal-tools`.

## Acceptance Criteria

- [x] Existing links remain valid.
- [x] Superseded product/path naming is explicit.
- [x] Canonical execution and roadmap documents are linked.
