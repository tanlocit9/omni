# Omni — Consolidated Implementation Roadmap

Status: Canonical autonomous-delivery roadmap

Application name: Omni Console

Primary repository: tanlocit9/omni

Planning baseline: main

Default integration branch: main

Last source cross-check: main at 8efc965b2084a16af9c733a9631e4e4729c23be4; CI succeeded at https://github.com/tanlocit9/omni/actions/runs/31606526578.

## Objective

This roadmap moves Omni from a working single-node data pipeline toward a contract-driven, observable, portable platform with safe scheduling, typed integration contracts, versioned datasets, dependency enforcement, portable deployment, Omni Console, notification routing, intraday processing, and realtime ingestion.

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
Roadmap
└── Phase
    └── Increment
        └── Task
```

A phase is a coherent architectural capability. An increment is the smallest independently reviewable and verifiable delivery unit. A task is an implementation step inside an increment and is not independently scheduled.

## Current source baseline

| Area                | Current verified source state                                                                                                                                                                                                                                               | Roadmap implication                                                                                  |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Scheduler due query | [`JobDefinitionRepository.findJobsDue()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobDefinitionRepository.java) applies the active predicate to both due conditions with deterministic ordering, and repository coverage is present. | P0-I1 is completed with merged source and successful CI evidence.                                    |
| Scheduler claiming  | Claim fields, PostgreSQL `SKIP LOCKED` acquisition, atomic execution/outbox preparation, fenced release, stable publish retry identity, and Testcontainers concurrency coverage are implemented and CI-verified in PR #8.                                                   | P1-I2 is completed; its autonomous direct dependents are ready.                                      |
| Dependency metadata | [`JobDefinitionConfig`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/constants/JobDefinitionConfig.java) seeds dependency metadata, but it remains documentation-only.                                                                                 | Preserve metadata until Phase 4 guard enforcement.                                                   |
| Sector execution    | Sector Transition still requires one logical writer for shared outputs.                                                                                                                                                                                                     | P1-I3 remains pending before manifest-dependent sector publication work.                             |
| Contracts           | No canonical [`contracts`](../../contracts) project exists in the current tree listing.                                                                                                                                                                                     | Proto3 migration starts at P2-I1 after scheduler integration.                                        |
| Dataset manifests   | [`py_common`](../../libs/py-common/py_common) has Parquet/storage abstractions, but no canonical READY manifest or lineage contract is complete.                                                                                                                            | Manifest work starts at P3-I1.                                                                       |
| Notifications       | [`NotificationService.send()`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/services/NotificationService.java) remains single-channel in the roadmap baseline.                                                                                     | Event ownership is handled in P1-I4 before routing in Phase 7.                                       |
| Web app             | No [`apps/omni-console`](../../apps/omni-console) project exists in the current tree listing.                                                                                                                                                                               | Console work remains downstream of metadata, contracts, dependency guard, and deployment boundaries. |
| Deployment          | Dockerfiles and Compose files exist.                                                                                                                                                                                                                                        | Harden existing assets instead of creating production deployment assumptions.                        |

## Phase dependency map

| Phase                          | Depends on                                                    | Main outcome                                                        |
| ------------------------------ | ------------------------------------------------------------- | ------------------------------------------------------------------- |
| 0 — Immediate correctness      | None                                                          | Correct due-job selection and portable workspace paths.             |
| 1 — Backend/Core stabilization | Phase 0                                                       | Safe scheduling model and normalized execution semantics.           |
| 2 — Proto3 contracts           | Phase 1                                                       | Typed, versioned Java/Python message boundaries.                    |
| 3 — Dataset manifests          | Phase 1; may overlap late Phase 2 only at approved boundaries | READY state, versioning, schema identity, and lineage.              |
| 4 — Dependency guard           | Phases 1 and 3                                                | Runtime dependency enforcement before dispatch.                     |
| 5 — Portable deployment        | Phases 1-4 for production readiness                           | Reproducible images, shared storage, backup/restore.                |
| 6 — Omni Console               | Phases 2-5                                                    | Dataset Explorer against canonical metadata and shared storage.     |
| 7 — Notification routing       | Phase 1 event cleanup; preferably Phase 2                     | Multi-channel, recipient-aware notifications.                       |
| 8 — Intraday EOD               | Phases 2, 3, and 5                                            | Post-close intraday bars, features, lineage, and sector aggregates. |
| 9 — Realtime per tick          | Phase 8                                                       | Tick ingestion, live features, archive, and EOD reconciliation.     |

