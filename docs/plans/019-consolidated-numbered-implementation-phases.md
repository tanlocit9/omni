# Consolidated Numbered Implementation Phases

This file is a compatibility index. The canonical autonomous-delivery roadmap starts at [`docs/plans/roadmap/README.md`](roadmap/README.md), the inherited platform is recorded in the [`pre-roadmap capability baseline`](roadmap/pre-roadmap-capability-baseline.md), and the dependency-ordered increment registry is [`docs/plans/roadmap/implementation-increments.md`](roadmap/implementation-increments.md).

> Current status comes only from the canonical increment registry. Historical reconciliation and delivery evidence are retained in the root [`ReleaseNotes.md`](../../ReleaseNotes.md). P1-I4 uses the approved coordinated hard cutover without a generic execution/status compatibility window.

## Capability Group Index

- **Inherited baseline:** [Capabilities present before Phase 0](roadmap/pre-roadmap-capability-baseline.md)
- **Group A — Control-plane safety:** [Phase 0 — Immediate correctness hotfixes](001-backend-core-stabilization.md), [Phase 1 — Backend/Core stabilization](001-backend-core-stabilization.md)
- **Group B — Deterministic contracts and data:** [Phase 2 — Cross-service Proto3 contracts](002-cross-service-protobuf-contracts.md), [Phase 3 — Dataset manifests and version lineage](003-dataset-metadata-manifest.md), [Phase 4 — Job dependency guard](005-job-dependency-guard.md)
- **Group C — Portable operations and product:** [Phase 5 — Portable containers and centralized object storage](007-portable-docker-deployment.md), [Phase 6 — Omni Console](008-omni-metadata-console-dashboard-execution-plan.md), [Phase 7 — Omni Console job operations](008-omni-metadata-console-dashboard-execution-plan.md), [Phase 8 — Multi-channel notification routing](010-telegram-multi-channel.md)
- **Group D — Higher-frequency market data:** [Phase 9 — Intraday EOD](013-intraday-eod.md), [Phase 10 — Realtime per tick](014-realtime-per-tick.md)

The groups are navigation only. Existing phase numbers, dependencies, and increment IDs remain authoritative.

## Supporting Files

- [Dependency-ordered implementation increments](roadmap/implementation-increments.md)
- [Root release notes and historical evidence](../../ReleaseNotes.md)
- [Automation rules](roadmap/automation-rules.md)
- [Cross-phase rules and definition of done](roadmap/cross-phase-rules.md)
- [Increment template](roadmap/templates/increment.md)
- [Daily report template](roadmap/templates/daily-report.md)

## Immediate Next Action

Do not select work from this compatibility index or historical release evidence. Use the active priority order and exit conditions in the canonical increment registry. Domain-specific keys remain true business inputs and must never be treated as compatibility copies of generic scheduler `workKey` identity.
