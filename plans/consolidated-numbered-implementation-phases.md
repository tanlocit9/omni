# Consolidated Numbered Implementation Phases

This file is a compatibility index. The canonical autonomous-delivery roadmap starts at [`plans/roadmap/README.md`](roadmap/README.md), and the dependency-ordered increment registry is [`plans/roadmap/implementation-increments.md`](roadmap/implementation-increments.md).

> Status reconciliation: before executing or updating an increment on `feature/parquet-date-normalization`, apply [`roadmap/status-reconciliation-2026-08-25.md`](roadmap/status-reconciliation-2026-08-25.md) where older roadmap text conflicts with the latest verified source/evidence. P1-I4 now requires a backfill-first coordinated cutover with no generic execution/status compatibility window.

## Phase Index

1. [Phase 0 — Immediate correctness hotfixes](roadmap/phase-0-immediate-correctness.md)
2. [Phase 1 — Backend/Core stabilization](roadmap/phase-1-backend-core-stabilization.md)
3. [Phase 2 — Cross-service Proto3 contracts](roadmap/phase-2-proto3-contracts.md)
4. [Phase 3 — Dataset manifests and version lineage](roadmap/phase-3-dataset-manifests.md)
5. [Phase 4 — Job dependency guard](roadmap/phase-4-job-dependency-guard.md)
6. [Phase 5 — Portable containers and centralized object storage](roadmap/phase-5-portable-deployment.md)
7. [Phase 6 — Omni Console: Dataset Explorer first](roadmap/phase-6-omni-console.md)
8. [Phase 7 — Omni Console job operations](roadmap/phase-7-console-job-operations.md)
9. [Phase 8 — Multi-channel notification routing](roadmap/phase-8-notification-routing.md)
10. [Phase 9 — Intraday EOD](roadmap/phase-9-intraday-eod.md)
11. [Phase 10 — Realtime per tick](roadmap/phase-10-realtime-per-tick.md)

## Supporting Files

- [Dependency-ordered implementation increments](roadmap/implementation-increments.md)
- [Status reconciliation — 2026-08-25](roadmap/status-reconciliation-2026-08-25.md)
- [Automation rules](roadmap/automation-rules.md)
- [Cross-phase rules and definition of done](roadmap/cross-phase-rules.md)
- [Execution log](roadmap/execution-log.md)
- [Increment template](roadmap/templates/increment.md)
- [Daily report template](roadmap/templates/daily-report.md)

## Immediate Next Action

Do not select work from stale status summaries alone. Reconcile the increment registry with the latest evidence first: Phase 7 is complete; P3-I4 implementation commit `215b41a` passed CI run #153; P1-I3 is committed and CI-verified in that same implementation commit but still lacks an increment-specific PR if that remains a hard completion requirement. P1-I4 must backfill generic execution metadata and delete legacy execution/status fallbacks. Domain-specific keys remain only where they are true business inputs, never as a compatibility copy of generic `workKey`.
