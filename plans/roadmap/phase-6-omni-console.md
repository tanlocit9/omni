# Phase 6 — Omni Console: Dataset Explorer First

## Goal

Create the extensible internal application only after the metadata and access boundaries it consumes are stable.

## Locked name and scope

Use [`apps/omni-console`](../../apps/omni-console) as the application path. Dataset Explorer is the first capability; Parquet inspection is a feature, not the product name.

## Increment P6-I1 — Console backend API and private-access boundary

| Field                   | Value                                           |
| ----------------------- | ----------------------------------------------- |
| id                      | P6-I1                                           |
| title                   | Console backend API and private-access boundary |
| status                  | pending                                         |
| priority                | high                                            |
| depends_on              | [P3-I3]                                         |
| blocks                  | [P6-I2, P6-I3]                                  |
| owned_modules           | [apps/core, docs]                               |
| execution_mode          | approval_required                               |
| requires_owner_decision | false                                           |
| pr                      | null                                            |
| last_verified_commit    | null                                            |

Goal: define read-only Platform APIs and private-access controls before UI scaffolding. The owner-approved V1 boundary is Platform read-only metadata APIs plus allow-listed, short-lived, read-only URLs resolved from authorized logical dataset/partition/version references.

Acceptance criteria: catalog/manifest/access resolver APIs are documented and tested, browser receives no object-store credentials, resolver rejects arbitrary URLs and unauthorized identity substitutions, and Internet-facing deployment requires identity-aware protection.

Required tests/checks: API contract tests, unauthorized object/version tests, private-access deployment check, and Core Nx checks.

Stop conditions: stop if implementation requires changing the approved access boundary, private network assumption, or identity-aware exposure requirement.

## Increment P6-I2 — Rename console implementation plan and scaffold application

| Field                   | Value                                                       |
| ----------------------- | ----------------------------------------------------------- |
| id                      | P6-I2                                                       |
| title                   | Rename console implementation plan and scaffold application |
| status                  | pending                                                     |
| priority                | medium                                                      |
| depends_on              | [P6-I1]                                                     |
| blocks                  | [P6-I3]                                                     |
| owned_modules           | [apps/omni-console, docs]                                   |
| execution_mode          | autonomous                                                  |
| requires_owner_decision | false                                                       |
| pr                      | null                                                        |
| last_verified_commit    | null                                                        |

Goal: follow the compatibility pointer in [`docs/INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`](../../docs/INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md) and scaffold the Nx React/Vite Omni Console application according to the canonical focused execution plan.

Acceptance criteria: project is named `omni-console`, Nx graph includes it on Linux, initial routes/components/tests exist without product write operations, and documentation references use the locked product name.

Required tests/checks: `nx show project omni-console`, lint/test/build for console target, and docs link checks.

Stop conditions: stop if scaffolding requires changing workspace conventions or public access assumptions.

## Increment P6-I3 — Dataset Explorer catalog, manifest, and query MVP

| Field                   | Value                                             |
| ----------------------- | ------------------------------------------------- |
| id                      | P6-I3                                             |
| title                   | Dataset Explorer catalog, manifest, and query MVP |
| status                  | pending                                           |
| priority                | medium                                            |
| depends_on              | [P6-I2]                                           |
| blocks                  | [P6-I4]                                           |
| owned_modules           | [apps/omni-console, apps/core]                    |
| execution_mode          | autonomous                                        |
| requires_owner_decision | false                                             |
| pr                      | null                                              |
| last_verified_commit    | null                                              |

Goal: implement private Dataset Explorer MVP against canonical metadata.

Acceptance criteria: private operator can search datasets, inspect current/historical manifests, view schema/statistics/lineage, preview bounded rows, run bounded read-only SQL, and see actionable errors.

Required tests/checks: component/route tests, API adapter tests, Playwright catalog-to-query flow, read-only SQL enforcement, and affected Nx checks.

Stop conditions: stop if metadata APIs are not stable or query limits require owner decision.

## Increment P6-I4 — Presigned URL refresh, browser safety, and private deployment verification

| Field                   | Value                                                                      |
| ----------------------- | -------------------------------------------------------------------------- |
| id                      | P6-I4                                                                      |
| title                   | Presigned URL refresh, browser safety, and private deployment verification |
| status                  | pending                                                                    |
| priority                | medium                                                                     |
| depends_on              | [P6-I3, P5-I3]                                                             |
| blocks                  | []                                                                         |
| owned_modules           | [apps/omni-console, apps/core, docker-compose]                             |
| execution_mode          | approval_required                                                          |
| requires_owner_decision | true                                                                       |
| pr                      | null                                                                       |
| last_verified_commit    | null                                                                       |

Goal: harden URL expiry handling, browser query limits, and private deployment posture.

Acceptance criteria: query execution resolves fresh URLs, idle tabs survive previous URL expiry, mid-query expiry discards partial results and requires explicit rerun, no presigned URLs persist in browser storage/logs/telemetry, and console/resolver are not anonymously Internet-accessible.

Required tests/checks: Playwright expiry tests, security tests for arbitrary URLs, browser memory/performance test, deployment privacy check, and affected Nx checks.

Stop conditions: owner must approve deployment exposure model and object-store CORS/range-request assumptions.
