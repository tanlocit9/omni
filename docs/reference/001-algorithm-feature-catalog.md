# Omni Algorithm Feature Catalog

## Purpose

Maintain a single roadmap of reusable analytical features produced by Omni datasets.

This is a feature contract/index, not a strategy definition. A feature can support multiple rule-based strategies, backtests, statistical models, and future ML models.

## Availability Labels

- `CURRENT` — already implemented/persisted.
- `PLANNED` — covered by an implementation plan.
- `CONDITIONAL` — requires optional provider data.

## Daily / EOD

Source datasets:

```text
eod
indicators
signals
symbol-features
sector-features
```

Current/planned reusable features include:

```text
open
high
low
close
volume
ma20
ma50
rsi14
macd
ichimoku_*
return_Tx
above_ma20
relative_strength
breadth_above_ma20
leader_contribution
laggard_contribution
```

Use cases:

- trend/momentum signal;
- sector wave/rotation;
- sector transition;
- market breadth;
- medium-horizon ranking.

## Intraday EOD — PLANNED

Source:

```text
intraday/trades
intraday/bars
intraday/features
```

Features:

```text
return_1m
return_5m
return_15m
return_from_open
vwap
vwap_distance_pct
above_vwap
trade_count
average_trade_size
cumulative_volume
relative_intraday_volume
volume_acceleration
bar_range_pct
realized_volatility_15m
realized_volatility_30m
opening_range_position
opening_range_breakout
```

Conditional flow features:

```text
buy_volume
sell_volume
volume_delta
cumulative_volume_delta
```

Use cases:

- intraday timing;
- breakout/reversal;
- VWAP confirmation;
- volume/volatility regimes;
- next-day features.

## Realtime Per-Tick — PLANNED

Features:

```text
ticks_1s
ticks_5s
volume_1s
volume_5s
trade_intensity_zscore
return_5s
return_30s
micro_momentum_30s
price_acceleration
```

Conditional:

```text
buy_volume_5s
sell_volume_5s
volume_delta_5s
buy_sell_imbalance
```

Use cases:

- realtime alerts;
- streaming anomaly detection;
- realtime signal confirmation;
- short-horizon momentum/reversal.

## Intraday Sector Features — PLANNED

```text
sector_return_1m
sector_return_5m
sector_return_15m
breadth_positive_return_5m
breadth_above_vwap
breadth_new_session_high
sector_relative_volume
sector_realized_volatility
sector_tick_intensity
leader_contribution_live
laggard_contribution_live
```

Use cases:

- intraday sector rotation;
- sector leadership transition;
- regime detection;
- focus-sector ranking.

## Feature Design Rules

1. Features must be deterministic for a defined source dataset and timestamp/evaluation date.
2. Do not mix future data into a feature evaluated at time `T`.
3. Persist raw/reusable features before strategy-specific decision scores.
4. Historical batch and realtime implementations of the same feature must share naming and semantics.
5. Window/timeframe must be explicit in the field name or dataset metadata.
6. Nullable provider-dependent features must remain nullable; do not infer fake values in ingestion.
7. JSON contribution maps are acceptable for explainability, but important scalar features should remain first-class columns for fast filtering/model input.

## Future Registry Schema

When the catalog becomes machine-readable, use a registry similar to:

```text
name
version
entity_type        # SYMBOL | SECTOR | MARKET
frequency          # 1d | 15m | 5m | 1m | tick
source_dataset
dtype
availability
provider_dependency
description
lookback
nullable
```

This registry can later support:

- analyzer dependency validation;
- Internal Tools feature browser;
- strategy input declarations;
- automatic backtest dataset selection;
- future offline feature-store workflows without requiring a feature-store product now.
