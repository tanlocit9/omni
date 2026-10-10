# Omni — Consolidated Implementation Roadmap

Status: Canonical autonomous-delivery roadmap

Application name: Omni Console

Primary repository: tanlocit9/omni

Planning baseline: main

Default integration branch: main

Current increment status and dependencies are canonical in [`implementation-increments.md`](implementation-increments.md). Durable historical evidence and reconciliation decisions are recorded in the root [`ReleaseNotes.md`](../../../ReleaseNotes.md).

## Objective

This roadmap moves Omni from a working single-node data pipeline toward a contract-driven, observable, portable platform with safe scheduling, typed integration contracts, versioned datasets, dependency enforcement, portable deployment, Omni Console, safe operator job controls, notification routing, intraday processing, realtime ingestion, cross-service correlation with structured logging, and an operator-trust landing experience that shows truthful pipeline stages, EOD data health, and a small fixed market review.

Roadmap delivery starts from canonical status and dependencies, implements one bounded increment, reconciles its cross-service blast radius, proves attributable test coverage, records approved verification evidence, and updates the roadmap without overstating source, CI, deployment, provider, or production state. Pull requests, commits, and CI remain traceability unless an increment declares them as explicit delivery gates.

## Canonical hierarchy

```text
Pre-roadmap capability baseline (historical; not scheduled)
└── Roadmap
    └── Milestone
        └── Epic / numbered plan
            └── Story / canonical increment
                └── Task
```

A milestone groups outcomes that should be delivered in one product/architecture horizon. A numbered implementation plan is an epic and may contain several independently deliverable stories. A story is registered as a canonical increment and is the smallest independently scheduled, reviewable, and verifiable delivery unit. A task is an implementation step inside one story and is not independently scheduled. Historical phase and increment IDs remain stable mappings; introducing milestones and epics does not renumber P0–P13, alter evidence, or promote status.

## Epic and Story Status Catalog

This table makes the roadmap hierarchy visible in one place. Status values are copied from the canonical [increment registry](implementation-increments.md); that registry remains authoritative for exact titles, dependencies, ownership, execution mode, evidence, and selection eligibility.

