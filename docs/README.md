# Omni Documentation

This directory is the documentation entry point for Omni. It is designed to help a new developer understand the system without reading implementation-level code first.

## Start Here

1. [System overview](architecture/system-overview.md) — service boundaries, infrastructure, and ownership.
2. [Where to change](development/where-to-change.md) — which project/file area to start from for common changes.
3. [Kafka contracts](data/kafka-contracts.md) — canonical topic/message map.
4. [Data lake](data/data-lake.md) — canonical Parquet dataset/path ownership.
5. [Job execution](flows/job-execution.md) — scheduler/worker execution flow.
6. [Implementation plan standard](IMPLEMENTATION_PLAN_STANDARD.md) — mandatory plan/outcome/feature/contract/agent-guidance format.
7. [Scheduler claim and outbox boundary ADR](adr/ADR-007-scheduler-claim-and-outbox-boundary.md) — Phase 1A claim foundation and Phase 1B outbox boundary.
8. [Metadata → Omni Console → Dashboard execution plan](../plans/omni-metadata-console-dashboard-execution-plan.md) — current focused milestone-gated execution order.
9. [Codex control and tooling plan](development/codex-control-and-tooling.md) — proposed skills, MCP integrations, guardrails, and rollout order.
10. [Canonical roadmap](../plans/roadmap/README.md) — phase ordering, increment status, dependencies, and execution evidence.
11. [Phase 7 Console job operations](../plans/roadmap/phase-7-console-job-operations.md) — Platform-owned job catalog/trigger/status contracts and the Omni Console Jobs tab.
12. [Phase 3 dataset manifests](../plans/roadmap/phase-3-dataset-manifests.md) — canonical manifests and verification-pending automatic EOD metadata reconciliation.
13. [Cloudflare-first low-cost deployment decision](deployment/cloudflare-low-cost-deployment.md) — zero-cost, demo, and minimal-VPS profiles with readiness blockers.
14. [P1-I4 execution identity hard cutover](deployment/p1-i4-hard-cutover.md) — coordinated drain, manual history cleanup, deploy, verification, and rollback procedure.

## Contract / Coordination Plans

- [Cross-Service Proto3 Contracts](CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md)
- [Dataset Metadata Manifest](DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md)
- [Job Dependency Guard](JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md)
- [Portable Docker Deployment](PORTABLE_DOCKER_DEPLOYMENT_IMPLEMENTATION_PLAN.md)
- [Cloudflare-first low-cost deployment decision](deployment/cloudflare-low-cost-deployment.md)

## Data / Product Plans

- [Omni Console / Parquet Viewer compatibility pointer](INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md)
- [Intraday EOD](INTRADAY_EOD_IMPLEMENTATION_PLAN.md)
- [Realtime Per-Tick](REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md)
- [Telegram Multi-Channel](TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md)
- [Backend/Core Stabilization](BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md)
- [Algorithm Feature Catalog](ALGORITHM_FEATURE_CATALOG.md)
- [Telegram Notification Deduplication Technical Debt](TELEGRAM_NOTIFICATION_DEDUPLICATION_TECHNICAL_DEBT.md)
- [Temporary System Operator UUID Technical Debt](TECHNICAL-DEBT-SYSTEM-OPERATOR-UUID.md)

## Canonical Documents

| Topic                     | Canonical document                                                 | Source of truth                            |
| ------------------------- | ------------------------------------------------------------------ | ------------------------------------------ |
| System boundaries         | [architecture/system-overview.md](architecture/system-overview.md) | `apps/` and `libs/`                        |
| Developer navigation      | [development/where-to-change.md](development/where-to-change.md)   | Current project layout                     |
| Kafka topics/contracts    | [data/kafka-contracts.md](data/kafka-contracts.md)                 | topic config + `libs/contracts/proto`      |
| Parquet datasets/paths    | [data/data-lake.md](data/data-lake.md)                             | `configs/shared/s3-paths.yaml` + manifests |
| Database domains          | [data/database.md](data/database.md)                               | `database/migrations`                      |
| Architecture decisions    | [adr](adr)                                                         | Accepted ADR files                         |
| Implementation-plan rules | [IMPLEMENTATION_PLAN_STANDARD.md](IMPLEMENTATION_PLAN_STANDARD.md) | Repository planning policy                 |

## Documentation Rules

- Prefer Mermaid diagrams/tables over long prose.
- Keep one canonical document per concept.
- Do not duplicate Kafka topic or S3 path details outside canonical data docs.
- Flow changes update the matching document under `flows/`.
- Kafka/contract changes update `data/kafka-contracts.md` and the canonical proto definitions.
- Storage/manifest changes update `data/data-lake.md`.
- Service responsibility changes update the related service README.
- Every implementation plan follows `IMPLEMENTATION_PLAN_STANDARD.md`.
- Architecture/contract/workflow changes must review `AGENTS.md`, `CLAUDE.md` and `.roo/rules/` so coding-agent guidance does not drift from the codebase.
