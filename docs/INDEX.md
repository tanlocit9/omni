# Numbered Documentation Registry

This registry covers every non-ADR content document under `docs/`. The navigation files `README.md` and `INDEX.md` are intentionally unnumbered. Each subject folder has an independent zero-padded sequence starting at `001`, and each content filename starts with its folder-local number, while architecture decisions retain their separate [`ADR-NNN` registry](adr/README.md).

## How to Use This Index

1. Use the folder-relative identifier, such as `data/001` or `plans/001`, and its matching `NNN-` filename prefix.
2. Use the [canonical roadmap](../plans/roadmap/README.md) for schedule, increment status, dependencies, and completion evidence.
3. Treat supporting plans as design and verification detail; they do not override the roadmap.
4. Treat technical-debt records as deferred scope until a roadmap increment explicitly schedules them.
5. Follow links in the related-document column to find the governing contract, flow, plan, or decision.

## Canonical References

| No.              | Document                                                                       | Classification                          | Roadmap mapping                                      | Related documents                                                                                                                                                 |
| ---------------- | ------------------------------------------------------------------------------ | --------------------------------------- | ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| architecture/001 | [System Overview](architecture/001-system-overview.md)                         | Canonical architecture reference        | Pre-roadmap; Groups A-D                              | [ADR registry](adr/README.md), [roadmap](../plans/roadmap/README.md)                                                                                              |
| data/001         | [Kafka Contracts](data/001-kafka-contracts.md)                                 | Canonical contract reference            | Pre-roadmap; Group B / Phase 2; Groups C-D consumers | [ADR-002](adr/ADR-002-kafka-job-orchestration.md), [ADR-005](adr/ADR-005-shared-kafka-contracts.md), [Proto3 plan](plans/002-cross-service-protobuf-contracts.md) |
| data/002         | [Data Lake](data/002-data-lake.md)                                             | Canonical dataset and storage reference | Pre-roadmap; Group B / Phase 3; Groups C-D consumers | [ADR-003](adr/ADR-003-parquet-analytical-storage.md), [metadata plan](plans/003-dataset-metadata-manifest.md)                                                     |
| data/003         | [Database](data/003-database.md)                                               | Canonical operational-schema reference  | Pre-roadmap; Group A / Phases 0-1; Group B / Phase 4 | [Job execution](flows/001-job-execution.md), [ADR-007](adr/ADR-007-scheduler-claim-and-outbox-boundary.md)                                                        |
| reference/001    | [Algorithm Feature Catalog](reference/001-algorithm-feature-catalog.md)        | Canonical analytical-feature index      | Pre-roadmap; Groups B-D                              | [Data Lake](data/002-data-lake.md), [Indicator and Signal Flow](flows/003-indicator-signal.md), [Sector Wave Flow](flows/004-sector-wave.md)                      |
| governance/001   | [Implementation Plan Standard](governance/001-implementation-plan-standard.md) | Canonical planning policy               | Groups A-D                                           | [roadmap](../plans/roadmap/README.md), [documentation registry](README.md)                                                                                        |

## Flow References

| No.       | Document                                                   | Classification                                        | Roadmap mapping                                                            | Related documents                                                                                                                                         |
| --------- | ---------------------------------------------------------- | ----------------------------------------------------- | -------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| flows/001 | [Job Execution Flow](flows/001-job-execution.md)           | Canonical control-plane flow                          | Pre-roadmap; Group A / Phases 0-1; Group B / Phase 4; Group C / Phases 7-8 | [Database](data/003-database.md), [Kafka Contracts](data/001-kafka-contracts.md), [ADR-007](adr/ADR-007-scheduler-claim-and-outbox-boundary.md)           |
| flows/002 | [Stock Sync Flow](flows/002-stock-sync.md)                 | Canonical ingestion flow                              | Pre-roadmap; Group D / Phases 9-10                                         | [Data Lake](data/002-data-lake.md), [Kafka Contracts](data/001-kafka-contracts.md)                                                                        |
| flows/003 | [Indicator and Signal Flow](flows/003-indicator-signal.md) | Canonical analytics flow                              | Pre-roadmap; Group D consumers                                             | [Data Lake](data/002-data-lake.md), [Kafka Contracts](data/001-kafka-contracts.md), [Feature Catalog](reference/001-algorithm-feature-catalog.md)         |
| flows/004 | [Sector Wave Flow](flows/004-sector-wave.md)               | Canonical sector-analytics and deferred-research flow | Pre-roadmap; future analytical scope                                       | [ADR-006](adr/ADR-006-sector-wave-precompute-model.md), [Data Lake](data/002-data-lake.md), [Feature Catalog](reference/001-algorithm-feature-catalog.md) |

## Development and Operations Guides

