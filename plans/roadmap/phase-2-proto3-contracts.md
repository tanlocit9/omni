# Phase 2 — Cross-Service Proto3 Contracts

## Goal

Replace untyped cross-service message maps with versioned generated Java/Python contracts while keeping domain models independent.

## Increment P2-I1 — Contracts project, Buf targets, and initial v1 schemas

| Field                   | Value                                                  |
| ----------------------- | ------------------------------------------------------ |
| id                      | P2-I1                                                  |
| title                   | Contracts project, Buf targets, and initial v1 schemas |
| status                  | pending                                                |
| priority                | critical                                               |
| depends_on              | [P1-I2]                                                |
| blocks                  | [P2-I2, P8-I1, P9-I1]                                  |
| owned_modules           | [contracts, apps/core, libs/py-common]                 |
| execution_mode          | autonomous                                             |
| requires_owner_decision | false                                                  |
| pr                      | null                                                   |
| last_verified_commit    | null                                                   |

Goal: create the canonical `contracts` Nx project with Buf format/lint/generate/breaking targets and initial `DatasetRef`, `DatasetOutput`, `ExecutionStatus`, `JobCommand`, and `JobStatusEvent` schemas.

Current verified baseline: no canonical [`contracts`](../../contracts) project exists in the current tree listing.

In scope: versioned proto package, zero-valued unspecified enums, pinned generation tooling, generated-code consistency check, and CI integration.

Out of scope: migrating production producers/consumers.

Acceptance criteria: contracts project appears in Nx graph, Buf checks are runnable through Nx, generated Java/Python code is reproducible, and committed generated output fails CI when stale.

Required tests/checks: Buf format/lint/breaking/generate, generated-code clean check, and Nx affected checks.

Stop conditions: stop if package ownership, field numbering, or compatibility baseline is unclear.

## Increment P2-I2 — Generated Java/Python adapters and golden fixtures

| Field                   | Value                                                                |
| ----------------------- | -------------------------------------------------------------------- |
| id                      | P2-I2                                                                |
| title                   | Generated Java/Python adapters and golden fixtures                   |
| status                  | pending                                                              |
| priority                | critical                                                             |
| depends_on              | [P2-I1, P1-I4]                                                       |
| blocks                  | [P2-I3, P3-I2]                                                       |
| owned_modules           | [contracts, apps/core, libs/py-common, apps/analyzer, apps/ingestor] |
| execution_mode          | autonomous                                                           |
| requires_owner_decision | false                                                                |
| pr                      | null                                                                 |
| last_verified_commit    | null                                                                 |

Goal: add Java/Python boundary adapters and cross-language fixtures without generated types leaking into domain models.

Acceptance criteria: Java can read Python fixtures, Python can read Java fixtures, unsupported enum values fail validation with actionable errors, and domain handlers remain mapped through adapters.

Required tests/checks: golden binary fixture tests, adapter validation tests, producer/consumer impact review, and relevant Nx targets.

Stop conditions: stop if a boundary lacks a clear producer and consumer owner.

## Increment P2-I3 — Pilot dual-read migration, producer switch, and observation window

| Field                   | Value                                                              |
| ----------------------- | ------------------------------------------------------------------ |
| id                      | P2-I3                                                              |
| title                   | Pilot dual-read migration, producer switch, and observation window |
| status                  | pending                                                            |
| priority                | high                                                               |
| depends_on              | [P2-I2]                                                            |
| blocks                  | [P6-I1, P7-I1]                                                     |
| owned_modules           | [apps/core, apps/analyzer, apps/ingestor, configs]                 |
| execution_mode          | approval_required                                                  |
| requires_owner_decision | true                                                               |
| pr                      | null                                                               |
| last_verified_commit    | null                                                               |

Goal: migrate one high-value boundary using dual-read consumers before producer switch.

Acceptance criteria: consumers accept legacy JSON and Proto3, one producer switches after compatibility tests, metrics show decode/validation failures, and legacy removal is tracked but not premature.

Required tests/checks: pilot producer/consumer integration test, fixture compatibility tests, and Nx affected checks.

Stop conditions: owner must approve pilot boundary, compatibility window, topic/content-type strategy, and legacy removal timing.
