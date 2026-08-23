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

| Field                   | Value                |
| ----------------------- | -------------------- |
| id                      | P6-I1                |
| status                  | verification_pending |
| depends_on              | [P3-I3, P5-I2]       |
| execution_mode          | autonomous           |
| requires_owner_decision | false                |
| pr                      | null                 |
| last_verified_commit    | 5ecb6c2              |

Build read-only APIs for catalog, READY partitions, query submission/status,
cancellation, JSON results, and Arrow IPC results.

Verification state: implementation is merged on `main`, but the declared dependencies remain incomplete and no increment-specific PR, CI run, or complete acceptance evidence is recorded. Source presence does not satisfy the completion gate.

Acceptance criteria:

- SQL is limited to read-only statements and declared logical views.
- DML, DDL, `COPY`, `ATTACH`, `INSTALL`, `LOAD`, dangerous `PRAGMA`, arbitrary
  URLs, and direct `read_parquet` calls are rejected.
- timeout, memory, row, scan, and concurrency limits apply.
- audit records include actor, SQL hash, consumed `dataVersion`s, duration, rows,
  and terminal state.
- credentials and physical paths never cross the HTTP boundary.

## P6-I2 — Dataset Explorer and Viewer

| Field                   | Value                |
| ----------------------- | -------------------- |
| id                      | P6-I2                |
| status                  | verification_pending |
| depends_on              | [P6-I1]              |
| execution_mode          | autonomous           |
| requires_owner_decision | false                |
| pr                      | null                 |
| last_verified_commit    | 5ecb6c2              |

Scaffold `apps/omni-console`, browse dataset/partition/schema/status/version/size
metadata, and generate bounded server-side preview queries with filter, sort,
projection, and pagination.

Verification state: implementation is merged on `main`, but P6-I1 is not completed and no increment-specific PR, CI run, or complete acceptance evidence is recorded.

Acceptance criteria:

- Explorer uses manifests for summaries and does not scan Parquet.
- Viewer only opens the selected READY version.
- unknown/additive columns remain inspectable.
- large datasets are never loaded fully into the browser.

## P6-I3 — SQL Console

| Field                   | Value                |
| ----------------------- | -------------------- |
| id                      | P6-I3                |
| status                  | verification_pending |
| depends_on              | [P6-I2]              |
| execution_mode          | autonomous           |
| requires_owner_decision | false                |
| pr                      | null                 |
| last_verified_commit    | 5ecb6c2              |

Add Monaco editing, schema completion, run/cancel/rerun, Arrow result display,
bounded local history, and limited CSV export.

Verification state: partial source is merged on `main`, but P6-I2 is not completed. Monaco editing, schema completion, Arrow result display, history/export behavior, and required Nx/CI evidence must be verified before completion.

Acceptance criteria:

- operator can query latest committed READY data without DBeaver.
- status shows duration, rows, error, and consumed versions.
- UI stores SQL/history only, never credentials or physical paths.

## P6-I4 — Saved Queries and Dashboard

| Field                   | Value             |
| ----------------------- | ----------------- |
| id                      | P6-I4             |
| status                  | blocked           |
| depends_on              | [P6-I3, P5-I3]    |
| execution_mode          | approval_required |
| requires_owner_decision | true              |
| pr                      | null              |
| last_verified_commit    | null              |

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
