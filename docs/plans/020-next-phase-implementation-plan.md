# Omni — Next Phase Implementation Plan

> **Status: Superseded compatibility document.** Use the [canonical roadmap](../../plans/roadmap/README.md) for phase order, increment status, dependencies, and execution evidence. This file preserves historical context only and must not be used to schedule or report work.

## Direction

Make contracts and data readiness deterministic first, then make compute portable, then increase market-data frequency.

```text
Backend correctness
      |
      v
Proto3 cross-service contracts
      |
      v
Dataset manifests + dataVersion lineage
      |
      v
Job dependency guard
      |
      v
Portable containers + centralized S3/R2
      |
      v
Internal Tools / shared data visibility
      |
      v
Intraday EOD
      |
      v
Realtime per-tick
```

## Outcome

After these phases Omni has:

- one typed contract source shared by Java/Python services;
- object-storage-native readiness/version/lineage metadata;
- dependency-safe scheduling without relying on fixed cron gaps;
- disposable compute with canonical data centralized in S3/R2;
- shared visibility/backtesting inputs across developers;
- a consistent feature vocabulary from daily to realtime.

## Phase 1 — Backend/Core Stabilization

See `BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md`.

Focus on correctness blockers that must be fixed regardless of later architecture:

- due-job query correctness;
- multi-sector universe alignment;
- single logical writer for shared Sector Transition outputs;
- atomic scheduler claiming.

## Phase 2 — Cross-Service Proto3 Contracts

See `CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md`.

### Outcome

- canonical `.proto` source under `libs/contracts/proto`;
- generated Java/Python types;
- Buf lint/breaking/generate through Nx;
- typed JobCommand/JobStatus/DatasetRef foundation;
- future MarketTick contract direction.

### Algorithm Feature Outputs

No direct algorithm feature output.

## Phase 3 — Dataset Metadata / Version Lineage

See `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`.

### Outcome

Canonical datasets publish JSON READY manifests under `_metadata/` with fast stats plus `dataVersion` and upstream input lineage.

### Algorithm Feature Outputs

No direct market feature output.

## Phase 4 — Job Dependency Guard

See `JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md`.

### Outcome

Due jobs run only when required manifests satisfy readiness/current-input conditions. BLOCKED dependencies are deferred rather than reported as failed executions.

### Algorithm Feature Outputs

No direct market feature output.

## Phase 5 — Portable Containers + Central Object Storage

See `PORTABLE_DOCKER_DEPLOYMENT_IMPLEMENTATION_PLAN.md`.

### Outcome

- immutable application images from GHCR;
- cloud profile without local MinIO;
- Parquet/manifests centralized in S3/R2;
- PostgreSQL backup/restore from object storage;
- new compute hosts require no data-lake migration.

## Phase 6 — Omni Console

See `INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`.

### Outcome

Developers browse manifests/dependency status and query canonical Parquet through
the private server-side Query Service using native DuckDB.

## Phase 8 — Telegram Channel Separation

See `TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md`.

Operational and market-signal destinations remain separate.

## Phase 9 — Intraday EOD

See `INTRADAY_EOD_IMPLEMENTATION_PLAN.md`.

Key feature outputs include:

```text
return_1m
return_5m
return_15m
vwap_distance_pct
relative_intraday_volume
volume_acceleration
realized_volatility_15m
realized_volatility_30m
opening_range_position
opening_range_breakout
```

## Phase 10 — Realtime Per-Tick

See `REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md`.

Start only after intraday schemas/features and protobuf event contracts are stable.

Key feature outputs include:

```text
ticks_1s
ticks_5s
volume_1s
volume_5s
trade_intensity_zscore
return_5s
return_30s
micro_momentum_30s
sector_tick_intensity
```

## Cross-Phase Contract

All implementation plans follow `IMPLEMENTATION_PLAN_STANDARD.md`.

Every implementation/review must explicitly cover:

1. Outcome;
2. Dataset Outputs;
3. Metadata Outputs;
4. Algorithm Feature Outputs;
5. Contract Impact;
6. Repository Guidance Updates;
7. Verification;
8. Acceptance Criteria.

`Repository Guidance Updates` requires reviewing `AGENTS.md`, `CLAUDE.md` and Zoo Code workspace rules under `.roo/rules/` whenever the phase changes architecture/contracts/workflows.

## Recommended Execution Order

```text
1. Backend correctness blockers
2. Create contracts Nx project + Buf config
3. Introduce JobCommand / JobStatus / DatasetRef proto3 contracts
4. Migrate producer/consumer boundaries incrementally
5. Add DatasetManifest dataVersion + input lineage
6. Implement manifest-based JobDependencyGuard
7. Enforce dependencies for selected analytical jobs
8. Configure centralized S3/R2
9. Production Docker images + cloud Compose
10. PostgreSQL backup/restore rehearsal
11. Internal Tools Dataset/Dependency Browser
12. Telegram routing split
13. SYNC_INTRADAY_EOD
14. BUILD_INTRADAY_BARS / FEATURES
15. Intraday sector aggregation
16. MarketTick proto + realtime provider spike
17. market-ticks.raw Kafka pipeline
18. realtime archive/reconciliation
19. realtime signal/sector consumers
```

Do not add a full DAG orchestrator, Redis metadata cache, Kubernetes/ECS, or AI/ML as a prerequisite for these phases.

## Repository Guidance Policy

All phase plans inherit the guidance-sync requirement even if an older document does not yet contain an explicit section.

When an older plan is implemented or materially edited, add its `Repository Guidance Updates` section and synchronize relevant files in the same change.
