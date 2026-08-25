# Phase 1 — Backend/Core Stabilization

## Goal

Make execution ownership, job scope, metadata, and internal events consistent before introducing new contracts.

## Phase eligibility

Depends on completed Phase 0. P1-I1 and P1-I2 are completed; P1-I3 is
`verification_pending`, and P1-I4 remains `ready` and unstarted.

## Increment P1-I1 — Claim data model and repository primitives

| Field                   | Value                                      |
| ----------------------- | ------------------------------------------ |
| id                      | P1-I1                                      |
| title                   | Claim data model and repository primitives |
| status                  | completed                                  |
| priority                | critical                                   |
| depends_on              | [P1-I0]                                    |
| blocks                  | [P1-I2, P4-I1]                             |
| owned_modules           | [apps/core, database]                      |
| execution_mode          | autonomous                                 |
| requires_owner_decision | false                                      |
| pr                      | https://github.com/tanlocit9/omni/pull/7   |
| last_verified_commit    | 8efc965b2084a16af9c733a9631e4e4729c23be4   |

Goal: implement the scheduler claim foundation from [`ADR-007`](../../docs/adr/ADR-007-scheduler-claim-and-outbox-boundary.md) without changing production dispatch flow.

Current verified baseline: ADR-007, claim migration and fields, PostgreSQL `SKIP LOCKED` repository primitives, fencing-token release, lease recovery, and Testcontainers coverage are merged in [PR #7](https://github.com/tanlocit9/omni/pull/7) and verified by [CI](https://github.com/tanlocit9/omni/actions/runs/31606526578).

Dependencies and eligibility: P1-I0 completed; no owner decision is required unless source contradicts ADR-007.

In scope: claim fields on job definitions, database migration/repository primitives, lease expiry selection, token/fencing semantics, tests for disabled jobs and expired claims.

Out of scope: creating executions, advancing `nextRun`, publishing Kafka, outbox rows, and changing [`JobScheduler`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/JobScheduler.java) production dispatch behavior.

Expected implementation approach: use PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`, UUID claim tokens, short transactions, bounded due-job batches, and Phase 0 due semantics.

Files or modules likely to be touched: [`apps/core`](../../apps/core), [`database/migrations`](../../database/migrations), scheduler repositories and tests.

Acceptance criteria:

- Active due jobs can be claimed once with a unique claim token and lease timestamps.
- Disabled jobs cannot be claimed.
- Unexpired claimed jobs are excluded from new claim acquisition.
- Expired claims are recoverable by a later claimant.
- Repository methods release only matching claim tokens.
- Production scheduler dispatch behavior is unchanged in this increment.

Required unit tests: repository claim acquisition/release/expiry tests and token mismatch tests.

Required integration or contract tests: real PostgreSQL lock behavior test; in-memory mocks are insufficient.

Required Nx/build/CI commands: inspect `nx show project core`, then run relevant Core test/lint/build targets through `nx run core:<target>` and affected checks where available.

Data migration or backward compatibility: migration must be additive and tolerate existing job definitions with null claim fields.

Risks: concurrency bugs, lease clock semantics, and accidental dispatch behavior changes.

Stop conditions: stop if ADR-007 no longer matches source behavior or if repository primitives require changing production dispatch flow.

Completion and rollback: rollback by reverting additive claim fields only before P1-I2 depends on them.

## Increment P1-I2 — Scheduler claim/outbox integration and concurrency tests

| Field                   | Value                                                    |
| ----------------------- | -------------------------------------------------------- |
| id                      | P1-I2                                                    |
| title                   | Scheduler claim/outbox integration and concurrency tests |
| status                  | completed                                                |
| priority                | critical                                                 |
| depends_on              | [P1-I1]                                                  |
| blocks                  | [P2-I1, P3-I1, P4-I1, P5-I1]                             |
| owned_modules           | [apps/core, database]                                    |
| execution_mode          | autonomous                                               |
| requires_owner_decision | false                                                    |
| pr                      | https://github.com/tanlocit9/omni/pull/8                 |
| last_verified_commit    | 6956a6eeef1897b343870e44480181cdf7812ae0                 |

Goal: atomically prepare job execution and dispatch ownership so two Core instances cannot dispatch the same logical execution.

Current verified baseline: claim acquisition, atomic execution/outbox preparation, fenced release, publish outside the transaction, stable retry identity, and PostgreSQL concurrency coverage are implemented in [PR #8](https://github.com/tanlocit9/omni/pull/8) and verified by [CI](https://github.com/tanlocit9/omni/actions/runs/31627082876).

In scope: claim-ready scheduler flow, parent/child execution creation, transactional outbox rows, `nextRun` advancement, exact matching claim release, publish outside transaction with stable execution/message identity.

Out of scope: dependency guard enforcement and Proto3 migration.

Acceptance criteria: concurrent schedulers produce exactly one logical execution, publish failures leave recoverable state, dispatch retry does not create a second logical execution, and lease recovery is observable.

Required tests: scheduler concurrency integration tests against PostgreSQL, publish-failure recovery tests, idempotency tests, and affected Core tests.

Stop conditions: stop if outbox boundary or idempotency key differs materially from ADR-007.

## Increment P1-I3 — Canonical sector universe and one shared transition writer

| Field                   | Value                                                      |
| ----------------------- | ---------------------------------------------------------- |
| id                      | P1-I3                                                      |
| title                   | Canonical sector universe and one shared transition writer |
| status                  | verification_pending                                       |
| priority                | high                                                       |
| depends_on              | [P1-I2]                                                    |
| blocks                  | [P3-I3, P9-I3]                                             |
| owned_modules           | [apps/core, apps/analyzer, configs]                        |
| execution_mode          | autonomous                                                 |
| requires_owner_decision | false                                                      |
| pr                      | https://github.com/tanlocit9/omni/pull/16                  |
| last_verified_commit    | ab2cc3cb0044c87d2b61a6736652c6fd4cfb2124                   |

### Goal

Establish one validated sector universe and one logical writer for shared Sector Transition outputs.

### Outcome

Platform seeds one analysis job and one outcome-evaluation job for the complete canonical sector universe. Existing producers resolve and validate that universe, and Analyzer computes and writes each shared output family once per scheduled execution instead of allowing competing per-sector scheduled writers.

### Dataset Outputs

No new analytical dataset output. Ownership changes for the existing `sector-transition-predictions`, `sector-transition-probabilities`, `sector-transition-decisions`, and `sector-transition-outcomes` datasets from competing per-sector schedules to one canonical-universe scheduled writer per output family.

### Metadata Outputs

No dataset metadata output. READY manifest publication remains out of scope until Phase 3.

### Algorithm Feature Outputs

No new direct algorithm feature output. Existing Sector Transition outputs become deterministic across the complete canonical universe because one execution owns each shared output family.

### Algorithms Unlocked

Complete-universe Sector Transition analysis and outcome evaluation can safely feed later manifest lineage and sector aggregation work without shared-writer races.

### Contract Impact

- Kafka/service-to-service protobuf: unchanged; existing job payload fields and wire format are unchanged.
- Object-storage JSON manifest: unchanged; this increment does not publish or modify READY manifests.
- Storage path/dataset ownership: logical paths are unchanged; scheduled ownership changes to one canonical-universe writer for each shared Sector Transition output family.
- Public Java/Python API: unchanged; seed generation is package-private and existing producer/Analyzer interfaces are reused.
- Configuration/environment contract: canonical sector configuration is unchanged; transition seeds now carry the full canonical universe with an empty focus list, which existing producer behavior resolves to the full universe.

### Repository Guidance Updates

No `AGENTS.md`, `CLAUDE.md`, `.roo/rules`, or canonical flow/data documentation update is required: repository guidance already requires one logical writer per shared dataset, and this change brings implementation into compliance without changing paths, wire contracts, manifest semantics, or developer workflow.

### Verification

PASS on 2026-08-25:

- targeted Platform scheduler/config and producer tests;
- `nx run platform:test`;
- `nx run analyzer:test` (79 passed, including Sector Transition computation/handler coverage);
- `nx run analyzer:lint`;
- `nx run analyzer:build`;
- `nx run platform:build`;
- `git diff --check` (line-ending warnings only);
- refreshed code-review graph and `detect_changes`: risk 0.60, no affected flows; graph-reported test gaps are false negatives for package-private seed functions covered through `JobDefinitionConfigTest`;
- [CI run #154](https://github.com/tanlocit9/omni/actions/runs/32870691112) passed for exact branch head `ab2cc3cb0044c87d2b61a6736652c6fd4cfb2124`.

The implementation is committed and CI-verified on draft PR #16, but the PR is owned by P3-I4 rather than being increment-specific. P1-I3 therefore remains `verification_pending`, and no dependent is promoted.

### Acceptance Criteria

- Unknown or focused sectors fail validation or are logged explicitly by the existing producer resolution boundary.
- Stock, indicator, signal, feature, and transition seeds derive from the same canonical sector universe.
- Exactly one scheduled analysis writer and one scheduled outcome writer own the shared Sector Transition output families.
- Platform and Analyzer tests, lint, and builds pass.
- Contract impact and repository-guidance impact are reviewed and recorded.
- Required PR, verified commit, and CI evidence are recorded before completion.

Stop conditions: stop if sector catalog ownership is unclear.

## Increment P1-I4 — workType/workKey metadata migration and notification event ownership

| Field                   | Value                                                                |
| ----------------------- | -------------------------------------------------------------------- |
| id                      | P1-I4                                                                |
| title                   | workType/workKey metadata migration and notification event ownership |
| status                  | ready                                                                |
| priority                | high                                                                 |
| depends_on              | [P1-I2]                                                              |
| blocks                  | [P2-I2, P4-I1, P8-I1]                                                |
| owned_modules           | [apps/core, libs/py-common, configs]                                 |
| execution_mode          | autonomous                                                           |
| requires_owner_decision | false                                                                |
| pr                      | null                                                                 |
| last_verified_commit    | null                                                                 |

Goal: normalize child execution metadata and ensure terminal notification events have one owner.

In scope: `workType`, `workKey`, optional legacy `symbolKey` read window, shared enum/value object, event payloads for completion and digest-ready notifications, single terminal aggregation publisher.

Out of scope: multi-channel notification routing and Proto3 transport migration.

Acceptance criteria: new child executions contain valid `workType` and `workKey`, readers accept legacy `symbolKey` during migration, writers emit normalized metadata, terminal SUCCESS aggregation emits digest-ready once, and consumers do not duplicate notification decisions.

Required tests: execution metadata tests, migration compatibility tests, notification event ownership tests, and producer/consumer impact inspection.

Stop conditions: stop if legacy compatibility window or terminal event owner is disputed.