| Milestone / group                       | Epic                                              | Supporting plan                                                                                    | Stories by current canonical status                                                | Epic scheduling summary                                                                               |
| --------------------------------------- | ------------------------------------------------- | -------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Historical foundation                   | P0 — Immediate correctness                        | [Plan 001](../001-backend-core-stabilization.md)                                                   | `completed`: P0-I1, P0-I2                                                          | Completed historical foundation.                                                                      |
| Group A — Control-plane safety          | P1 — Core safety and execution identity           | [Plan 001](../001-backend-core-stabilization.md)                                                   | `completed`: P1-I0, P1-I1, P1-I2, P1-I4; `verification_pending`: P1-I3             | Active evidence remains for P1-I3 only.                                                               |
| Group B — Contracts and data            | P2 — Cross-service Proto3 contracts               | [Plan 002](../002-cross-service-protobuf-contracts.md)                                             | `completed`: P2-I1; `superseded`: P2-I2, P2-I3                                     | Foundation retained; superseded migration is not selectable.                                          |
| Group B — Contracts and data            | P3 — Dataset manifests and date contracts         | [Plan 003](../003-dataset-metadata-manifest.md)                                                    | `completed`: P3-I4; `superseded`: P3-I1, P3-I2, P3-I3, P3-I5                       | Implemented source/evidence is retained without reopening superseded stories.                         |
| Milestone 1 — Safe scheduler operations | P4 — Job dependency guard                         | [Plan 005](../005-job-dependency-guard.md), [Plan 023](../023-dependency-aware-outbox-dispatch.md) | `completed`: P4-I1, P4-I2; `superseded`: P4-I3                                     | P14-I3 replaces the old dispatcher delivery; TD-014 retains unclosed evidence.                        |
| Group C — Portable operations           | P5 — Portable deployment                          | [Plan 007](../007-portable-docker-deployment.md)                                                   | `superseded`: P5-I1, P5-I2, P5-I3                                                  | Deferred/superseded; owner decision required to reactivate replacement scope.                         |
| Group C — Product foundation            | P6 — Console and query expansion                  | [Plan 008](../008-omni-metadata-console-dashboard-execution-plan.md)                               | `superseded`: P6-I1, P6-I2, P6-I3, P6-I4                                           | Existing source may be reused; former schedule is not active.                                         |
| Group C — Operator controls             | P7 — Job operations                               | [Plan 008](../008-omni-metadata-console-dashboard-execution-plan.md)                               | `completed`: P7-I1, P7-I2, P7-I3                                                   | Completed basic job catalog, trigger, and visibility capability.                                      |
| Active MVP                              | P8 — Notification routing and signal presentation | [Plan 010](../010-telegram-multi-channel.md), [Plan 022](../022-notification-outbox.md)            | `verification_pending`: P8-I1, P8-I2, P8-I5; `pending`: P8-I4; `superseded`: P8-I3 | P8-I4 source/evidence retained but paused to release apps/core ownership for P14.                     |
| Active MVP                              | P9 — Intraday EOD and confirmation                | [Plan 013](../013-intraday-eod.md), [Plan 021](../021-intraday-confirmed-rules.md)                 | `verification_pending`: P9-I1; `pending`: P9-I4; `superseded`: P9-I2, P9-I3, P9-I5 | P9-I4 source/evidence retained but paused until P9-I1/P8-I4 and post-P14 ownership permit resumption. |
| Deferred owner-gated                    | P10 — Realtime foundation/runtime                 | [Plan 014](../014-realtime-per-tick.md)                                                            | `verification_pending`: P10-I1, P10-I2; `blocked`: P10-I0, P10-I3                  | Evidence retained; provider/runtime activation remains owner-gated.                                   |
| Deferred owner-gated                    | P11 — Cross-service observability                 | [Plan 024](../024-polyglot-correlation-structured-logging.md)                                      | `pending`: P11-I1, P11-I2, P11-I3, P11-I4, P11-I5                                  | Deferred under TD-011; not autonomously selectable.                                                   |
| Deferred owner-gated                    | P12 — Worker throughput and writer batching       | [Plan 027](../027-concurrent-workers-and-writer-batching.md)                                       | `pending`: P12-I1, P12-I2, P12-I3, P12-I4                                          | Requires owner reactivation and the P13-I1 measurement gate.                                          |
| Milestone 1 — Operator trust            | P13 — Operator Trust Console                      | [Plan 029](../029-operator-trust-console.md)                                                       | `pending`: P13-I1, P13-I2, P13-I3, P13-I4                                          | Basic operations/shell remain active; P13-I3 retains its owner-decision gate.                         |
| Milestone 1 — Scheduler topology        | P14 — Static Graph & DispatchPlanner              | [Plan 030](../030-static-graph-dispatch-planner.md)                                                | `completed`: P14-I1; `ready`: P14-I2; `pending`: P14-I3                            | P14-I1 passed its approved local gate and owner attestation; P14-I2 is the first eligible increment.  |

Rules for reading the catalog:

- A mixed-status epic does not have one synthetic completion status; each story retains its own canonical status.
- `pending` does not imply `ready`. Dependencies, execution mode, owner decisions, and module conflicts still apply.
- `verification_pending` means source exists but required impact, coverage, checks, or runtime evidence remains incomplete.
- `superseded`, `blocked`, approval-required, manual, and deferred work is not autonomously selectable.
- Tasks inside a story are review units only and do not receive independent roadmap status.

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

