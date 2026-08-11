# Intraday End-of-Day Sync Implementation Plan

## Goal

Add historical intraday market data without introducing realtime-streaming complexity yet.

After the trading session closes, Omni fetches the complete intraday session, stores canonical raw intraday data, builds reusable minute bars, and optionally precomputes reusable intraday features.

## Outcome

After this phase Omni can:

- inspect complete intraday sessions after market close;
- backtest strategies using 1m/5m/15m data instead of only daily candles;
- calculate intraday momentum, VWAP, volume and volatility features;
- compare sector/symbol behavior inside the trading session;
- validate the same datasets through Internal Tools/Parquet Viewer.

This is the recommended step before realtime per-tick ingestion.

## Proposed Jobs

```text
SYNC_INTRADAY_EOD
        |
        v
canonical intraday trades
        |
        v
BUILD_INTRADAY_BARS
        |
        +-- 1m
        +-- 5m
        +-- 15m
        |
        v
BUILD_INTRADAY_FEATURES
```

Keep raw synchronization and derived feature computation separate so algorithms can be changed without re-fetching the source data.

## Dataset Outputs

### Raw intraday/trades

Preferred partitioning:

```text
stock-data/intraday/trades/
  date=YYYY-MM-DD/
    exchange=HOSE/
      part-*.parquet
```

Canonical fields where available:

```text
trading_date
timestamp
exchange
symbol
price
volume
trade_value
trade_id?          # provider dependent
sequence?          # provider dependent
side?              # provider dependent
```

### Intraday bars

```text
stock-data/intraday/bars/
  timeframe=1m/
    date=YYYY-MM-DD/
      exchange=HOSE/
        part-*.parquet
```

Canonical fields:

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

5m and 15m should be derived from the canonical 1m/trade source, not fetched independently unless the provider contract requires it.

### Intraday features

```text
stock-data/intraday/features/
  timeframe=1m/
    date=YYYY-MM-DD/
      exchange=HOSE/
        part-*.parquet
```

Feature files may initially share the bar dataset if schema size remains manageable. Split only when lifecycle or query patterns justify it.

## Algorithm Feature Outputs

### Price / momentum

- `return_1m` — `DIRECT/DERIVED`
- `return_5m` — `DERIVED`
- `return_15m` — `DERIVED`
- `return_from_open` — `DERIVED`
- `distance_from_session_high` — `DERIVED`
- `distance_from_session_low` — `DERIVED`
- `close_location_value` — `DERIVED`
- `intraday_momentum_5m` — `DERIVED`
- `intraday_momentum_15m` — `DERIVED`

### VWAP / execution context

- `vwap` — `DIRECT`
- `vwap_distance_pct` — `DERIVED`
- `above_vwap` — `DERIVED`
- `minutes_above_vwap` — `DERIVED`

### Volume / liquidity

- `trade_count` — `DIRECT`
- `cumulative_volume` — `DERIVED`
- `cumulative_value` — `DERIVED`
- `volume_share_of_session` — `DERIVED`
- `relative_intraday_volume` — `DERIVED`, requires historical same-time baselines
- `average_trade_size` — `DERIVED`
- `volume_acceleration` — `DERIVED`

### Volatility / range

- `bar_range_pct` — `DERIVED`
- `realized_volatility_15m` — `DERIVED`
- `realized_volatility_30m` — `DERIVED`
- `opening_range_pct` — `DERIVED`
- `opening_range_position` — `DERIVED`
- `opening_range_breakout` — `DERIVED`

### Order-flow features

Only if provider supplies reliable aggressor side/order-flow fields:

- `buy_volume` — `CONDITIONAL`
- `sell_volume` — `CONDITIONAL`
- `volume_delta` — `CONDITIONAL`
- `cumulative_volume_delta` — `CONDITIONAL`
- `buy_trade_ratio` — `CONDITIONAL`

Do not fabricate buy/sell classification from price movement in the canonical ingestion layer.

## Sector-Level Features Unlocked

Once multiple sectors are synced consistently, aggregate symbol intraday features into:

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

These should later become an intraday extension of the existing Sector Wave/Sector Transition feature model.

## Algorithms Unlocked

This phase enables:

- intraday momentum/reversal research;
- VWAP-based entry/exit filters;
- opening-range strategies;
- sector rotation by hour/session phase;
- volume breakout confirmation;
- intraday volatility regime classification;
- daily signal confidence using intraday confirmation;
- next-session prediction features.

## Scheduling

Run after the market session has settled, not immediately at the exact close.

Example dependency flow:

```text
SYNC_INTRADAY_EOD
  produces intraday-trades

BUILD_INTRADAY_BARS
  depends on intraday-trades
  produces intraday-bars

BUILD_INTRADAY_FEATURES
  depends on intraday-bars
  produces intraday-features
```

Use dataset readiness/freshness validation rather than relying only on fixed minute gaps between jobs.

## Idempotency

Natural raw identity should prefer a provider trade identifier/sequence when available.

Fallback dedupe key may use a stable compound key such as:

```text
exchange + symbol + timestamp + price + volume + occurrence_index
```

Do not assume timestamp alone is unique.

Re-running the same trading date must produce the same canonical dataset.

## Validation

Before marking sync successful verify:

- requested trading date is correct;
- timestamps fall within valid trading sessions;
- no negative price/volume;
- symbol/exchange are known;
- data is ordered or sortable deterministically;
- duplicate rate is within expected threshold;
- session coverage is reasonable;
- total volume/value can be reconciled against an independent daily total where feasible.

Persist validation metrics in job metadata.

## Implementation Steps

### Step 1 — Source contract

- [ ] Confirm provider endpoint/schema and historical availability.
- [ ] Define canonical trade schema in `py_common`.
- [ ] Identify guaranteed vs optional fields.

### Step 2 — Storage paths

- [ ] Add intraday trade/bar/feature paths to shared stock-data path configuration.
- [ ] Use date + exchange partitioning.
- [ ] Keep paths compatible with DuckDB glob scans.

### Step 3 — Scheduler + Kafka

- [ ] Add `SYNC_INTRADAY_EOD`.
- [ ] Add `BUILD_INTRADAY_BARS`.
- [ ] Add `BUILD_INTRADAY_FEATURES` when initial formulas are stable.
- [ ] Register dataset dependencies/outputs.

### Step 4 — Ingestor

- [ ] Fetch the complete target session.
- [ ] Normalize and validate.
- [ ] Write canonical Parquet idempotently.
- [ ] Publish execution status with coverage metrics.

### Step 5 — Analyzer

- [ ] Build 1m bars.
- [ ] Derive 5m/15m bars from canonical data.
- [ ] Add reusable feature calculations in shared/domain-appropriate modules.
- [ ] Avoid embedding strategy decisions into the feature dataset.

### Step 6 — Internal Tools

- [ ] Add intraday trades and bars to dataset catalog.
- [ ] Add time-range/symbol filters.
- [ ] Add basic candlestick/volume view later; table inspection is sufficient for V0.

## Acceptance Criteria

- A complete historical intraday session can be synced idempotently.
- 1m bars can be reproduced from canonical raw intraday data.
- 5m/15m bars are deterministic derived datasets.
- Feature outputs are reusable by multiple strategies.
- Provider-dependent fields are explicitly marked and optional.
- Data is queryable directly from Parquet via Internal Tools.
- The phase does not require a realtime Kafka tick stream.
