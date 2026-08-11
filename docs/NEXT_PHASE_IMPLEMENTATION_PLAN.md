# Omni — Next Phase Implementation Plan

## Direction

The roadmap should make compute disposable, centralize persistent data in object storage, then increase data frequency.

```text
Backend/data correctness
        |
        v
MinIO/S3-compatible dataset manifests
        |
        v
Portable containers + centralized S3/R2
        |
        v
Internal Tools / shared data visibility
        |
        v
Intraday EOD historical data
        |
        v
1m/5m/15m reusable features
        |
        v
Realtime per-tick pipeline
        |
        v
Realtime signal/sector algorithms
```

## Outcome

After these phases Omni becomes a portable multi-frequency research platform where:

- compute hosts can be replaced without copying the data lake;
- canonical Parquet datasets and manifests live in S3/R2;
- PostgreSQL is recoverable from object-storage backups;
- developers can consume the same canonical datasets from local or cloud containers;
- daily + intraday + future realtime algorithms share one feature vocabulary.

## Phase 1 — Backend/Core Stabilization

See `BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md`.

### Outcome

- scheduler correctness;
- aligned multi-sector feature universe;
- single logical Sector Transition writer;
- safer job execution/dependency semantics.

### Algorithm Feature Outputs

No new formula is required; this phase protects existing symbol/sector feature correctness.

## Phase 2 — Dataset Metadata Manifests

See `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`.

### Outcome

Dataset statistics/readiness live under `_metadata/` in S3-compatible object storage and can be read without scanning every Parquet object.

### Dataset Outputs

```text
_metadata/catalog.json
_metadata/datasets/...
```

### Algorithm Feature Outputs

No direct market feature output.

## Phase 3 — Portable Containers + Central Object Storage

See `PORTABLE_DOCKER_DEPLOYMENT_IMPLEMENTATION_PLAN.md`.

### Outcome

- application images are pulled from GHCR;
- production compute runs without local MinIO;
- Parquet + manifests are centralized in AWS S3/Cloudflare R2;
- PostgreSQL is backed up to object storage and restored explicitly on a fresh host;
- planned machine migration requires no data-lake copy;
- developers can run the same containers against the same canonical read-only datasets.

### Dataset Outputs

No new market dataset.

Operational objects:

```text
backups/postgres/<environment>/...
backups/postgres/<environment>/latest.json
```

### Algorithm Feature Outputs

No direct algorithm feature output.

This phase makes later feature/backtest results reproducible across machines.

## Phase 4 — Internal Tools / Parquet Viewer

See `INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`.

### Outcome

Developers can browse centralized dataset manifests and query Parquet directly using DuckDB-Wasm without backend row conversion.

### Algorithm Feature Outputs

No direct algorithm feature output.

## Phase 5 — Telegram Channel Separation

See `TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md`.

### Outcome

Operational notifications and market signal notifications are separated into `OPERATIONS` and `SIGNALS` destinations.

### Algorithm Feature Outputs

No direct algorithm feature output.

## Phase 6 — Intraday EOD

See `INTRADAY_EOD_IMPLEMENTATION_PLAN.md`.

### Outcome

Complete intraday sessions become deterministic 1m/5m/15m datasets with READY object-storage manifests.

### Key Algorithm Feature Outputs

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

## Phase 7 — Realtime Per-Tick

See `REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md`.

Start only after Intraday EOD schemas/features are stable.

### Outcome

Ticks become replayable through Kafka, archived to S3/R2 in micro-batches, compacted/reconciled at EOD, then published with a final READY manifest.

### Key Algorithm Feature Outputs

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

## Cross-Phase Contracts

See:

- `IMPLEMENTATION_PLAN_STANDARD.md`
- `ALGORITHM_FEATURE_CATALOG.md`
- `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`

Data plans must state:

1. Outcome;
2. Dataset Outputs;
3. Metadata Outputs/readiness semantics;
4. Algorithm Feature Outputs;
5. Algorithms Unlocked.

## Recommended Execution Order

```text
1. Backend correctness blockers
2. Shared DatasetManifest + _metadata path contract
3. External S3/R2 production storage configuration
4. Production Docker images + cloud Compose profile
5. PostgreSQL backup/restore to object storage
6. Rehearse clean-host restore/migration
7. Internal Tools Dataset Browser + Parquet Viewer
8. Telegram routing split
9. SYNC_INTRADAY_EOD
10. BUILD_INTRADAY_BARS 1m/5m/15m
11. BUILD_INTRADAY_FEATURES
12. Intraday sector aggregation
13. Provider realtime capability spike
14. market-ticks.raw Kafka pipeline
15. realtime archive + compaction + reconciliation
16. realtime signal/sector consumers
```

Do not add Redis/PostgreSQL only to cache dataset statistics in V1. Object-storage manifests are the metadata source of truth.

Do not make local MinIO a production migration dependency. MinIO remains a local/offline development profile; production data is centralized in S3/R2.

Do not add AI/ML as a dependency for these phases. Establish deterministic, backtestable features and labels first.
