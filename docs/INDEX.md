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
| flows/005 | [Intraday EOD Flow](flows/005-intraday-eod.md)             | Canonical post-close ingestion flow                   | Group D / Phase 9 / P9-I1                                                  | [Data Lake](data/002-data-lake.md), [Kafka Contracts](data/001-kafka-contracts.md), [Intraday EOD plan](plans/013-intraday-eod.md)                        |

| flows/006 | [Realtime Tick Foundation Flow](flows/006-realtime-tick-foundation.md) | Provider-independent contract/archive/rebuild; no live runtime | Group D / Phase 10 / P10-I0-P10-I3 | [Kafka Contracts](data/001-kafka-contracts.md), [Data Lake](data/002-data-lake.md), [Realtime plan](plans/014-realtime-per-tick.md) |

## Development and Operations Guides

| No.             | Document                                                                                          | Classification                  | Roadmap mapping                      | Related documents                                                                                                                                  |
| --------------- | ------------------------------------------------------------------------------------------------- | ------------------------------- | ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| development/001 | [Where to Change](development/001-where-to-change.md)                                             | Canonical developer navigation  | Groups A-D                           | [System Overview](architecture/001-system-overview.md), [documentation rules](README.md#documentation-rules)                                       |
| development/002 | [Manual Verification Handoff](development/002-manual-verification-handoff.md)                     | Verification procedure          | Groups A-D                           | [roadmap automation rules](../plans/roadmap/automation-rules.md), [implementation plan standard](governance/001-implementation-plan-standard.md)   |
| development/003 | [Codex Control and Tooling Plan](development/003-codex-control-and-tooling.md)                    | Proposed developer-tooling plan | Cross-cutting; not roadmap-scheduled | [roadmap automation rules](../plans/roadmap/automation-rules.md), [Where to Change](development/001-where-to-change.md)                            |
| deployment/001  | [P1-I4 Execution Identity Hard Cutover](deployment/001-p1-i4-hard-cutover.md)                     | Phase 1 deployment runbook      | Group A / Phase 1 / P1-I4            | [Backend/Core plan](plans/001-backend-core-stabilization.md), [Job Execution Flow](flows/001-job-execution.md)                                     |
| deployment/002  | [Cloudflare-First Low-Cost Deployment Decision](deployment/002-cloudflare-low-cost-deployment.md) | Proposed deployment decision    | Group C / Phase 5                    | [Portable deployment plan](plans/007-portable-docker-deployment.md), [ADR-003](adr/ADR-003-parquet-analytical-storage.md)                          |
| deployment/003  | [High Availability Notes](deployment/003-high-availability-notes.md)                              | Future deployment guidance      | Cross-cutting worker runtime         | [Worker throughput debt](technical-debt/009-python-kafka-worker-throughput-and-offset-safety.md), [Job Execution Flow](flows/001-job-execution.md) |

## Roadmap Supporting Plans

| No.       | Document                                                                                              | Classification                           | Roadmap mapping                    |
| --------- | ----------------------------------------------------------------------------------------------------- | ---------------------------------------- | ---------------------------------- |
| plans/001 | [Backend/Core Stabilization](plans/001-backend-core-stabilization.md)                                 | Supporting implementation plan           | Group A / Phase 1                  |
| plans/002 | [Cross-Service Proto3 Contracts](plans/002-cross-service-protobuf-contracts.md)                       | Supporting implementation plan           | Group B / Phase 2                  |
| plans/003 | [Dataset Metadata Manifest](plans/003-dataset-metadata-manifest.md)                                   | Consolidated implementation plan         | Group B / Phase 3                  |
| plans/004 | [Parquet Date Normalization](plans/004-parquet-date-normalization-increment.md)                       | Increment record                         | Group B / Phase 3                  |
| plans/005 | [Job Dependency Guard](plans/005-job-dependency-guard.md)                                             | Consolidated implementation plan         | Group B / Phase 4                  |
| plans/006 | [Job Dependency Guard Progress](plans/006-job-dependency-guard-progress.md)                           | Historical progress record               | Group B / Phase 4                  |
| plans/007 | [Portable Docker Deployment](plans/007-portable-docker-deployment.md)                                 | Deferred technical debt                  | Group C / Phase 5                  |
| plans/008 | [Omni Metadata and Console Execution](plans/008-omni-metadata-console-dashboard-execution-plan.md)    | Deferred technical-debt record           | Group C / Phase 6                  |
| plans/009 | [Dataset-Component Market Dashboard](plans/009-dataset-component-market-dashboard.md)                 | Deferred technical debt                  | Group C / Phase 6                  |
| plans/010 | [Telegram Multi-Channel](plans/010-telegram-multi-channel.md)                                         | Supporting implementation plan           | Group C / Phase 8                  |
| plans/011 | [Telegram Notification Format Modernization](plans/011-telegram-notification-format-modernization.md) | Scheduled/deferred implementation        | Group C / Phase 8                  |
| plans/012 | [Confirmed Trend Equals MVP](plans/012-confirmed-trend-equals-mvp.md)                                 | P8-I4 MVP supporting detail              | Group C / Phase 8                  |
| plans/013 | [Intraday EOD](plans/013-intraday-eod.md)                                                             | Active bounded implementation plan       | Group D / Phase 9                  |
| plans/014 | [Realtime Per-Tick](plans/014-realtime-per-tick.md)                                                   | Active bounded implementation plan       | Group D / Phase 10                 |
| plans/024 | [Polyglot Correlation and Structured Logging](plans/024-polyglot-correlation-structured-logging.md)   | Canonical supporting implementation plan | Group E / Phase 11 / P11-I1-P11-I5 |

## Proposed, Historical, and Compatibility Plans

| No.       | Document                                                                                                  | Classification                          | Roadmap mapping                      |
| --------- | --------------------------------------------------------------------------------------------------------- | --------------------------------------- | ------------------------------------ |
| plans/015 | [Cross-Service Observability Correlation](plans/015-cross-service-observability-correlation.md)           | Deferred technical debt                 | Groups A-D; not roadmap-scheduled    |
| plans/016 | [Shared API Contract and Unified OpenAPI](plans/016-shared-api-contract-and-unified-openapi.md)           | Deferred technical debt                 | Cross-cutting; not scheduled         |
| plans/017 | [Global Dataset Metadata Refactor](plans/017-global-dataset-metadata-refactor.md)                         | Implemented historical plan             | Group B / Phase 3 history            |
| plans/018 | [Internal Tools Parquet Viewer](plans/018-internal-tools-parquet-viewer.md)                               | Compatibility pointer                   | Group C / Phase 6                    |
| plans/019 | [Consolidated Numbered Implementation Phases](plans/019-consolidated-numbered-implementation-phases.md)   | Compatibility roadmap index             | Historical Phases 0-10               |
| plans/020 | [Next Phase Implementation Plan](plans/020-next-phase-implementation-plan.md)                             | Superseded compatibility document       | Historical Phases 1-10               |
| plans/021 | [Intraday Confirmation for Confirmed Trend](plans/021-intraday-confirmed-rules.md)                        | Implementation-ready follow-up          | Phase 9 follow-up / Phase 8 signal   |
| plans/022 | [Notification Outbox and Durable Delivery](plans/022-notification-outbox.md)                              | Active P8-I5 supporting detail          | Group C / Phase 8 / P8-I5            |
| plans/024 | [MVP Polyglot Correlation and Sync Failure Logging](plans/024-polyglot-correlation-structured-logging.md) | Proposed supporting plan                | Cross-cutting; not roadmap-scheduled |
| plans/025 | [Polycheck Adoption for Omni](plans/025-polycheck-adoption.md)                                            | Deferred developer-tooling plan         | Cross-cutting; not roadmap-scheduled |
| plans/026 | [ContractKit Adoption for Omni](plans/026-contractkit-adoption.md)                                        | Deferred developer-platform plan        | Cross-cutting; not roadmap-scheduled |
| plans/027 | [Concurrent Workers and Writer Batching](plans/027-concurrent-workers-and-writer-batching.md)             | Phase 12 supporting implementation plan | Group F / Phase 12 / P12-I1-P12-I4   |

## Technical Debt

| No.                | Document                                                                                                                   | Classification                            | Roadmap mapping                      | Related documents                                                                                                                                  |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- | ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| technical-debt/001 | [P3-I5 Metadata Reconciliation Technical Debt](technical-debt/001-p3-i5-metadata-reconciliation.md)                        | Superseded increment follow-up debt       | Group B / Phase 3 / P3-I5            | [Phase 3 roadmap](../plans/roadmap/phase-3-dataset-manifests.md), [Data Lake](data/002-data-lake.md)                                               |
| technical-debt/002 | [Telegram Notification Deduplication Technical Debt](technical-debt/002-telegram-notification-deduplication.md)            | Retained cooldown follow-up debt          | Group C / Phase 8 after P8-I5        | [Notification Outbox plan](plans/022-notification-outbox.md), [format modernization plan](plans/011-telegram-notification-format-modernization.md) |
| technical-debt/003 | [Temporary System Operator UUID Technical Debt](technical-debt/003-system-operator-uuid.md)                                | Deferred security/identity debt           | Group C / Phases 6-7 follow-up       | [Phase 6 roadmap](../plans/roadmap/phase-6-omni-console.md), [Phase 7 roadmap](../plans/roadmap/phase-7-console-job-operations.md)                 |
| technical-debt/004 | [Post-MVP Roadmap Work](technical-debt/004-post-mvp-roadmap-work.md)                                                       | Deferred post-MVP scope                   | Cross-cutting deferred work          | [Plan 024](plans/024-polyglot-correlation-structured-logging.md), [canonical roadmap](../plans/roadmap/README.md)                                  |
| technical-debt/005 | [Job Status Empty Output Semantics](technical-debt/005-job-status-empty-output-semantics.md)                               | Active semantic clarification debt        | Cross-cutting job execution          | [Job Execution Flow](flows/001-job-execution.md), [metadata reconciliation debt](technical-debt/001-p3-i5-metadata-reconciliation.md)              |
| technical-debt/006 | [Job Status Transaction Silent Rollback](technical-debt/006-job-status-transaction-silent-rollback.md)                     | Resolved historical diagnosis             | Cross-cutting job execution          | [Job Execution Flow](flows/001-job-execution.md), [Notification Outbox plan](plans/022-notification-outbox.md)                                     |
| technical-debt/007 | [Async Dependency Evaluation](technical-debt/007-async-dependency-evaluation.md)                                           | Implemented; verification debt remains    | Group B / Phase 4                    | [Phase 4 roadmap](../plans/roadmap/phase-4-job-dependency-guard.md), [Job Execution Flow](flows/001-job-execution.md)                              |
| technical-debt/008 | [VCI Intraday Adapter vnstock 4.x Migration](technical-debt/008-vci-intraday-adapter-vnstock4-migration.md)                | Provider compatibility verification debt  | Group D / Phase 9 / P9-I1            | [Intraday EOD flow](flows/005-intraday-eod.md), [Plan 013](plans/013-intraday-eod.md)                                                              |
| technical-debt/009 | [Python Kafka Worker Throughput and Offset Safety](technical-debt/009-python-kafka-worker-throughput-and-offset-safety.md) | Scheduled pending Phase 12 debt           | Group F / P12-I1-P12-I4              | [Plan 027](plans/027-concurrent-workers-and-writer-batching.md), [High Availability Notes](deployment/003-high-availability-notes.md)              |
| technical-debt/010 | [Kafka Poison Record and Dead-Letter Policy](technical-debt/010-kafka-poison-record-and-dead-letter-policy.md)             | Deferred cross-service operational policy | Cross-cutting; not roadmap-scheduled | [Kafka Contracts](data/001-kafka-contracts.md), [Plan 027](plans/027-concurrent-workers-and-writer-batching.md)                                    |

## Roadmap View

| Roadmap scope                              | Primary registry entries                                                                                              |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| Pre-roadmap baseline                       | `architecture/001`; `data/001-003`; `reference/001`; `flows/001-004`                                                  |
| Group A - Control-plane safety             | `data/003`; `flows/001`; `deployment/001`; `plans/001`, `plans/023`                                                   |
| Group B - Deterministic contracts and data | `data/001-003`; `plans/002-004`, `plans/023`; `technical-debt/001`                                                    |
| Group C - Portable operations and product  | `flows/001`; `deployment/002`; `plans/005-007`, `plans/010`, `plans/012`, `plans/021-022`; `technical-debt/002-003`   |
| Group D - Higher-frequency market data     | `data/001-002`; `reference/001`; `flows/002-003`; `plans/008-009`, `plans/021`                                        |
| Cross-cutting governance and tooling       | `governance/001`; `development/001-003`; `plans/011`, `plans/024-026`; `technical-debt/005-007`, `technical-debt/010` |
| Group F - Worker throughput and writer     | `deployment/003`; `plans/027`; `technical-debt/009-010`                                                               |
| Historical compatibility                   | `plans/006`, `plans/013`                                                                                              |

## Numbering Rules

- Maintain a separate sequence in every non-ADR subject folder; each sequence starts at `001`.
- Use the next number in that folder and prefix the filename with it, for example `plans/014-short-kebab-case-title.md`.
- Identify documents in this registry by folder and local number, for example `plans/014`; there is no global `DOC-NNN` sequence.
- Never reuse or renumber an assigned number within the same folder.
- Keep superseded documents registered and label their classification clearly.
- Add each new non-ADR content document under `docs/` to one subject registry and the roadmap view; leave navigation indexes unnumbered.
- Register architecture decisions in [`docs/adr/README.md`](adr/README.md), not here.
- A registry mapping records relevance; it does not establish implementation status or completion evidence.
