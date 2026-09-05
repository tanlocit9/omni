# Omni — Consolidated Implementation Roadmap

Status: Canonical autonomous-delivery roadmap

Application name: Omni Console

Primary repository: tanlocit9/omni

Planning baseline: main

Default integration branch: main

Last source cross-check: draft PR #16 includes completed P3-I4 at `ab2cc3cb0044c87d2b61a6736652c6fd4cfb2124` and completed P1-I4 at `6c51dd600c06305d9841d60f98cab89a6e54c0ca`, each with exact-head successful CI; the PR remains unmerged for owner review. P1-I3 remains verification-pending because increment-specific PR evidence is absent. On 2026-09-04 the owner prioritized Phase 8 Telegram formatting and signal notifications ahead of P2-I2 under the explicit no-Kafka/no-Proto3 dependency exception recorded below.

## Objective

This roadmap moves Omni from a working single-node data pipeline toward a contract-driven, observable, portable platform with safe scheduling, typed integration contracts, versioned datasets, dependency enforcement, portable deployment, Omni Console, safe operator job controls, notification routing, intraday processing, and realtime ingestion.

This roadmap is structured for daily autonomous delivery:

1. inspect repository and roadmap state;
2. select the next eligible unfinished increment;
3. create or continue one dedicated draft pull request;
4. implement only that increment;
5. add and run objective tests;
6. push and inspect CI;
7. repair attributable failures within a three-attempt budget;
8. update roadmap progress and evidence;
9. report the result and next eligible work.

## Canonical hierarchy

```text
Pre-roadmap capability baseline (historical; not scheduled)
└── Roadmap
    └── Capability group
        └── Phase
            └── Increment
                └── Task
```

A capability group is a navigation layer over related phases; it does not change phase numbers, dependencies, increment IDs, or selection rules. A phase is a coherent architectural capability. An increment is the smallest independently reviewable and verifiable delivery unit. A task is an implementation step inside an increment and is not independently scheduled.

## Inherited Pre-Roadmap Platform

Phase 0 starts from an existing working platform, not a greenfield repository. The inherited baseline already includes:

- an Nx monorepo with Platform/Core, Ingestor, and Analyzer services plus shared Python infrastructure;
- PostgreSQL-backed job definitions and execution history, cron scheduling, parent/child aggregation, Kafka job/status flows, and basic Telegram delivery;
- provider-backed symbol and EOD ingestion with normalized Parquet persistence and symbol/sector projections;
- indicator calculation, signal generation/history, forward evaluation, and signal notifications;
- Sector Wave symbol/sector features, ranking, rotation backtests, and an early Sector Transition research track;
- MinIO/S3-compatible Parquet datasets with shared topic and logical-path configuration;
- local Docker/Compose assets for the service and infrastructure stack.

This baseline describes capability presence only. It does not imply the later correctness, claim/outbox safety, Proto3 migration, deterministic manifests, dependency enforcement, portability, Console, or higher-frequency acceptance criteria were already complete. See the [pre-roadmap capability baseline](pre-roadmap-capability-baseline.md) for boundaries and canonical source links.

## Current source baseline

