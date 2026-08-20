# Internal Tools / Parquet Viewer Detailed Plan — Compatibility Pointer

Status: Superseded

The former `apps/internal-tools` plan is retained at this path only so historical links do not break. Do not scaffold or implement `apps/internal-tools`.

Canonical direction:

- Application: `apps/omni-console`
- Product: Omni Console
- Features: Dataset Explorer, Parquet Viewer, and Data Health Dashboard
- Private access: Platform read-only APIs resolving authorized logical dataset/partition/version references to allow-listed, short-lived, read-only URLs
- Query engine: DuckDB-Wasm with bounded structured queries; no arbitrary URLs or unrestricted SQL

Execute [`plans/omni-metadata-console-dashboard-execution-plan.md`](omni-metadata-console-dashboard-execution-plan.md) from M0. Roadmap ownership remains in [`plans/roadmap/phase-6-omni-console.md`](roadmap/phase-6-omni-console.md).