| Area                | Current verified source state                                                                                                                                                                                                                                                                                                                                                      | Roadmap implication                                                                                                             |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Scheduler due query | [`JobDefinitionRepository.findJobsDue()`](../../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobDefinitionRepository.java) applies the active predicate to both due conditions with deterministic ordering, and repository coverage is present.                                                                                                     | P0-I1 is completed with merged source and successful CI evidence.                                                               |
| Scheduler claiming  | Claim fields, PostgreSQL `SKIP LOCKED` acquisition, atomic execution/outbox preparation, fenced release, stable publish retry identity, and Testcontainers concurrency coverage are implemented and CI-verified in PR #8.                                                                                                                                                          | P1-I2 is completed; its autonomous direct dependents are ready.                                                                 |
| Dependency metadata | [`JobDefinitionConfig`](../../../apps/core/src/main/java/com/omni/platform/modules/scheduler/constants/JobDefinitionConfig.java) seeds dependency metadata; P4-I1/P4-I2 enforcement and superseded P4-I3 dispatch-time gating source are present.                                                                                                                                  | Reuse the source baseline; P14-I3 owns final integrated impact, coverage, migration, fencing, and runtime evidence.             |
| Sector execution    | P1-I3 seeds one canonical-universe analysis writer and one outcome writer for shared Sector Transition outputs; Platform/Analyzer checks, graph review, commit, and exact-head CI pass on draft PR #16, but the PR is owned by P3-I4 rather than increment-specific.                                                                                                               | P1-I3 remains `verification_pending` and does not yet unblock manifest-dependent sector publication work.                       |
| Contracts           | The [`contracts`](../../../libs/contracts) Nx project owns versioned common/job Proto3 schemas under `libs/contracts/proto`. P1-I4 completed the active JSON execution/status hard cutover to required `workType`/`workKey`; P2-I2/P2-I3 were later superseded by the MVP audit.                                                                                                   | Preserve the completed hard-cutover evidence; do not reactivate the superseded Proto3 migration without a new owner decision.   |
| Dataset manifests   | [`py_common`](../../../libs/py-common/py_common) implements canonical JSON manifests, deterministic lineage-inclusive identity, immutable version manifests, READY-last pointers, and shared compatibility fixtures. The unfinished P3-I1/P3-I2/P3-I3 sequence is superseded; completed P3-I4 evidence remains intact.                                                             | Treat the implemented manifest foundation as current source capability while keeping superseded roadmap work in technical debt. |
| Notifications       | Typed Telegram rendering, signal presentation, bounded strategy filtering, and the separate durable notification outbox source are present. P8-I1, P8-I2, and P8-I5 have fresh local Platform test/build evidence but still lack complete increment-specific impact/coverage and applicable runtime evidence; PR/CI are traceability unless an increment explicitly requires them. | Follow the active Phase 8 evidence order; source presence and local checks do not make these increments completed.              |
| Web app             | [`apps/omni-console`](../../../apps/omni-console) and [`apps/query-service`](../../../apps/query-service) contain the focused V1 source, including P8-I4 strategy-aware exact-symbol signal history. P6-I1 through P6-I4 are superseded as scheduling items.                                                                                                                       | Preserve existing source; do not reactivate deferred Console/query expansion without owner approval.                            |
| Deployment          | Dockerfiles and Compose files exist.                                                                                                                                                                                                                                                                                                                                               | Harden existing assets instead of creating production deployment assumptions.                                                   |

## Roadmap at a glance

See the standalone [roadmap diagram](roadmap.md) for the Mermaid view of roadmap phases and selected cross-phase relationships. The diagram is navigational; increment-level status, dependencies, ownership, and execution eligibility remain canonical in [`implementation-increments.md`](implementation-increments.md).

The separate [technical-debt priority diagram](../../technical-debt/priority-order.md) visualizes debt priority and activation relationships. In both diagrams, color describes evidence state rather than urgency; P0–P3 labels in the debt diagram describe risk priority.

| Active execution order                                                                                 | Status summary                                                                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P14-I2 → P14-I3 → P13-I1 → P13-I2 → P13-I4; then P8-I1 → P8-I2 → P8-I4 → P8-I5 → P9-I1 → P9-I4 → P1-I3 | P14-I1 completed its approved local Platform gate and owner attestation on 2026-10-11. P14-I2 is ready; P8-I4/P9-I4 remain paused. P14-I3 absorbs superseded P4-I3 safety/evidence via TD-014.                                            |
| P11 logging rollout                                                                                    | Deferred to TD-011; owner reactivation required.                                                                                                                                                                                          |
| P13-I1 → P13-I2 → P13-I4; P13-I3 separately                                                            | Owner-approved operator-trust scope; P13-I1 requires completed P14-I3/P7-I3, while P13-I3 uses Query Service memory cache; calendar evidence or explicit classification narrowing remains an owner decision and does not block the shell. |
| P12 throughput and writer expansion                                                                    | Deferred to TD-009/TD-011; baseline and owner promotion required.                                                                                                                                                                         |

