# Omni — Consolidated Implementation Roadmap

Status: Proposed execution plan

Application name: Omni Console

Primary repository: tanlocit9/omni

Last source cross-check: Current main branch supplied with this plan

## Objective

This roadmap moves Omni from a working single-node data pipeline toward a contract-driven, observable, portable platform with:

- safe job scheduling and deterministic execution ownership;
- typed Java/Python integration contracts;
- versioned datasets with explicit readiness and lineage;
- runtime dependency enforcement;
- portable deployment and centralized object storage;
- an internal operations application named Omni Console;
- extensible notification routing;
- incremental intraday processing followed by reconciled realtime ingestion.

The phases are intentionally ordered. Correctness and runtime metadata must exist before the console or more advanced orchestration depends on them.

## Current source baseline

| Area                | Current source state                                                                                                                                                                                                               | Roadmap implication                                                   |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Scheduler due query | [`JobDefinitionRepository.findJobsDue()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobDefinitionRepository.java) is missing parentheses around the `nextRun` condition.                      | Fix immediately; inactive jobs with `nextRun = NULL` can be selected. |
| Scheduler claiming  | [`JobScheduler`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/JobScheduler.java) reads and dispatches due jobs without an atomic claim.                                                                       | Complete before multi-instance deployment.                            |
| Dependency metadata | [`JobDefinitionConfig`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/constants/JobDefinitionConfig.java) seeds `dependsOnJobs`, `dependsOnDatasets`, and `producesDatasets` with documentation-only behavior. | Preserve now; enforce at runtime in Phase 4.                          |
| Sector execution    | Sector Transition is seeded as one job per focus sector while producing shared datasets.                                                                                                                                           | Consolidate into one logical writer per shared output.                |
| Contracts           | [`contracts`](../../contracts) does not exist; Python Kafka payloads remain generic JSON/map based.                                                                                                                                | Proto3 migration has not started.                                     |
| Dataset manifests   | Parquet storage exists in [`py_common`](../../libs/py-common/py_common), but there is no canonical READY manifest or `dataVersion`.                                                                                                | Manifest and lineage work has not started.                            |
| Notifications       | [`NotificationService.send()`](../../apps/core/src/main/java/com/omni/platform/modules/notifications/services/NotificationService.java) has no channel argument and Telegram has one chat ID.                                      | Tighten events first; add routing later.                              |
| Web app             | No React/internal-tool project exists.                                                                                                                                                                                             | Create Omni Console after metadata and access contracts are stable.   |
| Workspace paths     | Root workspaces use Windows-style entries such as `apps\\core`.                                                                                                                                                                    | Normalize before adding Nx projects.                                  |
| Deployment          | Dockerfiles and Compose files exist.                                                                                                                                                                                               | Harden existing assets instead of rebuilding from zero.               |

## Locked naming and scope

Use Omni Console as the long-term application name:

```text
apps/omni-console
```

The first capability is Dataset Explorer. Parquet inspection remains an implementation feature, not the product name:

```text
apps/omni-console/src/features/
├── dataset-explorer/
├── parquet-explorer/
├── dependency-monitor/
└── shared/
```

When console implementation begins, rename:

```text
docs/INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md
  -> docs/OMNI_CONSOLE_DATASET_EXPLORER_IMPLEMENTATION_PLAN.md
```

Do not use `parquet-viewer` as the application name. Avoid `data-workbench` because the planned scope includes jobs, dependency state, notifications, and operational tooling.

## Phase dependency map

| Phase                          | Depends on                                | Main outcome                                                        |
| ------------------------------ | ----------------------------------------- | ------------------------------------------------------------------- |
| 0 — Immediate correctness      | None                                      | Correct due-job selection and portable Nx workspace paths.          |
| 1 — Backend/Core stabilization | Phase 0                                   | Safe scheduling model and normalized execution semantics.           |
| 2 — Proto3 contracts           | Phase 1                                   | Typed, versioned Java/Python message boundaries.                    |
| 3 — Dataset manifests          | Phase 1; may overlap late Phase 2         | READY state, versioning, schema identity, and lineage.              |
| 4 — Dependency guard           | Phases 1 and 3                            | Runtime dependency enforcement before dispatch.                     |
| 5 — Portable deployment        | Phases 1-4 for production readiness       | Reproducible images, shared storage, backup/restore.                |
| 6 — Omni Console               | Phases 2-5                                | Dataset Explorer against canonical metadata and shared storage.     |
| 7 — Notification routing       | Phase 1 event cleanup; preferably Phase 2 | Multi-channel, recipient-aware notifications.                       |
| 8 — Intraday EOD               | Phases 2, 3, and 5                        | Post-close intraday bars, features, lineage, and sector aggregates. |
| 9 — Realtime per tick          | Phase 8                                   | Tick ingestion, live features, archive, and EOD reconciliation.     |

Phases 2 and 3 may overlap only after their boundary is agreed: Proto3 owns cross-service messages; JSON owns persisted dataset manifests.

## Phase files

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
11. [Implementation increments](implementation-increments.md)
12. [Cross-phase rules and definition of done](cross-phase-rules.md)

## Immediate next action

Start with Phase 0 as two narrow changes. After both are green, produce a short scheduler-claim ADR covering lock strategy, lease recovery, outbox boundary, and idempotency key before implementing Phase 1.1.
