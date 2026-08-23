# Omni — Consolidated Implementation Roadmap

Status: Canonical autonomous-delivery roadmap

Application name: Omni Console

Primary repository: tanlocit9/omni

Planning baseline: main

Default integration branch: main

Last source cross-check: `main@d858750cb1766ec567b328cdcb47656ded57a888`. Query Service, Omni Console, and manifest-handling source is present, but increment-specific acceptance and CI evidence is incomplete; source presence does not satisfy milestone completion.

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
Roadmap
└── Phase
    └── Increment
        └── Task
```

A phase is a coherent architectural capability. An increment is the smallest independently reviewable and verifiable delivery unit. A task is an implementation step inside an increment and is not independently scheduled.

## Current source baseline

| Area                | Current verified source state                                                                                                                                                                                                                                               | Roadmap implication                                                                                                                 |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Scheduler due query | [`JobDefinitionRepository.findJobsDue()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobDefinitionRepository.java) applies the active predicate to both due conditions with deterministic ordering, and repository coverage is present. | P0-I1 is completed with merged source and successful CI evidence.                                                                   |
| Scheduler claiming  | Claim fields, PostgreSQL `SKIP LOCKED` acquisition, atomic execution/outbox preparation, fenced release, stable publish retry identity, and Testcontainers concurrency coverage are implemented and CI-verified in PR #8.                                                   | P1-I2 is completed; its autonomous direct dependents are ready.                                                                     |
| Dependency metadata | [`JobDefinitionConfig`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/constants/JobDefinitionConfig.java) seeds dependency metadata, but it remains documentation-only.                                                                                 | Preserve metadata until Phase 4 guard enforcement.                                                                                  |
| Sector execution    | Sector Transition still requires one logical writer for shared outputs.                                                                                                                                                                                                     | P1-I3 remains pending before manifest-dependent sector publication work.                                                            |
| Contracts           | The [`contracts`](../../libs/contracts) Nx project owns versioned common/job Proto3 schemas under `libs/contracts/proto`, pinned local generation, ignored disposable Java/Python output, and CI checks; production wire formats remain unchanged.                          | P2-I1 is completed; P2-I2 remains pending until P1-I4 is completed.                                                                 |
| Dataset manifests   | [`py_common`](../../libs/py-common/py_common) implements the canonical JSON manifest contract, deterministic lineage-inclusive identity, immutable version manifests, READY-last pointers, and shared compatibility fixtures; EOD publication and Java reading are present. | Audit and freshly verify P3-I1/P3-I2, then repair exact Analyzer lineage in P3-I3 before Console reliance.                          |
| Notifications       | [`NotificationService.send()`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/services/NotificationService.java) remains single-channel in the roadmap baseline.                                                                                     | Event ownership is handled in P1-I4 before routing in Phase 8.                                                                      |
| Web app             | [`apps/omni-console`](../../apps/omni-console) and [`apps/query-service`](../../apps/query-service) are present on `main` as the focused V1 implementation paths.                                                                                                           | P6-I1 through P6-I3 remain verification-pending until dependencies, acceptance criteria, Nx checks, and CI evidence are reconciled. |
| Deployment          | Dockerfiles and Compose files exist.                                                                                                                                                                                                                                        | Harden existing assets instead of creating production deployment assumptions.                                                       |

## Phase dependency map

| Phase                          | Depends on                                                                                             | Main outcome                                                                                |
| ------------------------------ | ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| 0 — Immediate correctness      | None                                                                                                   | Correct due-job selection and portable workspace paths.                                     |
| 1 — Backend/Core stabilization | Phase 0                                                                                                | Safe scheduling model and normalized execution semantics.                                   |
| 2 — Proto3 contracts           | Phase 1                                                                                                | Typed, versioned Java/Python message boundaries.                                            |
| 3 — Dataset manifests          | Phase 1; may overlap late Phase 2 only at approved boundaries                                          | READY state, versioning, schema identity, and lineage.                                      |
| 4 — Dependency guard           | Phases 1 and 3                                                                                         | Runtime dependency enforcement before dispatch.                                             |
| 5 — Portable deployment        | Phases 1-4 for production readiness                                                                    | Reproducible images, shared storage, backup/restore.                                        |
| 6 — Omni Console               | Phases 2-5                                                                                             | Server-side query, Dataset Explorer/Viewer, SQL Console, and Saved Query-backed dashboards. |
| 7 — Console job operations     | Phase 1 scheduler safety; Phase 4 enforcement; completed Phase 6 Console and private identity boundary | Job-definition catalog, allow-list-only safe API trigger, and execution visibility.         |
| 8 — Notification routing       | Phase 1 event cleanup; preferably Phase 2                                                              | Multi-channel, recipient-aware notifications.                                               |
| 9 — Intraday EOD               | Phases 2, 3, and 5                                                                                     | Post-close intraday bars, features, lineage, and sector aggregates.                         |
| 10 — Realtime per tick         | Phase 9                                                                                                | Tick ingestion, live features, archive, and EOD reconciliation.                             |

Phases 2 and 3 may overlap only after their boundary is agreed: Proto3 owns cross-service messages; JSON owns persisted dataset manifests.