Proto3 owns cross-service messages; JSON owns persisted dataset manifests. Superseded, blocked, deferred, and approval-required work is not autonomously selectable.

## Canonical Files by Group

- **Inherited baseline:** [Pre-roadmap capability baseline](pre-roadmap-capability-baseline.md)
- **Group A — Control-plane safety:** [Phase 0 — Immediate correctness hotfixes](../001-backend-core-stabilization.md), [Phase 1 — Backend/Core stabilization](../001-backend-core-stabilization.md)
- **Group B — Deterministic contracts and data:** [Phase 2 — Cross-service Proto3 contracts](../002-cross-service-protobuf-contracts.md), [Phase 3 — Dataset manifests and version lineage](../003-dataset-metadata-manifest.md), [Phase 4 — Job dependency guard](../005-job-dependency-guard.md)
- **Group C — Portable operations and product:** [Phase 5 — Portable containers and centralized object storage](../007-portable-docker-deployment.md), [Phase 6 — Omni Console and server-side query](../008-omni-metadata-console-dashboard-execution-plan.md), [Phase 7 — Omni Console job operations](../008-omni-metadata-console-dashboard-execution-plan.md), [Phase 8 — Multi-channel notification routing](../010-telegram-multi-channel.md)
- **Group D — Higher-frequency market data:** [Phase 9 — Intraday EOD](../013-intraday-eod.md), [Phase 10 — Realtime per tick](../014-realtime-per-tick.md)
- **Group E — Cross-service observability:** [Phase 11 — Cross-service observability](../024-polyglot-correlation-structured-logging.md)
- **Group F — Worker throughput:** [Phase 12 — Python worker throughput and dataset writer](../027-concurrent-workers-and-writer-batching.md)
- **Group G — Operator trust:** [Phase 13 — Job Operations, EOD Data Health, and fixed Console order](../029-operator-trust-console.md)
- **Milestone 1 scheduler topology epic:** [Phase 14 — Static Graph & DispatchPlanner](../030-static-graph-dispatch-planner.md)

### Execution and Governance

1. [Numbered architecture decision registry](../../adr/README.md)
2. [Dependency-ordered implementation increments](implementation-increments.md)
3. [Automation rules](automation-rules.md)
4. [Cross-phase rules and definition of done](cross-phase-rules.md)
5. [Root release notes and historical evidence](../../../ReleaseNotes.md)
6. [Increment template](templates/increment.md)
7. [Daily report template](templates/daily-report.md)

## Current focused execution plan

The active MVP remains the existing daily/EOD pipeline, usable Telegram operational and signal notifications, and basic Phase 7 operator controls. Follow the compact execution-order table above and the exact exit conditions in [`implementation-increments.md`](implementation-increments.md). The owner additionally approved bounded Phase 13 operator-trust scope on 2026-10-06 and the Plan 030 Static Graph & DispatchPlanner epic on 2026-10-08. P14-I1/P14-I2 provide shared topology and pure planning before P14-I3 touches dispatch; basic Phase 13 operations do not wait for the graph, while graph-specific presentation does.

P9-I5 provider health, arbitrary SQL expansion, broad Dataset Explorer polish, customizable dashboards, automatic Data Health scanning/repair, capacity changes, and multi-provider expansion remain deferred in [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../technical-debt/004-post-mvp-roadmap-work.md). Phase 13 promotes only truthful Job Operations, manual read-only EOD Data Health, and a small fixed Market Review. Existing source and historical evidence remain valid, but other deferred increments are not eligible for automation.

## Supporting plan inventory

