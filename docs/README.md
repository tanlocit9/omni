# Omni Documentation

This directory is the documentation entry point for Omni. It is designed to help a new developer understand the system without reading implementation-level code first. Use the [numbered documentation registry](INDEX.md) for complete non-ADR coverage, classifications, roadmap mappings, and related-document links.

## Start Here

1. [Numbered documentation registry](INDEX.md) — folder-local references and matching `NNN-` filename prefixes for every non-ADR content document under `docs/`.
2. [Architecture decision registry](adr/README.md) — stable `ADR-NNN` references and roadmap mappings for architecture decisions.
3. [System overview](architecture/001-system-overview.md) — service boundaries, infrastructure, and ownership.
4. [Where to change](development/001-where-to-change.md) — which project/file area to start from for common changes.
5. [Kafka contracts](data/001-kafka-contracts.md) — canonical topic/message map.
6. [Data lake](data/002-data-lake.md) — canonical Parquet dataset/path ownership.
7. [Job execution](flows/001-job-execution.md) — scheduler/worker execution flow.
8. [Implementation plan standard](governance/001-implementation-plan-standard.md) — mandatory plan/outcome/feature/contract/agent-guidance format.
9. [Pre-roadmap capability baseline](plans/roadmap/pre-roadmap-capability-baseline.md) — working platform capabilities inherited by Phase 0.
10. [Metadata → Omni Console → Dashboard execution plan](plans/008-omni-metadata-console-dashboard-execution-plan.md) — current focused milestone-gated execution order.
11. [Codex control and tooling plan](development/003-codex-control-and-tooling.md) — proposed skills, MCP integrations, guardrails, and rollout order.
12. [Canonical roadmap](plans/roadmap/README.md) — compact phase view, increment ordering, and links to canonical status/dependencies.
13. [Root release notes](../ReleaseNotes.md) — concise historical implementation, verification, deferral, and owner-decision evidence.
14. [Phase 7 Console job operations](plans/008-omni-metadata-console-dashboard-execution-plan.md) — Platform-owned job catalog/trigger/status contracts and the Omni Console Jobs tab.
15. [Phase 3 dataset manifests](plans/003-dataset-metadata-manifest.md) — canonical manifests and verification-pending automatic EOD metadata reconciliation.
16. [Cloudflare-first low-cost deployment decision](deployment/002-cloudflare-low-cost-deployment.md) — zero-cost, demo, and minimal-VPS profiles with readiness blockers.
17. [P1-I4 execution identity hard cutover](deployment/001-p1-i4-hard-cutover.md) — coordinated drain, manual history cleanup, deploy, verification, and rollback procedure.
18. [Realtime tick foundation](flows/006-realtime-tick-foundation.md) — strict contract plus finite archive/rebuild/bars/reconciliation; VCI provider discovery and live runtime are deferred and owner-gated; historical blocked states remain.
19. [Concurrent workers and writer batching](plans/027-concurrent-workers-and-writer-batching.md) — deferred Phase 12 design; owner reactivation and the Phase 13 measurement gate are required.
20. [Operator Trust Console](plans/029-operator-trust-console.md) — owner-approved Phase 13 plan for truthful Job Operations, manual EOD Data Health, and the fixed Job Operations → Data Health → Market Review order.

## Planning Map

Use planning documents in this order:

1. [Canonical roadmap](plans/roadmap/README.md) for the compact phase view and [increment registry](plans/roadmap/implementation-increments.md) for current status, dependencies, and execution order; use [ReleaseNotes.md](../ReleaseNotes.md) for historical evidence and the [pre-roadmap baseline](plans/roadmap/pre-roadmap-capability-baseline.md) for inherited features.
2. A focused or supporting execution plan under `docs/plans/` when the roadmap delegates a bounded delivery sequence.
3. The numbered order in `docs/plans/` for phase-local design, implementation, progress, and verification detail; these plans do not override roadmap status or sequencing.
4. A technical-debt document under `docs/technical-debt/` for a deferred, explicitly scoped follow-up; it is not independently scheduled unless linked from an active increment.

