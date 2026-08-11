# Omni — Next Phase Implementation Plan

## Direction

The next roadmap should build trustworthy analytical data first, then increase data frequency.

```text
Backend/data correctness
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
- direct Parquet observability;
- daily + intraday historical backtesting inputs;
- a future replayable realtime tick stream;
- one consistent reusable feature vocabulary across batch and realtime processing.

## Phase 1 — Backend/Core Stabilization

See `BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md`.

Primary outcomes:

- scheduler correctness;
- aligned multi-sector feature universe;
- single logical Sector Transition writer;
- safer job execution/dependency semantics.

### Algorithm Feature Outputs

This phase mainly protects correctness of existing outputs rather than adding new formulas.

Reliable datasets/features include:

```text
symbol-features
sector-features
breadth_above_ma20
leader/laggard contribution
sector transition predictions/probabilities/outcomes
```

## Phase 2 — Internal Tools / Parquet Viewer

See `INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`.

### Outcome

Developers can inspect/query Parquet directly from the browser using logical path + schema metadata without backend row conversion.

### Algorithm Feature Outputs

No direct algorithm feature output.

The value is validation and observability of all feature datasets before they are used in algorithms.

## Phase 3 — Telegram Channel Separation

See `TELEGRAM_MULTI_CHANNEL_IMPLEMENTATION_PLAN.md`.

### Outcome

Operational noise and market signal output are separated into `OPERATIONS` and `SIGNALS` Telegram destinations.

### Algorithm Feature Outputs

No direct algorithm feature output.

Signal notifications are presentation/output of algorithm results, not model inputs.

## Phase 4 — Intraday EOD

See `INTRADAY_EOD_IMPLEMENTATION_PLAN.md`.

### Outcome

Complete intraday sessions can be synced after market close and converted into deterministic 1m/5m/15m bars/features.

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

## Phase 5 — Realtime Per-Tick

See `REALTIME_PER_TICK_IMPLEMENTATION_PLAN.md`.

Start only after Intraday EOD schemas/features are stable.

### Outcome

Ticks become replayable through Kafka, archived to Parquet in micro-batches, and aggregated into live bars/features.

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

## Cross-Phase Feature Contract

See `ALGORITHM_FEATURE_CATALOG.md`.

All new data plans must follow `IMPLEMENTATION_PLAN_STANDARD.md` and explicitly state:

1. `Outcome`;
2. `Dataset Outputs`;
3. `Algorithm Feature Outputs`;
4. `Algorithms Unlocked`.

## Recommended Execution Order

```text
1. Backend correctness blockers
2. Internal Tools V0
3. Telegram routing split (independent/small)
4. SYNC_INTRADAY_EOD
5. BUILD_INTRADAY_BARS 1m
6. BUILD_INTRADAY_FEATURES
7. Intraday sector aggregation
8. Provider realtime capability spike
9. market-ticks.raw Kafka pipeline
10. realtime bar/feature reconciliation
11. realtime signal/sector consumers
```

Do not add AI/ML as a dependency for these phases. First establish deterministic, backtestable feature datasets and labels; ML can consume them later.