| Document                                                                                                                       | Classification                          | Canonical owner                                                                                                  |
| ------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| [`docs/plans/001-backend-core-stabilization.md`](../001-backend-core-stabilization.md)                                         | Supporting detail                       | Phase 1 increments in [`implementation-increments.md`](implementation-increments.md)                             |
| [`docs/plans/002-cross-service-protobuf-contracts.md`](../002-cross-service-protobuf-contracts.md)                             | Supporting detail                       | Phase 2 increments                                                                                               |
| [`docs/plans/003-dataset-metadata-manifest.md`](../003-dataset-metadata-manifest.md)                                           | Consolidated supporting detail          | Phase 3 increments                                                                                               |
| [`docs/plans/004-parquet-date-normalization-increment.md`](../004-parquet-date-normalization-increment.md)                     | Increment record                        | P3-I4 date normalization evidence and scope                                                                      |
| [`docs/plans/005-job-dependency-guard.md`](../005-job-dependency-guard.md)                                                     | Consolidated supporting detail          | Phase 4 increments                                                                                               |
| [`docs/plans/006-job-dependency-guard-progress.md`](../006-job-dependency-guard-progress.md)                                   | Historical progress record              | Phase 4 implementation history                                                                                   |
| [`docs/plans/007-portable-docker-deployment.md`](../007-portable-docker-deployment.md)                                         | Deferred technical debt                 | Phase 5 increments                                                                                               |
| [`docs/plans/008-omni-metadata-console-dashboard-execution-plan.md`](../008-omni-metadata-console-dashboard-execution-plan.md) | Deferred technical-debt record          | Phase 6 metadata, explorer, viewer, and dashboard sequence                                                       |
| [`docs/plans/009-dataset-component-market-dashboard.md`](../009-dataset-component-market-dashboard.md)                         | Deferred technical debt                 | P6-I4 superseded; bounded Market Review is P13 scope                                                             |
| [`docs/plans/010-telegram-multi-channel.md`](../010-telegram-multi-channel.md)                                                 | Supporting detail                       | Phase 8 routing increments                                                                                       |
| [`docs/plans/011-telegram-notification-format-modernization.md`](../011-telegram-notification-format-modernization.md)         | Scheduled Phase 8 detail                | P8-I1/P8-I2 formats; P8-I3 historical; P8-I5 durable delivery is detailed by plan 022                            |
| [`docs/plans/012-confirmed-trend-equals-mvp.md`](../012-confirmed-trend-equals-mvp.md)                                         | Scheduled P8-I4 MVP detail              | Equal-vote combined signal, symbol query, and Telegram strategy selection                                        |
| [`docs/plans/013-intraday-eod.md`](../013-intraday-eod.md)                                                                     | Active bounded P9-I1 detail             | VCI normalized trades for HOSE/HNX/UPCOM; later bars/sector increments deferred                                  |
| [`docs/plans/021-intraday-confirmed-rules.md`](../021-intraday-confirmed-rules.md)                                             | Active bounded P9-I4 detail             | Exact-date intraday confirmation/suppression for the existing confirmed daily signal                             |
| [`docs/plans/014-realtime-per-tick.md`](../014-realtime-per-tick.md)                                                           | Deferred Phase 10 design                | P10-I1/P10-I2 evidence plus blocked VCI discovery and live collector/control runtime                             |
| [`docs/plans/015-cross-service-observability-correlation.md`](../015-cross-service-observability-correlation.md)               | Superseded historical design            | Replaced by Plan 024 and Phase 11; do not schedule                                                               |
| [`docs/plans/016-shared-api-contract-and-unified-openapi.md`](../016-shared-api-contract-and-unified-openapi.md)               | Deferred technical debt                 | Not roadmap-scheduled                                                                                            |
| [`docs/plans/017-global-dataset-metadata-refactor.md`](../017-global-dataset-metadata-refactor.md)                             | Implemented historical plan             | Phase 3 metadata migration history                                                                               |
| [`docs/plans/018-internal-tools-parquet-viewer.md`](../018-internal-tools-parquet-viewer.md)                                   | Compatibility pointer                   | Canonical execution is the focused Omni Console plan                                                             |
| [`docs/plans/019-consolidated-numbered-implementation-phases.md`](../019-consolidated-numbered-implementation-phases.md)       | Compatibility roadmap index             | This roadmap; navigation only                                                                                    |
| [`docs/plans/020-next-phase-implementation-plan.md`](../020-next-phase-implementation-plan.md)                                 | Superseded compatibility document       | This roadmap; do not update status or schedule from it                                                           |
| [`docs/plans/022-notification-outbox.md`](../022-notification-outbox.md)                                                       | Active P8-I5 supporting detail          | Canonical registry owns status, dependencies, and execution order                                                |
| [`docs/plans/023-dependency-aware-outbox-dispatch.md`](../023-dependency-aware-outbox-dispatch.md)                             | Active P4-I3 supporting detail          | Canonical registry owns status, dependencies, readiness, and execution order                                     |
| [`docs/plans/024-polyglot-correlation-structured-logging.md`](../024-polyglot-correlation-structured-logging.md)               | Deferred Phase 11 supporting plan       | P11-I1 through P11-I5; canonical registry owns status and execution order                                        |
| [`docs/plans/027-concurrent-workers-and-writer-batching.md`](../027-concurrent-workers-and-writer-batching.md)                 | Deferred Phase 12 supporting plan       | P12-I1 through P12-I4; owner reactivation and measured baseline required                                         |
| [`docs/plans/029-operator-trust-console.md`](../029-operator-trust-console.md)                                                 | Phase 13 supporting implementation plan | P13-I1 through P13-I4; basic operations remain independent and only graph-specific presentation waits for P14-I2 |
| [`docs/plans/030-static-graph-dispatch-planner.md`](../030-static-graph-dispatch-planner.md)                                   | Active Milestone 1 epic                 | P14-I1 through P14-I3; canonical registry owns story status, dependencies, and order                             |
| [`docs/reference/001-algorithm-feature-catalog.md`](../../reference/001-algorithm-feature-catalog.md)                          | Supporting reference                    | Phase 9 and Phase 10 feature naming                                                                              |