Compatibility indexes and superseded plans remain only as navigation aids and must point to their canonical owner.

### Roadmap Supporting Plans

- [Backend/Core Stabilization](plans/001-backend-core-stabilization.md) — Phase 1 detail.
- [Cross-Service Proto3 Contracts](plans/002-cross-service-protobuf-contracts.md) — Phase 2 detail.
- [Dataset Metadata Manifest](plans/003-dataset-metadata-manifest.md) — Phase 3 detail, including the former detailed plan.
- [Parquet Date Normalization](plans/004-parquet-date-normalization-increment.md) — Phase 3 increment record.
- [Job Dependency Guard](plans/005-job-dependency-guard.md) — Phase 4 detail, including the former detailed plan.
- [Job Dependency Guard Progress](plans/006-job-dependency-guard-progress.md) — Phase 4 historical progress record.
- [Portable Docker Deployment](plans/007-portable-docker-deployment.md) — deferred Phase 5 deployment debt.
- [Omni Metadata and Console Execution Plan](plans/008-omni-metadata-console-dashboard-execution-plan.md) — deferred Phase 6 execution record.
- [Dataset-Component Market Dashboard](plans/009-dataset-component-market-dashboard.md) — deferred Phase 6 dashboard debt.
- [Telegram Multi-Channel](plans/010-telegram-multi-channel.md) — Phase 8 routing detail.
- [Telegram Notification Format Modernization](plans/011-telegram-notification-format-modernization.md) — Phase 8 presentation detail.
- [Confirmed Trend Equals MVP](plans/012-confirmed-trend-equals-mvp.md) — Phase 8 combined-signal detail.
- [Intraday EOD](plans/013-intraday-eod.md) — active bounded P9-I1 VCI normalized-trade implementation for HOSE/HNX/UPCOM; later bars/features remain deferred.
- [Realtime Per-Tick](plans/014-realtime-per-tick.md) — bounded Phase 10 contract and finite archive/rebuild evidence plus the blocked VCI provider evidence matrix and historical live-collector proposal, now deferred under TD-011.
- [Polyglot Correlation and Structured Logging](plans/024-polyglot-correlation-structured-logging.md) — deferred Phase 11 design; historical dependencies are retained, owner reactivation is required.
- [Concurrent Workers and Writer Batching](plans/027-concurrent-workers-and-writer-batching.md) — deferred Phase 12 detail; capacity work requires measured baseline and owner reactivation.
- [Operator Trust Console](plans/029-operator-trust-console.md) — canonical Phase 13 supporting detail for P13-I1 through P13-I4.

### Proposed, Historical, and Compatibility Plans

- [Cross-Service Observability Correlation](plans/015-cross-service-observability-correlation.md) — superseded historical design; Plan 024 and Phase 11 are canonical.
- [Shared API Contract and Unified OpenAPI](plans/016-shared-api-contract-and-unified-openapi.md) — deferred developer-platform debt.
- [Global Dataset Metadata Refactor](plans/017-global-dataset-metadata-refactor.md) — implemented historical migration plan.
- [Omni Console / Parquet Viewer](plans/018-internal-tools-parquet-viewer.md) — Phase 6 compatibility pointer with the former detailed pointer merged in.
- [Consolidated Numbered Implementation Phases](plans/019-consolidated-numbered-implementation-phases.md) — compatibility roadmap index.
- [Next Phase Implementation Plan](plans/020-next-phase-implementation-plan.md) — superseded compatibility document.
- [Notification Outbox and Durable Delivery](plans/022-notification-outbox.md) — active P8-I5 supporting detail for separate scheduler/notification outboxes and durable Telegram delivery; canonical scheduling remains in the roadmap registry.
- [MVP Polyglot Correlation and Sync Failure Logging](plans/024-polyglot-correlation-structured-logging.md) — proposed debugging MVP for locating when and why sync work fails; not roadmap-scheduled.
- [Polycheck Adoption for Omni](plans/025-polycheck-adoption.md) — deferred generic repository-readiness CLI adoption with Nx as the first adapter.
- [ContractKit Adoption for Omni](plans/026-contractkit-adoption.md) — deferred generic contract-lifecycle adoption with Buf as the Protobuf engine and Karapace optional.
- [Reusable Date-Range Job Backfill](plans/028-reusable-date-range-backfill.md) — proposed reuse of existing job definitions, producers, dependency-aware outbox dispatch, and exact dated history/manifest checks; not roadmap-scheduled.