## Canonical files

1. [Phase 0 — Immediate correctness hotfixes](phase-0-immediate-correctness.md)
2. [Phase 1 — Backend/Core stabilization](phase-1-backend-core-stabilization.md)
3. [Phase 2 — Cross-service Proto3 contracts](phase-2-proto3-contracts.md)
4. [Phase 3 — Dataset manifests and version lineage](phase-3-dataset-manifests.md)
5. [Phase 4 — Job dependency guard](phase-4-job-dependency-guard.md)
6. [Phase 5 — Portable containers and centralized object storage](phase-5-portable-deployment.md)
7. [Phase 6 — Omni Console and server-side query](phase-6-omni-console.md)
8. [Phase 7 — Omni Console job operations](phase-7-console-job-operations.md)
9. [Phase 8 — Multi-channel notification routing](phase-8-notification-routing.md)
10. [Phase 9 — Intraday EOD](phase-9-intraday-eod.md)
11. [Phase 10 — Realtime per tick](phase-10-realtime-per-tick.md)
12. [Dependency-ordered implementation increments](implementation-increments.md)
13. [Automation rules](automation-rules.md)
14. [Cross-phase rules and definition of done](cross-phase-rules.md)
15. [Execution log](execution-log.md)
16. [Increment template](templates/increment.md)
17. [Daily report template](templates/daily-report.md)

## Current focused execution plan

[`plans/omni-metadata-console-dashboard-execution-plan.md`](../omni-metadata-console-dashboard-execution-plan.md) is the canonical gated sequence for Query Service, Omni Console Dataset Explorer/Viewer, SQL Console, Saved Query-backed Dashboard, and Force Precompute date semantics. It does not reorder unrelated roadmap phases globally.

## Supporting plan inventory

| Document                                                                                                                             | Classification             | Canonical owner                                                                      |
| ------------------------------------------------------------------------------------------------------------------------------------ | -------------------------- | ------------------------------------------------------------------------------------ |
| [`docs/BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md`](../../docs/BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md)             | Supporting detail          | Phase 1 increments in [`implementation-increments.md`](implementation-increments.md) |
| [`docs/CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md`](../../docs/CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md) | Supporting detail          | Phase 2 increments                                                                   |
| [`docs/DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`](../../docs/DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md)               | Supporting detail          | Phase 3 increments                                                                   |
| [`docs/JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md`](../../docs/JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md)                         | Supporting detail          | Phase 4 increments                                                                   |
| [`docs/PORTABLE_DOCKER_DEPLOYMENT_IMPLEMENTATION_PLAN.md`](../../docs/PORTABLE_DOCKER_DEPLOYMENT_IMPLEMENTATION_PLAN.md)             | Supporting detail          | Phase 5 increments                                                                   |
| [`docs/INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`](../../docs/INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md)       | Compatibility pointer      | Canonical execution is the focused Omni Console plan                                 |
| [`docs/TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md`](../../docs/TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md)                     | Supporting detail          | Phase 8 increments                                                                   |
| [`docs/INTRADAY_EOD_IMPLEMENTATION_PLAN.md`](../../docs/INTRADAY_EOD_IMPLEMENTATION_PLAN.md)                                         | Supporting detail          | Phase 9 increments                                                                   |
| [`docs/REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md`](../../docs/REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md)                               | Supporting detail          | Phase 10 increments                                                                  |
| [`docs/NEXT_PHASE_IMPLEMENTATION_PLAN.md`](../../docs/NEXT_PHASE_IMPLEMENTATION_PLAN.md)                                             | Duplicate/overlapping plan | Reconcile into this roadmap; do not schedule independently                           |
| [`docs/ALGORITHM_FEATURE_CATALOG.md`](../../docs/ALGORITHM_FEATURE_CATALOG.md)                                                       | Supporting reference       | Phase 9 and Phase 10 feature naming                                                  |

## Selection summary

P3-I1 is the highest-priority unfinished correctness increment and is `blocked`. Complete local acceptance checks passed at verified branch head `a24edd2` on 2026-08-23, but GitHub actions are prohibited for this run, leaving its required increment-specific PR and CI evidence unavailable; current code-review-graph change/impact evidence is also unavailable. Automation must stop rather than bypass P3-I1 or promote its dependents. For the focused Metadata → Console → Dashboard plan, M0 remains the next gate: it must reconcile merged Query Service/Console source and Phase 6 dependency exceptions before any Phase 6 increment can complete. P6-I1 through P6-I3 are `verification_pending`, not eligible new work. Phase 7 remains pending until P4-I2, P6-I3, and P6-I4 are completed. Its approved operator policy permits manual triggers only for explicitly allow-listed active definitions, preserves dependency and concurrency enforcement, and excludes force, bypass, and cancellation.

Automation must not select approval-required or manual work until the owner resolves the recorded decision or access need.

## Codex execution entry point

Use [`automation-rules.md`](automation-rules.md) as the detailed operating protocol. Every run must produce a report matching [`templates/daily-report.md`](templates/daily-report.md), update increment metadata and [`execution-log.md`](execution-log.md) when evidence changes, and leave pull requests as drafts until acceptance criteria and CI pass.
