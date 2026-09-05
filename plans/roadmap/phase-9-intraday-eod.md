# Phase 9 — Intraday EOD

## Goal

Introduce post-close intraday processing with the same contract, manifest, lineage, and single-writer guarantees established in earlier phases.

## Increment P9-I1 — Post-close intraday ingestion contracts and normalization

MVP decision (2026-09-05): all Phase 9 increments are deferred to [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md). The active product boundary remains daily/EOD processing.

| Field                   | Value                                                     |
| ----------------------- | --------------------------------------------------------- |
| id                      | P9-I1                                                     |
| title                   | Post-close intraday ingestion contracts and normalization |
| status                  | superseded                                                |
| priority                | medium                                                    |
| depends_on              | [P2-I3, P3-I3, P5-I2]                                     |
| blocks                  | [P9-I2]                                                   |
| owned_modules           | [contracts, apps/ingestor, libs/py-common]                |
| execution_mode          | autonomous                                                |
| requires_owner_decision | false                                                     |
| pr                      | null                                                      |
| last_verified_commit    | null                                                      |

Goal: ingest and normalize completed-session intraday trades using established contracts and manifests.

Acceptance criteria: provider timestamps normalize to UTC, session/partition identity is deterministic, duplicates/gaps/late corrections/session boundaries are validated before publication, and failed publication preserves previous READY state.

Required tests/checks: timestamp normalization, duplicate/gap/correction validation, manifest publication tests, and affected Nx checks.

Stop conditions: stop if provider data semantics or session calendar ownership is unclear.

## Increment P9-I2 — Intraday bars, reusable features, and manifests

| Field                   | Value                                           |
| ----------------------- | ----------------------------------------------- |
| id                      | P9-I2                                           |
| title                   | Intraday bars, reusable features, and manifests |
| status                  | superseded                                      |
| priority                | medium                                          |
| depends_on              | [P9-I1]                                         |
| blocks                  | [P9-I3, P10-I2]                                 |
| owned_modules           | [apps/analyzer, libs/py-common]                 |
| execution_mode          | autonomous                                      |
| requires_owner_decision | false                                           |
| pr                      | null                                            |
| last_verified_commit    | null                                            |

Goal: build canonical 1m bars, deterministic 5m/15m aggregates, and reusable intraday symbol features.

Acceptance criteria: repeated builds from same input produce identical bars/version identity, partial-session boundaries are tested, feature vocabulary matches planned realtime consumers, and each partition publishes READY manifests with lineage.

Required tests/checks: bar aggregation boundary tests, deterministic rebuild tests, feature schema tests, and Analyzer/py-common checks.

Stop conditions: stop if feature naming conflicts with [`docs/reference/001-algorithm-feature-catalog.md`](../../docs/reference/001-algorithm-feature-catalog.md).

## Increment P9-I3 — Sector aggregation and lineage publication

| Field                   | Value                                      |
| ----------------------- | ------------------------------------------ |
| id                      | P9-I3                                      |
| title                   | Sector aggregation and lineage publication |
| status                  | superseded                                 |
| priority                | medium                                     |
| depends_on              | [P9-I2, P1-I3]                             |
| blocks                  | [P10-I1]                                   |
| owned_modules           | [apps/analyzer, libs/py-common]            |
| execution_mode          | autonomous                                 |
| requires_owner_decision | false                                      |
| pr                      | null                                       |
| last_verified_commit    | null                                       |

Goal: build sector aggregates only from READY symbol partitions with exact lineage.

Acceptance criteria: sector aggregation uses one logical writer, all inputs are READY, exact trade/bar input versions are recorded, and failed corrected-session rebuild preserves previous READY.

Required tests/checks: READY-input enforcement, lineage tests, failed rebuild preservation, and affected Nx checks.

Stop conditions: stop if sector ownership from P1-I3 is incomplete.