Phases 2 and 3 may overlap only after their boundary is agreed: Proto3 owns cross-service messages; JSON owns persisted dataset manifests.

## Canonical files

1. [Phase 0 — Immediate correctness hotfixes](phase-0-immediate-correctness.md)
2. [Phase 1 — Backend/Core stabilization](phase-1-backend-core-stabilization.md)
3. [Phase 2 — Cross-service Proto3 contracts](phase-2-proto3-contracts.md)
4. [Phase 3 — Dataset manifests and version lineage](phase-3-dataset-manifests.md)
5. [Phase 4 — Job dependency guard](phase-4-job-dependency-guard.md)
6. [Phase 5 — Portable containers and centralized object storage](phase-5-portable-deployment.md)
7. [Phase 6 — Omni Console: Dataset Explorer first](phase-6-omni-console.md)
8. [Phase 7 — Multi-channel notification routing](phase-7-notification-routing.md)
9. [Phase 8 — Intraday EOD](phase-8-intraday-eod.md)
10. [Phase 9 — Realtime per tick](phase-9-realtime-per-tick.md)
11. [Dependency-ordered implementation increments](implementation-increments.md)
12. [Automation rules](automation-rules.md)
13. [Cross-phase rules and definition of done](cross-phase-rules.md)
14. [Execution log](execution-log.md)
15. [Increment template](templates/increment.md)
16. [Daily report template](templates/daily-report.md)

## Supporting plan inventory

| Document                                                                                                                             | Classification                     | Canonical owner                                                                      |
| ------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------- | ------------------------------------------------------------------------------------ |
| [`docs/BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md`](../../docs/BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md)             | Supporting detail                  | Phase 1 increments in [`implementation-increments.md`](implementation-increments.md) |
| [`docs/CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md`](../../docs/CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md) | Supporting detail                  | Phase 2 increments                                                                   |
| [`docs/DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`](../../docs/DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md)               | Supporting detail                  | Phase 3 increments                                                                   |
| [`docs/JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md`](../../docs/JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md)                         | Supporting detail                  | Phase 4 increments                                                                   |
| [`docs/PORTABLE_DOCKER_DEPLOYMENT_IMPLEMENTATION_PLAN.md`](../../docs/PORTABLE_DOCKER_DEPLOYMENT_IMPLEMENTATION_PLAN.md)             | Supporting detail                  | Phase 5 increments                                                                   |
| [`docs/INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`](../../docs/INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md)       | Superseded name, supporting detail | Phase 6; rename when P6-I2 starts                                                    |
| [`docs/TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md`](../../docs/TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md)                     | Supporting detail                  | Phase 7 increments                                                                   |
| [`docs/INTRADAY_EOD_IMPLEMENTATION_PLAN.md`](../../docs/INTRADAY_EOD_IMPLEMENTATION_PLAN.md)                                         | Supporting detail                  | Phase 8 increments                                                                   |
| [`docs/REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md`](../../docs/REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md)                               | Supporting detail                  | Phase 9 increments                                                                   |
| [`docs/NEXT_PHASE_IMPLEMENTATION_PLAN.md`](../../docs/NEXT_PHASE_IMPLEMENTATION_PLAN.md)                                             | Duplicate/overlapping plan         | Reconcile into this roadmap; do not schedule independently                           |
| [`docs/ALGORITHM_FEATURE_CATALOG.md`](../../docs/ALGORITHM_FEATURE_CATALOG.md)                                                       | Supporting reference               | Phase 8 and Phase 9 feature naming                                                   |

## Selection summary

The next increment daily automation should select is P2-I1 from [`implementation-increments.md`](implementation-increments.md). P1-I2 has completed implementation and successful CI evidence in draft PR #8; P2-I1 wins the critical-priority tie with P3-I1 by the lowest phase/increment rule.

Automation must not select approval-required or manual work until the owner resolves the recorded decision or access need.

## Codex execution entry point

Use [`automation-rules.md`](automation-rules.md) as the detailed operating protocol. Every run must produce a report matching [`templates/daily-report.md`](templates/daily-report.md), update increment metadata and [`execution-log.md`](execution-log.md) when evidence changes, and leave pull requests as drafts until acceptance criteria and CI pass.
