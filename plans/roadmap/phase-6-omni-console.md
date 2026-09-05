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

MVP decision (2026-09-05): all Phase 6 increments are deferred to [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md). Basic operator controls remain owned by completed Phase 7; existing merged Query Service and Console source is retained without active completion work.

| Field                   | Value          |
| ----------------------- | -------------- |
| id                      | P6-I1          |
| status                  | superseded     |
| depends_on              | [P3-I3, P5-I2] |
| execution_mode          | autonomous     |
| requires_owner_decision | false          |
| pr                      | null           |
| last_verified_commit    | 5ecb6c2        |

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

| Field                   | Value      |
| ----------------------- | ---------- |
| id                      | P6-I2      |
| status                  | superseded |
| depends_on              | [P6-I1]    |
| execution_mode          | autonomous |
| requires_owner_decision | false      |
| pr                      | null       |
| last_verified_commit    | 5ecb6c2    |

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

| Field                   | Value      |
| ----------------------- | ---------- |
| id                      | P6-I3      |
| status                  | superseded |
| depends_on              | [P6-I2]    |
| execution_mode          | autonomous |
| requires_owner_decision | false      |
| pr                      | null       |
| last_verified_commit    | 5ecb6c2    |

Add Monaco editing, schema completion, run/cancel/rerun, Arrow result display,
bounded local history, and limited CSV export.

Verification state: partial source is merged on `main`, but P6-I2 is not completed. Monaco editing, schema completion, Arrow result display, history/export behavior, and required Nx/CI evidence must be verified before completion.

Fresh verification on `feature/phase-7` passed the Console's declared lint,
typecheck, Vitest, and production build commands on 2026-08-24. The existing
suite currently contains only one broad App test, so acceptance-level coverage
for Monaco completion, Arrow rendering, history, and export remains incomplete.

Acceptance criteria:

- operator can query latest committed READY data without DBeaver.
- status shows duration, rows, error, and consumed versions.
- UI stores SQL/history only, never credentials or physical paths.

## P6-I4 — Fixed Market Dashboard

| Field                   | Value      |
| ----------------------- | ---------- |
| id                      | P6-I4      |
| status                  | superseded |
| depends_on              | [P6-I3]    |
| execution_mode          | autonomous |
| requires_owner_decision | false      |
| pr                      | null       |
| last_verified_commit    | null       |

Make a fixed, code-owned Market Dashboard the default Omni Console section. The
first useful slice provides freshness, market breadth, top movers, sector
strength, and recent signals when their source contracts support those views.
Dataset components use bounded Query Service contracts and fail independently.

Persisted layouts, personalization, arbitrary widget definitions, and user-owned
SQL templates are outside this increment.

Acceptance criteria:

- Dashboard is active on first render while Dataset Explorer, SQL Console, and
  Jobs retain their existing behavior.
- an explicit compile-time registry allowlists fixed dataset components;
- each widget distinguishes loading, ready, empty, stale, unavailable, and error
  states without hiding sibling widgets;
- analytical widgets expose their effective data date and consumed
  `dataVersion`s when available from the source contract;
- dashboard reads use explicit bounded endpoints or fixed, code-owned Query
  Service definitions over logical dataset aliases;
- browser requests contain no object-store credentials, physical paths,
  arbitrary SQL, or remotely supplied component definitions;
- desktop and mobile layouts preserve a logical, accessible reading order;
- the deployment identity boundary supplies a trusted operator principal for
  Query Service requests.

Source datasets do not need a new dashboard-specific READY-manifest contract.
Existing manifests may supply readiness, freshness, and provenance where already
available; otherwise the bounded dashboard response must report truthful
availability and date/version metadata from its supported source contract.

Identity-boundary evidence on `feature/phase-7`: Query Service rejects a
missing/blank `X-Omni-User` on query submission and propagates the normalized
operator identity into the audited query record. Dashboard implementation must
route requests through the same trusted identity-aware boundary rather than add
an anonymous fallback.
