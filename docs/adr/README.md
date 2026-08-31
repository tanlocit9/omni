# Architecture Decision Records

This directory is the canonical numbered index for accepted Omni architecture decisions. ADR filenames keep the stable `NNN-...` prefix; roadmap phases and implementation plans reference these records but do not replace them.

## How to Use This Index

1. Read the ADR to understand the accepted architectural boundary.
2. Use the roadmap mapping to find when follow-up work is scheduled.
3. Use related implementation plans for delivery detail and verification criteria.
4. Treat current source, roadmap increment status, and execution evidence as the completion authority; an accepted ADR is not proof that every follow-up increment is complete.

## Numbered ADR Registry

| No. | ADR                                                                                              | Status   | Decision scope                                                                                    | Roadmap mapping                                                                                                     | Related implementation plans                                                                                                                                                                                                                                                             |
| --: | ------------------------------------------------------------------------------------------------ | -------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 001 | [Use Nx Monorepo for Multi-service Development](001-nx-monorepo.md)                              | Accepted | Workspace task runner, project graph, and shared Java/Python development boundary                 | Pre-roadmap baseline; applies across Groups A-D                                                                     | [Backend/Core Stabilization](../plans/001-backend-core-stabilization.md), [Portable Docker Deployment](../plans/005-portable-docker-deployment.md)                                                                                                                                       |
| 002 | [Use Kafka for Asynchronous Job Orchestration](002-kafka-job-orchestration.md)                   | Accepted | Platform-to-worker asynchronous commands and worker-to-Platform status events                     | Pre-roadmap baseline; Group A Phases 0-1; foundation for Group B Phases 2 and 4                                     | [Backend/Core Stabilization](../plans/001-backend-core-stabilization.md), [Cross-Service Proto3 Contracts](../plans/002-cross-service-protobuf-contracts.md), [Job Dependency Guard](../plans/004-job-dependency-guard.md)                                                               |
| 003 | [Use Parquet Object Storage as Analytical Data Lake](003-parquet-analytical-storage.md)          | Accepted | Analytical storage, logical path ownership, date/timestamp semantics, and READY-last rewrites     | Pre-roadmap baseline; Group B Phases 3-4; Group C Phases 5-6; Group D Phases 9-10                                   | [Dataset Metadata Manifest](../plans/003-dataset-metadata-manifest.md), [Portable Docker Deployment](../plans/005-portable-docker-deployment.md), [Intraday EOD](../plans/008-intraday-eod.md), [Realtime Per-Tick](../plans/009-realtime-per-tick.md)                                   |
| 004 | [Keep Analyzer Independent from Platform Transactional Database](004-analyzer-no-platform-db.md) | Accepted | Control-plane/data-plane separation and Analyzer persistence boundaries                           | Pre-roadmap baseline; constrains Groups A-D                                                                         | [Backend/Core Stabilization](../plans/001-backend-core-stabilization.md), [Dataset Metadata Manifest](../plans/003-dataset-metadata-manifest.md), [Dataset-Component Market Dashboard](../plans/010-dataset-component-market-dashboard.md)                                               |
| 005 | [Centralize Shared Kafka Contracts](005-shared-kafka-contracts.md)                               | Accepted | Shared topic ownership and coordinated Java/Python payload evolution                              | Pre-roadmap baseline; Group B Phase 2                                                                               | [Cross-Service Proto3 Contracts](../plans/002-cross-service-protobuf-contracts.md), [Cross-Service Observability Correlation](../plans/011-cross-service-observability-correlation.md)                                                                                                   |
| 006 | [Use Precomputed Sector Wave Model](006-sector-wave-precompute-model.md)                         | Accepted | Precomputed symbol/sector features, rankings, and rotation backtests                              | Pre-roadmap analytical baseline; Group A Phase 1 stabilization; Group B Phase 3 manifests; Group D extension inputs | [Backend/Core Stabilization](../plans/001-backend-core-stabilization.md), [Dataset Metadata Manifest](../plans/003-dataset-metadata-manifest.md), [Intraday EOD](../plans/008-intraday-eod.md), [Dataset-Component Market Dashboard](../plans/010-dataset-component-market-dashboard.md) |
| 007 | [Scheduler Claim and Outbox Boundary](007-scheduler-claim-and-outbox-boundary.md)                | Accepted | PostgreSQL leases, fencing, atomic execution/outbox preparation, and at-least-once Kafka dispatch | Group A Phase 1; prerequisite for Group B Phase 4 and Group C Phase 7                                               | [Backend/Core Stabilization](../plans/001-backend-core-stabilization.md), [Job Dependency Guard](../plans/004-job-dependency-guard.md)                                                                                                                                                   |

## Roadmap View

| Roadmap area                                            | Governing ADRs               |
| ------------------------------------------------------- | ---------------------------- |
| Pre-roadmap inherited platform                          | 001, 002, 003, 004, 005, 006 |
| Group A - Control-plane safety (Phases 0-1)             | 001, 002, 004, 006, 007      |
| Group B - Deterministic contracts and data (Phases 2-4) | 002, 003, 004, 005, 006, 007 |
| Group C - Portable operations and product (Phases 5-8)  | 001, 003, 004, 007           |
| Group D - Higher-frequency market data (Phases 9-10)    | 001, 003, 004, 006           |

These mappings indicate architectural relevance, not strict phase dependencies. The [canonical roadmap](../../plans/roadmap/README.md) and its phase files remain authoritative for ordering and eligibility.

## Numbering Rules

- Use the next zero-padded sequence: `008`, `009`, and so on.
- Filename format: `NNN-short-kebab-case-title.md`.
- Title format: `# NNN: Decision Title`.
- Never reuse or renumber an assigned ADR number.
- Keep superseded ADRs in place and mark their status `Superseded`; link to the replacement ADR.
- Use `Proposed`, `Accepted`, `Deprecated`, or `Superseded` as the status.
- Add every new ADR to both the numbered registry and roadmap view in this file.
- Link related plans from the registry, but keep implementation status in the roadmap rather than the ADR.

## ADR Content

Each new record should contain at least:

```text
Status
Context
Decision
Consequences
```

Add alternatives, migration, security, compatibility, or supersession sections when the decision needs them. ADRs record durable decisions; implementation plans describe how and when to deliver them.
