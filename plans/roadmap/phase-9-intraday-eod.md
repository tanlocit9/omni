# Phase 9 — Intraday EOD

## Goal

Introduce post-close intraday processing with the same contract, manifest, lineage, and single-writer guarantees established in earlier phases.

## Increment P9-I1 — Post-close intraday ingestion contracts and normalization

MVP decision (2026-09-05): all Phase 9 increments were deferred to [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md).

Owner reactivation decision (2026-09-10): P9-I1 alone is reactivated for a bounded slice using the vnstock Python package with VCI, the latest completed session for HOSE, HNX, and UPCOM, all active symbols on each configured exchange, and normalized trades only. D9-1 through D9-6 are approved in [`docs/plans/013-intraday-eod.md`](../../docs/plans/013-intraday-eod.md). P9-I2 and P9-I3 remain superseded; bars, features, sectors, Console, and realtime coupling are excluded.

| Field                   | Value                                                     |
| ----------------------- | --------------------------------------------------------- |
| id                      | P9-I1                                                     |
| title                   | Post-close intraday ingestion contracts and normalization |
| status                  | in_progress                                               |
| priority                | medium                                                    |
| depends_on              | []                                                        |
| blocks                  | [P9-I2]                                                   |
| owned_modules           | [contracts, apps/ingestor, libs/py-common]                |
| execution_mode          | autonomous                                                |
| requires_owner_decision | false                                                     |
| pr                      | null                                                      |
| last_verified_commit    | null                                                      |

Goal: ingest and normalize completed-session intraday trades using the approved provider, local-date validation, correction, reconciliation, and partition contracts.

Eligibility: owner approval of D9-1 through D9-6 and the bounded initial slice was recorded on 2026-09-10. P9-I1 is active without reactivating Proto3 migration, advanced manifest migration, portable deployment, Console work, bars/features, sector aggregation, or realtime processing.

Implementation evidence (2026-09-10): bounded source now includes intraday
path/reconciliation/immutable-publication primitives, Platform `SYNC_INTRADAY_EOD`
seed/producer/message and configured active-symbol selection for HOSE/HNX/UPCOM,
shared topic, Ingestor command/router/VCI cursor adapter/normalization/publication handler, redacted provider
fixture, focused tests, and synchronized Kafka/data-lake/flow documentation. Provider
timestamps are converted to `Asia/Ho_Chi_Minh` and must match the requested local date;
normalized persisted timestamps remain UTC. No calendar-version lineage, holiday, or
session-segment validation is included. This is source evidence only; no verification
command, commit, PR, CI, deployment, or production run is claimed.

Acceptance criteria: all six decisions have linked contract/fixture evidence; provider timestamps convert to `Asia/Ho_Chi_Minh`, match the requested local date, and persist normalized in UTC; session completeness and EOD reconciliation produce explicit outcomes; session/partition identity is deterministic; duplicates, gaps, pagination, late corrections, and boundaries follow approved policies; publication metadata has no calendar-version lineage; and failed publication preserves previous READY state.

Required tests/checks (**not run**): provider fixture mapping, local-date boundaries, completeness outcomes, reconciliation tolerance boundaries, duplicate/gap/correction behavior, deterministic object/partition identity, failed READY preservation, metadata lineage publication, and affected Nx checks.

Stop conditions: stop if any decision gate remains unresolved, provider fixtures are unavailable or contradictory, completeness/reconciliation would rely on guessed thresholds, correction behavior can overwrite the last valid READY state, or implementation would reactivate unrelated deferred work.

## Increment P9-I2 — Intraday bars, reusable features, and manifests

| Field                   | Value                                           |
| ----------------------- | ----------------------------------------------- |
| id                      | P9-I2                                           |
| title                   | Intraday bars, reusable features, and manifests |
| status                  | superseded                                      |
| priority                | medium                                          |
| depends_on              | [P9-I1 completed and separately reactivated]    |
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
| depends_on              | [P9-I2 completed, P1-I3]                   |
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
