# Phase 6 — Omni Console and Server-side Query

## Goal

Provide a private Dataset Explorer, Parquet Viewer, SQL Console, and Dashboard
without exposing object-storage credentials or physical paths to the browser.

## Locked direction

- UI: `apps/omni-console`.
- Analytical read boundary: `apps/query-service`.
- Query engine: native DuckDB and PyArrow on the server.
- Identity: logical dataset, partition, and optional `dataVersion` resolved from
  canonical `READY` manifests.
- Results: bounded JSON for small previews and Arrow IPC for larger SQL results.
- DuckDB-Wasm is not part of V1.
- Internet-facing deployment remains behind identity-aware access.

## P6-I1 — Query Service

Build read-only APIs for catalog, READY partitions, query submission/status,
cancellation, JSON results, and Arrow IPC results.

Acceptance criteria:

- SQL is limited to read-only statements and declared logical views.
- DML, DDL, `COPY`, `ATTACH`, `INSTALL`, `LOAD`, dangerous `PRAGMA`, arbitrary
  URLs, and direct `read_parquet` calls are rejected.
- timeout, memory, row, scan, and concurrency limits apply.
- audit records include actor, SQL hash, consumed `dataVersion`s, duration, rows,
  and terminal state.
- credentials and physical paths never cross the HTTP boundary.

## P6-I2 — Dataset Explorer and Viewer

Scaffold `apps/omni-console`, browse dataset/partition/schema/status/version/size
metadata, and generate bounded server-side preview queries with filter, sort,
projection, and pagination.

Acceptance criteria:

- Explorer uses manifests for summaries and does not scan Parquet.
- Viewer only opens the selected READY version.
- unknown/additive columns remain inspectable.
- large datasets are never loaded fully into the browser.

## P6-I3 — SQL Console

Add Monaco editing, schema completion, run/cancel/rerun, Arrow result display,
bounded local history, and limited CSV export.

Acceptance criteria:

- operator can query latest committed READY data without DBeaver.
- status shows duration, rows, error, and consumed versions.
- UI stores SQL/history only, never credentials or physical paths.

## P6-I4 — Saved Queries and Dashboard

Store Saved Query and Dashboard configuration in Platform-owned PostgreSQL.
Widgets reference Saved Queries and support table, KPI, and basic chart views.

Acceptance criteria:

- Saved Query contains SQL template, parameters, owner/visibility, result limit,
  and refresh policy.
- cache identity includes normalized SQL, parameters, and consumed
  `dataVersion`s.
- widget failures remain isolated.
- the deployment identity model supplies a trustworthy operator principal.

Stop if authentication/principal ownership is not defined; do not make Console
APIs anonymous merely to complete the dashboard.