| No.             | Document                                                                                          | Classification                  | Roadmap mapping                      | Related documents                                                                                                                                |
| --------------- | ------------------------------------------------------------------------------------------------- | ------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| development/001 | [Where to Change](development/001-where-to-change.md)                                             | Canonical developer navigation  | Groups A-D                           | [System Overview](architecture/001-system-overview.md), [documentation rules](README.md#documentation-rules)                                     |
| development/002 | [Manual Verification Handoff](development/002-manual-verification-handoff.md)                     | Verification procedure          | Groups A-D                           | [roadmap automation rules](../plans/roadmap/automation-rules.md), [implementation plan standard](governance/001-implementation-plan-standard.md) |
| development/003 | [Codex Control and Tooling Plan](development/003-codex-control-and-tooling.md)                    | Proposed developer-tooling plan | Cross-cutting; not roadmap-scheduled | [roadmap automation rules](../plans/roadmap/automation-rules.md), [Where to Change](development/001-where-to-change.md)                          |
| deployment/001  | [P1-I4 Execution Identity Hard Cutover](deployment/001-p1-i4-hard-cutover.md)                     | Phase 1 deployment runbook      | Group A / Phase 1 / P1-I4            | [Backend/Core plan](plans/001-backend-core-stabilization.md), [Job Execution Flow](flows/001-job-execution.md)                                   |
| deployment/002  | [Cloudflare-First Low-Cost Deployment Decision](deployment/002-cloudflare-low-cost-deployment.md) | Proposed deployment decision    | Group C / Phase 5                    | [Portable deployment plan](plans/005-portable-docker-deployment.md), [ADR-003](adr/ADR-003-parquet-analytical-storage.md)                        |

## Roadmap Supporting Plans

| No.       | Document                                                                        | Classification                 | Roadmap mapping    | Related documents                                                                                                                                                 |
| --------- | ------------------------------------------------------------------------------- | ------------------------------ | ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| plans/001 | [Backend/Core Stabilization](plans/001-backend-core-stabilization.md)           | Supporting implementation plan | Group A / Phase 1  | [Phase 1 roadmap](../plans/roadmap/phase-1-backend-core-stabilization.md), [ADR-007](adr/ADR-007-scheduler-claim-and-outbox-boundary.md)                          |
| plans/002 | [Cross-Service Proto3 Contracts](plans/002-cross-service-protobuf-contracts.md) | Supporting implementation plan | Group B / Phase 2  | [Phase 2 roadmap](../plans/roadmap/phase-2-proto3-contracts.md), [ADR-005](adr/ADR-005-shared-kafka-contracts.md), [Kafka Contracts](data/001-kafka-contracts.md) |
| plans/003 | [Dataset Metadata Manifest](plans/003-dataset-metadata-manifest.md)             | Supporting implementation plan | Group B / Phase 3  | [Phase 3 roadmap](../plans/roadmap/phase-3-dataset-manifests.md), [Data Lake](data/002-data-lake.md)                                                              |
| plans/004 | [Job Dependency Guard](plans/004-job-dependency-guard.md)                       | Supporting implementation plan | Group B / Phase 4  | [Phase 4 roadmap](../plans/roadmap/phase-4-job-dependency-guard.md), [Job Execution Flow](flows/001-job-execution.md)                                             |
| plans/005 | [Portable Docker Deployment](plans/005-portable-docker-deployment.md)           | Supporting implementation plan | Group C / Phase 5  | [Phase 5 roadmap](../plans/roadmap/phase-5-portable-deployment.md), [Cloudflare decision](deployment/002-cloudflare-low-cost-deployment.md)                       |
| plans/006 | [Internal Tools Parquet Viewer](plans/006-internal-tools-parquet-viewer.md)     | Compatibility pointer          | Group C / Phase 6  | [Phase 6 roadmap](../plans/roadmap/phase-6-omni-console.md), [focused Console plan](../plans/omni-metadata-console-dashboard-execution-plan.md)                   |
| plans/007 | [Telegram Multi-Channel](plans/007-telegram-multi-channel.md)                   | Supporting implementation plan | Group C / Phase 8  | [Phase 8 roadmap](../plans/roadmap/phase-8-notification-routing.md), [Job Execution Flow](flows/001-job-execution.md)                                             |
| plans/008 | [Intraday EOD](plans/008-intraday-eod.md)                                       | Supporting implementation plan | Group D / Phase 9  | [Phase 9 roadmap](../plans/roadmap/phase-9-intraday-eod.md), [Stock Sync Flow](flows/002-stock-sync.md)                                                           |
| plans/009 | [Realtime Per-Tick](plans/009-realtime-per-tick.md)                             | Supporting implementation plan | Group D / Phase 10 | [Phase 10 roadmap](../plans/roadmap/phase-10-realtime-per-tick.md), [Kafka Contracts](data/001-kafka-contracts.md)                                                |

## Proposed and Compatibility Plans

| No.       | Document                                                                                              | Classification                    | Roadmap mapping                          | Related documents                                                                                                                                                                   |
| --------- | ----------------------------------------------------------------------------------------------------- | --------------------------------- | ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| plans/010 | [Dataset-Component Market Dashboard](plans/010-dataset-component-market-dashboard.md)                 | Proposed supporting plan          | Group C / Phase 6; not roadmap-scheduled | [Phase 6 roadmap](../plans/roadmap/phase-6-omni-console.md), [focused Console plan](../plans/omni-metadata-console-dashboard-execution-plan.md), [Data Lake](data/002-data-lake.md) |
| plans/011 | [Cross-Service Observability Correlation](plans/011-cross-service-observability-correlation.md)       | Proposed cross-cutting plan       | Groups A-D; not roadmap-scheduled        | [System Overview](architecture/001-system-overview.md), [Kafka Contracts](data/001-kafka-contracts.md)                                                                              |
| plans/012 | [Telegram Notification Format Modernization](plans/012-telegram-notification-format-modernization.md) | Proposed Phase 8 follow-up        | Group C / Phase 8; not roadmap-scheduled | [Telegram Multi-Channel plan](plans/007-telegram-multi-channel.md), [Phase 8 roadmap](../plans/roadmap/phase-8-notification-routing.md)                                             |
| plans/013 | [Next Phase Implementation Plan](plans/013-next-phase-implementation-plan.md)                         | Superseded compatibility document | Historical Phases 1-10                   | [canonical roadmap](../plans/roadmap/README.md), [numbered phase index](../plans/consolidated-numbered-implementation-phases.md)                                                    |

## Technical Debt

| No.                | Document                                                                                                        | Classification                      | Roadmap mapping                | Related documents                                                                                                                                        |
| ------------------ | --------------------------------------------------------------------------------------------------------------- | ----------------------------------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| technical-debt/001 | [P3-I5 Metadata Reconciliation Technical Debt](technical-debt/001-p3-i5-metadata-reconciliation.md)             | Verification-pending technical debt | Group B / Phase 3 / P3-I5      | [Phase 3 roadmap](../plans/roadmap/phase-3-dataset-manifests.md), [Data Lake](data/002-data-lake.md)                                                     |
| technical-debt/002 | [Telegram Notification Deduplication Technical Debt](technical-debt/002-telegram-notification-deduplication.md) | Deferred technical debt             | Group C / Phase 8 follow-up    | [Telegram Multi-Channel plan](plans/007-telegram-multi-channel.md), [format modernization plan](plans/012-telegram-notification-format-modernization.md) |
| technical-debt/003 | [Temporary System Operator UUID Technical Debt](technical-debt/003-system-operator-uuid.md)                     | Deferred security/identity debt     | Group C / Phases 6-7 follow-up | [Phase 6 roadmap](../plans/roadmap/phase-6-omni-console.md), [Phase 7 roadmap](../plans/roadmap/phase-7-console-job-operations.md)                       |

## Roadmap View

| Roadmap scope                              | Primary registry entries                                                                           |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| Pre-roadmap baseline                       | `architecture/001`; `data/001-003`; `reference/001`; `flows/001-004`                               |
| Group A - Control-plane safety             | `data/003`; `flows/001`; `deployment/001`; `plans/001`                                             |
| Group B - Deterministic contracts and data | `data/001-003`; `plans/002-004`; `technical-debt/001`                                              |
| Group C - Portable operations and product  | `flows/001`; `deployment/002`; `plans/005-007`, `plans/010`, `plans/012`; `technical-debt/002-003` |
| Group D - Higher-frequency market data     | `data/001-002`; `reference/001`; `flows/002-003`; `plans/008-009`                                  |
| Cross-cutting governance and tooling       | `governance/001`; `development/001-003`; `plans/011`                                               |
| Historical compatibility                   | `plans/006`, `plans/013`                                                                           |

## Numbering Rules

- Maintain a separate sequence in every non-ADR subject folder; each sequence starts at `001`.
- Use the next number in that folder and prefix the filename with it, for example `plans/014-short-kebab-case-title.md`.
- Identify documents in this registry by folder and local number, for example `plans/014`; there is no global `DOC-NNN` sequence.
- Never reuse or renumber an assigned number within the same folder.
- Keep superseded documents registered and label their classification clearly.
- Add each new non-ADR content document under `docs/` to one subject registry and the roadmap view; leave navigation indexes unnumbered.
- Register architecture decisions in [`docs/adr/README.md`](adr/README.md), not here.
- A registry mapping records relevance; it does not establish implementation status or completion evidence.
