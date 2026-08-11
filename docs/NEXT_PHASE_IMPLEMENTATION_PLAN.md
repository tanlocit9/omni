# Omni — Next Phase Implementation Plan

## Direction

The next roadmap should build trustworthy analytical data first, then increase data frequency.

```text
Backend/data correctness
        |
        v
MinIO dataset metadata manifests
        |
        v
Internal Tools / Parquet visibility
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

After these phases Omni evolves from an EOD analytics pipeline into a multi-frequency research platform with:

- reliable scheduled data dependencies;
- object-storage-native dataset readiness/freshness metadata;
- direct Parquet observability;
- daily + intraday historical backtesting inputs;
- replayable realtime tick streams;
- one consistent feature vocabulary across batch and realtime.

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

Dataset statistics/readiness live in MinIO under `_metadata/` and can be read without scanning all Parquet objects.

Metadata includes:

```text
objectCount
totalBytes
rowCount
schema
min/max date or timestamp
freshness
READY status
```

The READY manifest becomes the preferred dependency/freshness marker.

### Dataset Outputs

```text
stock-data/_metadata/catalog.json
stock-data/_metadata/datasets/...
```

### Algorithm Feature Outputs

No direct market feature output.

Metadata enables safer completeness/freshness validation for algorithms and backtests.

## Phase 3 — Internal Tools / Parquet Viewer

See `INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`.

### Outcome

Developers can browse dataset manifests and drill directly into Parquet through DuckDB-Wasm without backend row conversion.

### Algorithm Feature Outputs

No direct algorithm feature output.

## Phase 4 — Telegram Channel Separation

See `TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md`.

### Outcome

Operational notifications and market signal notifications are separated into `OPERATIONS` and `SIGNALS` destinations.

### Algorithm Feature Outputs

No direct algorithm feature output.

## Phase 5 — Intraday EOD

See `INTRADAY_EOD_IMPLEMENTATION_PLAN.md`.

### Outcome

Complete intraday sessions become deterministic 1m/5m/15m datasets with READY MinIO manifests.

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

## Phase 6 — Realtime Per-Tick

See `REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md`.

Start only after Intraday EOD schemas/features are stable.

### Outcome

Ticks become replayable through Kafka, archived to Parquet in micro-batches, compacted/reconciled at EOD, then published with a final READY MinIO manifest.

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

All new data plans must state:

1. Outcome;
2. Dataset Outputs;
3. Metadata Outputs/readiness semantics;
4. Algorithm Feature Outputs;
5. Algorithms Unlocked.

## Recommended Execution Order

```text
1. Backend correctness blockers
2. Shared DatasetManifest + _metadata path contract
3. Internal Tools Dataset Browser + Parquet Viewer
4. Telegram routing split (independent/small)
5. SYNC_INTRADAY_EOD
6. BUILD_INTRADAY_BARS 1m/5m/15m
7. BUILD_INTRADAY_FEATURES
8. Intraday sector aggregation
9. Provider realtime capability spike
10. market-ticks.raw Kafka pipeline
11. realtime archive + compaction + reconciliation
12. realtime signal/sector consumers
```

Do not add Redis/PostgreSQL only to cache dataset statistics in V1. MinIO manifests are the metadata source of truth.

Do not add AI/ML as a dependency for these phases. First establish deterministic, backtestable features and labels; ML can consume them later.