## Deferred follow-ups

Owner decision (2026-10-07): P10/P11/P12 and wider Console/provider/deployment expansion are technical-debt backlog, not automatic next work. Retain historical IDs, evidence and design dependencies; reactivate only a bounded slice with a measured trigger. See [technical-debt priority index](../../technical-debt/README.md), [Mermaid priority source](../../technical-debt/priority-order.md), and [TD-011](../../technical-debt/011-deferred-observability-capacity-and-realtime.md). A confirmed correctness/security issue can be promoted ahead of feature expansion.

## Selection summary

Select ready P14-I2 first, then P14-I3. P14-I1 is completed with an approved local Platform test/coverage/build gate, attributable per-class coverage, and owner attestation; no CI/deployment/runtime evidence is claimed. P4-I3 is superseded; TD-014 retains its unclosed evidence, and P14-I3 must prove the complete dependency-aware safety contract against the final planner-integrated path. Then follow P13-I1/P13-I2/P13-I4 and the remaining queue.

Automation must not select superseded, blocked, deferred, approval-required, or manual work until the canonical registry and owner decisions make it eligible.

## Codex execution entry point

Use [`automation-rules.md`](automation-rules.md) as the detailed operating protocol. Every run must produce a report matching [`templates/daily-report.md`](templates/daily-report.md), update canonical increment metadata when current status changes, and append a concise entry to [`ReleaseNotes.md`](../../../ReleaseNotes.md) only for durable implementation, verification, release, deferral, or owner-policy milestones.

## Field/DTO impact and task boundaries — 2026-10-07

[Cross-plan field/DTO inventory](../../reference/002-planned-field-dto-impact.md) records change kinds, LOW/MEDIUM/HIGH impact and candidate contract decisions. Plan 029 separates contract inventory, additive timing, stage read projection, startedAt semantic cutover, baseline, operations API/ETA/heuristics, scanner checks/cache/calendar and fixed UI into bounded tasks. Plan 030 is the active epic for P14-I1/P14-I2/P14-I3; its static topology, snapshot planner, and dispatcher integration are separate independently scheduled stories. Existing evidence and statuses remain unchanged; no task alone satisfies a story's acceptance criteria. Deferred P10/P11/P12 and proposed Plan 028 remain outside automatic selection.
