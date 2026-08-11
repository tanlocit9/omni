# Intraday End-of-Day Sync Implementation Plan

## Goal

Add historical intraday market data after market close, then build deterministic 1m/5m/15m bars and reusable features.

Every completed data partition must publish its dataset metadata to MinIO. No PostgreSQL/Redis metadata cache is required in V1.

## Outcome

After this phase Omni can:

- sync complete intraday sessions;
- backtest with 1m/5m/15m data;
- calculate VWAP, momentum, volume and volatility features;
- inspect partition size, object count, row count, schema, range and freshness through MinIO manifests;
- use those manifests as downstream dataset readiness markers.

## Proposed Jobs

```text
SYNC_INTRADAY_EOD
  -> intraday trades
  -> READY manifest

BUILD_INTRADAY_BARS
  -> 1m / 5m / 15m bars
  -> READY manifest

BUILD_INTRADAY_FEATURES
  -> reusable features
  -> READY manifest
```

Writer order is mandatory:

```text
write Parquet -> validate -> write metadata manifest last
```

## Dataset Outputs

```text
stock-data/intraday/trades/
  date=YYYY-MM-DD/exchange=HOSE/part-*.parquet

stock-data/intraday/bars/
  timeframe=1m/date=YYYY-MM-DD/exchange=HOSE/part-*.parquet

stock-data/intraday/features/
  timeframe=1m/date=YYYY-MM-DD/exchange=HOSE/part-*.parquet
```

Raw trade fields where available:

```text
trading_date
timestamp
exchange
symbol
price
volume
trade_value
trade_id?
sequence?
side?
```

Bar fields:

```text
trading_date
bar_time
exchange
symbol
open
high
low
close
volume
value
trade_count
vwap
```

5m/15m should be derived from canonical trade/1m data.

## MinIO Metadata Outputs

Each completed partition writes one manifest under:

```text
stock-data/_metadata/datasets/intraday-trades/...
stock-data/_metadata/datasets/intraday-bars/...
stock-data/_metadata/datasets/intraday-features/...
```

Example:

```text
_metadata/datasets/intraday-bars/
  timeframe=1m/date=2026-08-11/exchange=HOSE.json
```

Manifest should contain:

```text
status = READY
path
objectCount
totalBytes
rowCount
columnCount
schemaHash
minTimestamp
maxTimestamp
sourceExecutionId
generatedAt
```

See `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`.

## Algorithm Feature Outputs

Price/momentum:

```text
return_1m
return_5m
return_15m
return_from_open
distance_from_session_high
distance_from_session_low
close_location_value
intraday_momentum_5m
intraday_momentum_15m
```

VWAP:

```text
vwap
vwap_distance_pct
above_vwap
minutes_above_vwap
```

Volume/liquidity:

```text
trade_count
cumulative_volume
cumulative_value
volume_share_of_session
relative_intraday_volume
average_trade_size
volume_acceleration
```

Volatility/range:

```text
bar_range_pct
realized_volatility_15m
realized_volatility_30m
opening_range_pct
opening_range_position
opening_range_breakout
```

Provider-dependent (`CONDITIONAL`):

```text
buy_volume
sell_volume
volume_delta
cumulative_volume_delta
buy_trade_ratio
```

Do not fabricate canonical buy/sell side when the provider does not supply a reliable field.

## Sector-Level Features Unlocked

```text
sector_return_5m
sector_return_15m
breadth_positive_return_5m
breadth_above_vwap
breadth_new_session_high
sector_relative_volume
sector_realized_volatility
leader_contribution
laggard_contribution
```

## Algorithms Unlocked

- intraday momentum/reversal;
- VWAP confirmation;
- opening-range strategies;
- sector rotation by session phase;
- volume breakout confirmation;
- intraday volatility regimes;
- next-session/daily signal confirmation.

## Readiness / Dependency Rule

Downstream jobs must read the expected partition manifest and verify:

```text
manifest exists
status == READY
partition date/timeframe matches
schema version supported
freshness acceptable
```

Do not repeatedly scan the full data prefix just to decide whether a dataset is ready.

## Idempotency and Validation

- Prefer provider trade id/sequence for identity when available.
- Timestamp alone is not assumed unique.
- Re-running the same partition must be deterministic.
- Failed rewrites should not replace the last valid READY manifest.
- Validate session range, duplicates, symbol/exchange, price/volume, schema and daily volume reconciliation where feasible.

## Implementation Steps

1. Confirm provider intraday schema/history availability.
2. Add canonical trade/bar contracts and manifest contract in `py_common`.
3. Add intraday + `_metadata` path builders.
4. Implement `SYNC_INTRADAY_EOD` and publish manifest after validation.
5. Build 1m then deterministic 5m/15m bars and manifests.
6. Build reusable feature dataset and manifest.
7. Use manifests for scheduler dependency checks.
8. Add manifests to Internal Tools Dataset Browser.

## Acceptance Criteria

- Intraday sessions sync idempotently.
- 1m/5m/15m outputs are deterministic.
- Feature outputs are reusable by multiple algorithms.
- Every successful partition has a READY MinIO manifest.
- Internal Tools can display stats without scanning all Parquet objects.
- Downstream jobs can validate freshness/readiness from the manifest.