### References and Technical Debt

- [Algorithm Feature Catalog](reference/001-algorithm-feature-catalog.md)
- [P3-I5 Metadata Reconciliation Technical Debt](technical-debt/001-p3-i5-metadata-reconciliation.md)
- [Telegram Notification Deduplication Technical Debt](technical-debt/002-telegram-notification-deduplication.md) — retained cooldown-specific follow-ups; active durable delivery is P8-I5.
- [Temporary System Operator UUID Technical Debt](technical-debt/003-system-operator-uuid.md)
- [Post-MVP Roadmap Work](technical-debt/004-post-mvp-roadmap-work.md) — deferred scope that must not block the active MVP, including production logging hardening.
- [Python Kafka Worker Throughput and Offset Safety](technical-debt/009-python-kafka-worker-throughput-and-offset-safety.md) — open offset-safety debt plus deferred capacity work; confirmed correctness fixes can be promoted separately.
- [Cloudflare-first low-cost deployment decision](deployment/002-cloudflare-low-cost-deployment.md)

## Canonical Documents

| Topic                     | Canonical document                                                                               | Source of truth                            |
| ------------------------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------ |
| Documentation inventory   | [Numbered documentation registry](INDEX.md)                                                      | Markdown files under `docs/`               |
| System boundaries         | [architecture/001-system-overview.md](architecture/001-system-overview.md)                       | `apps/` and `libs/`                        |
| Developer navigation      | [development/001-where-to-change.md](development/001-where-to-change.md)                         | Current project layout                     |
| Kafka topics/contracts    | [data/001-kafka-contracts.md](data/001-kafka-contracts.md)                                       | topic config + `libs/contracts/proto`      |
| Parquet datasets/paths    | [data/002-data-lake.md](data/002-data-lake.md)                                                   | `configs/shared/s3-paths.yaml` + manifests |
| Database domains          | [data/003-database.md](data/003-database.md)                                                     | `database/migrations`                      |
| Architecture decisions    | [ADR registry](adr/README.md)                                                                    | Numbered accepted ADR files                |
| Implementation-plan rules | [governance/001-implementation-plan-standard.md](governance/001-implementation-plan-standard.md) | Repository planning policy                 |

## Documentation Rules

- Prefer Mermaid diagrams/tables over long prose.
- Keep one canonical document per concept.
- Do not duplicate Kafka topic or S3 path details outside canonical data docs.
- Flow changes update the matching numbered document under `flows/`.
- Kafka/contract changes update `data/001-kafka-contracts.md` and the canonical proto definitions.
- Storage/manifest changes update `data/002-data-lake.md`.
- Service responsibility changes update the related service README.
- Every implementation plan follows `governance/001-implementation-plan-standard.md`.
- Register every non-ADR content document under `docs/` in `INDEX.md`; keep architecture decisions in `adr/README.md`.
- Restart numbering at `001` in each subject folder, match registry entries to their `NNN-` filename prefixes, and never reuse assigned numbers within a folder.
- Architecture/contract/workflow changes must review `AGENTS.md`, `CLAUDE.md` and `.roo/rules/` so coding-agent guidance does not drift from the codebase.

## Technical-debt priority and review

[Reviewed debt index](technical-debt/README.md) distinguishes correctness, measured performance, historical closure and deferred features. [Mermaid priority source](technical-debt/priority-order.mmd) shows conditional priority lanes. [TD-011](technical-debt/011-deferred-observability-capacity-and-realtime.md) records P10/P11/P12 follow-up deferral; source-local TODOs link to the existing debt record and do not change runtime behavior.

Diagram sources share the roadmap's visual style: [roadmap.mmd](plans/roadmap/roadmap.mmd) and [priority-order.mmd](technical-debt/priority-order.mmd).