| Area                | Current verified source state                                                                                                                                                                                                                                                                                                                                                                         | Roadmap implication                                                                                                                 |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Scheduler due query | [`JobDefinitionRepository.findJobsDue()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobDefinitionRepository.java) applies the active predicate to both due conditions with deterministic ordering, and repository coverage is present.                                                                                                                           | P0-I1 is completed with merged source and successful CI evidence.                                                                   |
| Scheduler claiming  | Claim fields, PostgreSQL `SKIP LOCKED` acquisition, atomic execution/outbox preparation, fenced release, stable publish retry identity, and Testcontainers concurrency coverage are implemented and CI-verified in PR #8.                                                                                                                                                                             | P1-I2 is completed; its autonomous direct dependents are ready.                                                                     |
| Dependency metadata | [`JobDefinitionConfig`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/constants/JobDefinitionConfig.java) seeds dependency metadata, but it remains documentation-only.                                                                                                                                                                                                           | Preserve metadata until Phase 4 guard enforcement.                                                                                  |
| Sector execution    | P1-I3 seeds one canonical-universe analysis writer and one outcome writer for shared Sector Transition outputs; Platform/Analyzer checks, graph review, commit, and exact-head CI pass on draft PR #16, but the PR is owned by P3-I4 rather than increment-specific.                                                                                                                                  | P1-I3 remains `verification_pending` and does not yet unblock manifest-dependent sector publication work.                           |
| Contracts           | The [`contracts`](../../libs/contracts) Nx project owns versioned common/job Proto3 schemas under `libs/contracts/proto`. P1-I4 changes the active JSON execution/status boundary to required `workType`/`workKey` with no generic `symbolKey` compatibility path; the Proto3 foundation itself remains unchanged.                                                                                    | P2-I1 is completed; P1-I4 is verification-pending, so P2-I2 remains pending.                                                        |
| Dataset manifests   | [`py_common`](../../libs/py-common/py_common) implements the canonical JSON manifest contract, deterministic lineage-inclusive identity, immutable version manifests, READY-last pointers, and shared compatibility fixtures; EOD publication and Java reading are present. Query Service sync, py-common format/lint/test/build, and graph review pass locally, but P3-I1 still lacks successful CI. | Keep P3-I1 blocked until successful CI replaces the failed PR/merge runs; do not promote P3-I2.                                     |
| Notifications       | [`NotificationService.send()`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/services/NotificationService.java) remains single-channel in the roadmap baseline.                                                                                                                                                                                                               | Event ownership is handled in P1-I4 before routing in Phase 8.                                                                      |
| Web app             | [`apps/omni-console`](../../apps/omni-console) and [`apps/query-service`](../../apps/query-service) are present on `main` as the focused V1 implementation paths.                                                                                                                                                                                                                                     | P6-I1 through P6-I3 remain verification-pending until dependencies, acceptance criteria, Nx checks, and CI evidence are reconciled. |
| Deployment          | Dockerfiles and Compose files exist.                                                                                                                                                                                                                                                                                                                                                                  | Harden existing assets instead of creating production deployment assumptions.                                                       |

## Capability Groups

| Group                                | Theme                                                        | Phases | Outcome                                                                                         |
| ------------------------------------ | ------------------------------------------------------------ | ------ | ----------------------------------------------------------------------------------------------- |
| A — Control-plane safety             | Correct and stabilize the inherited scheduler/control plane  | 0-1    | Correct due selection, safe claiming/outbox dispatch, and normalized execution semantics        |
| B — Deterministic contracts and data | Make service and dataset boundaries explicit and enforceable | 2-4    | Typed messages, versioned READY datasets with lineage, and dependency-safe dispatch             |
| C — Portable operations and product  | Turn the pipeline into a portable, operator-facing platform  | 5-8    | Reproducible deployment, Console/query workflows, safe job operations, and notification routing |
| D — Higher-frequency market data     | Extend the stable platform beyond daily processing           | 9-10   | Intraday EOD processing followed by realtime per-tick ingestion and reconciliation              |

Groups communicate intent and provide a shorter navigation model. The dependency map remains authoritative when phases inside or across groups can overlap.

## Phase dependency map

| Phase                          | Depends on                                                                                                             | Main outcome                                                                                      |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 0 — Immediate correctness      | None                                                                                                                   | Correct due-job selection and portable workspace paths.                                           |
| 1 — Backend/Core stabilization | Phase 0                                                                                                                | Safe scheduling model and normalized execution semantics.                                         |
| 2 — Proto3 contracts           | Phase 1                                                                                                                | Typed, versioned Java/Python message boundaries.                                                  |
| 3 — Dataset manifests          | Phase 1; may overlap late Phase 2 only at approved boundaries                                                          | READY state, versioning, schema identity, and lineage.                                            |
| 4 — Dependency guard           | Phases 1 and 3                                                                                                         | Runtime dependency enforcement before dispatch.                                                   |
| 5 — Portable deployment        | Phases 1-4 for production readiness                                                                                    | Reproducible images, shared storage, backup/restore.                                              |
| 6 — Omni Console               | Phases 2-5                                                                                                             | Server-side query, Dataset Explorer/Viewer, SQL Console, and a fixed code-owned Market Dashboard. |
| 7 — Console job operations     | Phase 1 scheduler safety; Phase 4 enforcement; completed Phase 6 Console and private identity boundary                 | Job-definition catalog, allow-list-only safe API trigger, and execution visibility.               |
| 8 — Notification routing       | P1-I4; explicit owner-approved exception removes the P2-I3 prerequisite while current internal contracts are preserved | Modern Telegram formats, signal presentation, routing, and reliable delivery.                     |
| 9 — Intraday EOD               | Phases 2, 3, and 5                                                                                                     | Post-close intraday bars, features, lineage, and sector aggregates.                               |
| 10 — Realtime per tick         | Phase 9                                                                                                                | Tick ingestion, live features, archive, and EOD reconciliation.                                   |

Phases 2 and 3 may overlap only after their boundary is agreed: Proto3 owns cross-service messages; JSON owns persisted dataset manifests.

## Canonical Files by Group

- **Inherited baseline:** [Pre-roadmap capability baseline](pre-roadmap-capability-baseline.md)
- **Group A — Control-plane safety:** [Phase 0 — Immediate correctness hotfixes](phase-0-immediate-correctness.md), [Phase 1 — Backend/Core stabilization](phase-1-backend-core-stabilization.md)
- **Group B — Deterministic contracts and data:** [Phase 2 — Cross-service Proto3 contracts](phase-2-proto3-contracts.md), [Phase 3 — Dataset manifests and version lineage](phase-3-dataset-manifests.md), [Phase 4 — Job dependency guard](phase-4-job-dependency-guard.md)
- **Group C — Portable operations and product:** [Phase 5 — Portable containers and centralized object storage](phase-5-portable-deployment.md), [Phase 6 — Omni Console and server-side query](phase-6-omni-console.md), [Phase 7 — Omni Console job operations](phase-7-console-job-operations.md), [Phase 8 — Multi-channel notification routing](phase-8-notification-routing.md)
- **Group D — Higher-frequency market data:** [Phase 9 — Intraday EOD](phase-9-intraday-eod.md), [Phase 10 — Realtime per tick](phase-10-realtime-per-tick.md)

### Execution and Governance

1. [Numbered architecture decision registry](../../docs/adr/README.md)
2. [Dependency-ordered implementation increments](implementation-increments.md)
3. [Automation rules](automation-rules.md)
4. [Cross-phase rules and definition of done](cross-phase-rules.md)
5. [Execution log](execution-log.md)
6. [Increment template](templates/increment.md)
7. [Daily report template](templates/daily-report.md)

## Current focused execution plan

The active MVP is the existing daily/EOD pipeline plus usable Telegram operational and signal notifications and basic Phase 7 operator controls. P1-I3, P8-I1, and P8-I2 may finish evidence reconciliation because they directly support that boundary. There is no additional feature increment selected after those gates.

The previous Query Service, Dataset Explorer, SQL Console, Dashboard, and metadata expansion plan is deferred with other post-MVP work in [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md). Existing source and historical evidence remain valid, but deferred increments are not eligible for automation.

## Supporting plan inventory

| Document                                                                                                                             | Classification                    | Canonical owner                                                                      |
| ------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------- | ------------------------------------------------------------------------------------ |
| [`docs/plans/001-backend-core-stabilization.md`](../../docs/plans/001-backend-core-stabilization.md)                                 | Supporting detail                 | Phase 1 increments in [`implementation-increments.md`](implementation-increments.md) |
| [`docs/plans/002-cross-service-protobuf-contracts.md`](../../docs/plans/002-cross-service-protobuf-contracts.md)                     | Supporting detail                 | Phase 2 increments                                                                   |
| [`docs/plans/003-dataset-metadata-manifest.md`](../../docs/plans/003-dataset-metadata-manifest.md)                                   | Supporting detail                 | Phase 3 increments                                                                   |
| [`docs/plans/004-job-dependency-guard.md`](../../docs/plans/004-job-dependency-guard.md)                                             | Supporting detail                 | Phase 4 increments                                                                   |
| [`docs/plans/005-portable-docker-deployment.md`](../../docs/plans/005-portable-docker-deployment.md)                                 | Supporting detail                 | Phase 5 increments                                                                   |
| [`docs/plans/006-internal-tools-parquet-viewer.md`](../../docs/plans/006-internal-tools-parquet-viewer.md)                           | Compatibility pointer             | Canonical execution is the focused Omni Console plan                                 |
| [`docs/plans/010-dataset-component-market-dashboard.md`](../../docs/plans/010-dataset-component-market-dashboard.md)                 | P6-I4 supporting detail           | Canonical fixed Market Dashboard scope is scheduled as P6-I4                         |
| [`docs/plans/007-telegram-multi-channel.md`](../../docs/plans/007-telegram-multi-channel.md)                                         | Supporting detail                 | Phase 8 routing increments                                                           |
| [`docs/plans/012-telegram-notification-format-modernization.md`](../../docs/plans/012-telegram-notification-format-modernization.md) | Scheduled/deferred Phase 8 detail | P8-I1 operational/generic formats, P8-I2 signal formats; P8-I3 is technical debt     |
| [`docs/plans/011-cross-service-observability-correlation.md`](../../docs/plans/011-cross-service-observability-correlation.md)       | Proposed cross-cutting plan       | Not roadmap-scheduled; assign dependencies/increment IDs before selection            |
| [`docs/plans/008-intraday-eod.md`](../../docs/plans/008-intraday-eod.md)                                                             | Supporting detail                 | Phase 9 increments                                                                   |
| [`docs/plans/009-realtime-per-tick.md`](../../docs/plans/009-realtime-per-tick.md)                                                   | Supporting detail                 | Phase 10 increments                                                                  |
| [`docs/plans/013-next-phase-implementation-plan.md`](../../docs/plans/013-next-phase-implementation-plan.md)                         | Superseded compatibility document | This roadmap; do not update status or schedule from it                               |
| [`docs/reference/001-algorithm-feature-catalog.md`](../../docs/reference/001-algorithm-feature-catalog.md)                           | Supporting reference              | Phase 9 and Phase 10 feature naming                                                  |

## Selection summary

P8-I1 and P8-I2 are the active notification evidence gates, and P1-I3 may finish reconciliation for the daily/EOD pipeline. P8-I3 and all unfinished Proto3 migration, advanced manifest/metadata, portable-deployment hardening, Console/query polish, intraday, and realtime increments are `superseded` for MVP scheduling and recorded in [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md). Automation must not select deferred work without a new owner decision. Completed work and existing safety controls remain unchanged.

Automation must not select approval-required or manual work until the owner resolves the recorded decision or access need.

## Codex execution entry point

Use [`automation-rules.md`](automation-rules.md) as the detailed operating protocol. Every run must produce a report matching [`templates/daily-report.md`](templates/daily-report.md), update increment metadata and [`execution-log.md`](execution-log.md) when evidence changes, and leave pull requests as drafts until acceptance criteria and CI pass.
