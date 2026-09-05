# Phase 10 — Realtime Per Tick

## Goal

Add live processing only after the intraday batch vocabulary and EOD reconciliation path are stable.

## Increment P10-I1 — Tick contract and ingestion

MVP decision (2026-09-05): all Phase 10 increments are deferred to [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md). Realtime processing requires a future product decision after the daily/EOD MVP.

| Field                   | Value                       |
| ----------------------- | --------------------------- |
| id                      | P10-I1                      |
| title                   | Tick contract and ingestion |
| status                  | superseded                  |
| priority                | low                         |
| depends_on              | [P9-I3, P2-I3]              |
| blocks                  | [P10-I2]                    |
| owned_modules           | [contracts, apps/ingestor]  |
| execution_mode          | autonomous                  |
| requires_owner_decision | false                       |
| pr                      | null                        |
| last_verified_commit    | null                        |

Goal: define and ingest versioned `MarketTick` events after batch intraday semantics are stable.

Acceptance criteria: tick contract has stable identity/event-time/sequence/deduplication/correlation fields, poison-message handling is separate from business failures, and malformed messages fail at the boundary with actionable reasons.

Required tests/checks: Proto3 contract checks, boundary validation tests, duplicate/malformed tick tests, and Ingestor checks.

Stop conditions: stop if provider tick guarantees are unknown or conditional fields are undocumented.

## Increment P10-I2 — Live bars, features, archive, and EOD reconciliation

| Field                   | Value                                                |
| ----------------------- | ---------------------------------------------------- |
| id                      | P10-I2                                               |
| title                   | Live bars, features, archive, and EOD reconciliation |
| status                  | superseded                                           |
| priority                | low                                                  |
| depends_on              | [P10-I1, P9-I2]                                      |
| blocks                  | []                                                   |
| owned_modules           | [apps/analyzer, apps/ingestor, libs/py-common]       |
| execution_mode          | autonomous                                           |
| requires_owner_decision | false                                                |
| pr                      | null                                                 |
| last_verified_commit    | null                                                 |

Goal: compute live bars/features, archive immutable micro-batches, and reconcile to canonical post-close sessions.

Acceptance criteria: replaying the same tick stream produces the same canonical output, reconnect/duplicate/late/out-of-order scenarios pass, archived micro-batches can rebuild the session, discrepancies are exposed, and incomplete data is never silently promoted.

Required tests/checks: stream replay determinism, reconnect/duplicate/out-of-order integration tests, archive rebuild tests, reconciliation tests, and affected Nx checks.

Stop conditions: stop if live feature contracts diverge from Phase 9 batch vocabulary.
