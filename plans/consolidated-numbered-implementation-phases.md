# Consolidated Numbered Implementation Phases

This file is a compatibility index. The canonical autonomous-delivery roadmap starts at [`plans/roadmap/README.md`](roadmap/README.md), and the dependency-ordered increment registry is [`plans/roadmap/implementation-increments.md`](roadmap/implementation-increments.md).

## Phase Index

1. [Phase 0 — Immediate correctness hotfixes](roadmap/phase-0-immediate-correctness.md)
2. [Phase 1 — Backend/Core stabilization](roadmap/phase-1-backend-core-stabilization.md)
3. [Phase 2 — Cross-service Proto3 contracts](roadmap/phase-2-proto3-contracts.md)
4. [Phase 3 — Dataset manifests and version lineage](roadmap/phase-3-dataset-manifests.md)
5. [Phase 4 — Job dependency guard](roadmap/phase-4-job-dependency-guard.md)
6. [Phase 5 — Portable containers and centralized object storage](roadmap/phase-5-portable-deployment.md)
7. [Phase 6 — Omni Console: Dataset Explorer first](roadmap/phase-6-omni-console.md)
8. [Phase 7 — Multi-channel notification routing](roadmap/phase-7-notification-routing.md)
9. [Phase 8 — Intraday EOD](roadmap/phase-8-intraday-eod.md)
10. [Phase 9 — Realtime per tick](roadmap/phase-9-realtime-per-tick.md)

## Supporting Files

- [Dependency-ordered implementation increments](roadmap/implementation-increments.md)
- [Automation rules](roadmap/automation-rules.md)
- [Cross-phase rules and definition of done](roadmap/cross-phase-rules.md)
- [Execution log](roadmap/execution-log.md)
- [Increment template](roadmap/templates/increment.md)
- [Daily report template](roadmap/templates/daily-report.md)

## Immediate Next Action

The first eligible autonomous increment is [P1-I2 in the increment registry](roadmap/implementation-increments.md) because Phase 0, ADR-007, and the P1-I1 claim foundation are merged and CI-verified on `main`.
