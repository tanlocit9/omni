# Internal Tools / Parquet Viewer Detailed Plan — Compatibility Pointer

Status: Superseded

The former `apps/internal-tools` plan is retained at this path only so historical links do not break. Do not scaffold or implement `apps/internal-tools`.

Canonical direction:

- Application: `apps/omni-console`
- Product: Omni Console
- Features: Dataset Explorer, Parquet Viewer, and Data Health Dashboard
- Private access: Query Service resolves logical dataset/partition/version references from READY manifests; physical paths and credentials remain server-side
- Query engine: native DuckDB in `apps/query-service`; the browser is a thin client and cannot submit arbitrary URLs or write SQL

Execute [`plans/omni-metadata-console-dashboard-execution-plan.md`](omni-metadata-console-dashboard-execution-plan.md) from M0. Roadmap ownership remains in [`plans/roadmap/phase-6-omni-console.md`](roadmap/phase-6-omni-console.md).
