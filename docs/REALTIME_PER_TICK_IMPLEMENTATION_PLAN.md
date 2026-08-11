# Realtime Per-Tick Market Data Implementation Plan

## Goal

Add realtime market-data ingestion only after the historical intraday pipeline is stable.

The realtime system must treat Kafka as the durable streaming boundary and Parquet as analytical/archive storage. Do not append one tick at a time directly into a canonical Parquet file.

## Outcome

After this phase Omni can:

- receive and normalize market ticks continuously;
- replay/recover ticks from Kafka after consumer failure;
- build live 1m bars and intraday features;
- archive raw ticks into compactable Parquet chunks;
- feed realtime signal/sector algorithms without waiting for end-of-day synchronization.

## Target Architecture

```text
Market Data WebSocket/API
          |
          v
      Tick Ingestor
          |
          v
 Kafka market-ticks.raw
      |             |
      |             +--------------------+
      v                                  v
Realtime Bar/Feature               Tick Archiver
Aggregator                              |
      |                           buffered chunks
      v                                  |
1m bars/features                         v
      |                          part-*.parquet
      v                                  |
Realtime algorithms                     v
                                  EOD compaction
                                        |
                                        v
                               canonical tick archive
```

## Dataset Outputs

### Kafka raw tick event

Canonical event should contain a versioned envelope plus market payload:

```text
schema_version
event_id
source
received_at
market_timestamp
trading_date
exchange
symbol
price
volume
trade_value
sequence?      # provider dependent
trade_id?      # provider dependent
side?          # provider dependent
```

Kafka key should normally preserve symbol ordering, for example:

```text
HOSE:ACB
```

### Realtime raw tick archive

```text
stock-data/intraday/ticks/
  date=YYYY-MM-DD/
    exchange=HOSE/
      part-*.parquet
```

Archive by micro-batch, then compact after the session.

Do not create one Parquet object per tick.

### Realtime bars/features

Reuse the same canonical schema/naming as the historical Intraday EOD pipeline.

Realtime and batch outputs must converge to equivalent values for the same completed bar.

## Algorithm Feature Outputs

### Tick intensity

- `ticks_1s` — `DERIVED`
- `ticks_5s` — `DERIVED`
- `volume_1s` — `DERIVED`
- `volume_5s` — `DERIVED`
- `trade_intensity_zscore` — `DERIVED`
- `average_trade_size_5s` — `DERIVED`

### Short-horizon price dynamics

- `return_5s` — `DERIVED`
- `return_30s` — `DERIVED`
- `return_1m` — `DERIVED`
- `micro_momentum_30s` — `DERIVED`
- `price_acceleration` — `DERIVED`
- `distance_from_vwap` — `DERIVED`

### Liquidity / flow

When fields are available:

- `buy_volume_5s` — `CONDITIONAL`
- `sell_volume_5s` — `CONDITIONAL`
- `volume_delta_5s` — `CONDITIONAL`
- `cumulative_volume_delta` — `CONDITIONAL`
- `buy_sell_imbalance` — `CONDITIONAL`

If future source includes order book levels, define those in a separate order-book contract rather than mixing snapshot depth fields into the trade tick schema.

### Realtime sector features

Aggregate symbol streams into:

```text
sector_return_1m
sector_breadth_positive_1m
sector_breadth_above_vwap
sector_relative_volume
sector_tick_intensity
sector_new_high_ratio
sector_new_low_ratio
leader_contribution_live
```

These are high-value inputs for realtime sector rotation and market-state detection.

## Algorithms Unlocked

This phase enables:

- realtime signal confirmation;
- realtime sector leadership/rotation;
- short-horizon momentum/reversal;
- unusual volume/trade-intensity alerts;
- realtime breakout detection;
- streaming anomaly detection;
- alert throttling based on live confidence;
- later online/near-online feature scoring.

It does not by itself require ML. Rule-based algorithms should consume the same reusable features first.

## Kafka Topics

Suggested V1:

```text
market-ticks.raw
market-bars.1m
```

Avoid creating topics per sector or symbol.

Introduce additional feature topics only when an actual consumer needs them.

## Delivery Semantics

Aim for at-least-once ingestion with idempotent downstream processing.

Requirements:

- stable event identity when provider supplies trade id/sequence;
- consumer offsets committed only after state/update succeeds;
- duplicate-safe bar aggregation;
- explicit handling for out-of-order/late ticks;
- bounded lateness window before a bar is considered final.

Exactly-once end-to-end semantics are not a V1 requirement.

## Buffering and Parquet Archival

`TickArchiver` should buffer by size/time, for example:

```text
flush when rows >= configured threshold
OR elapsed time >= configured threshold
```

Do not hard-code a tiny 1-5 second Parquet flush if it creates excessive small files. Tune using observed tick volume and object-storage behavior.

At end of session:

```text
micro-batch parquet parts
       |
       v
COMPACT_INTRADAY_TICKS
       |
       v
larger canonical parts
```

Delete/archive temporary pieces only after compaction is verified.

## Realtime vs Batch Consistency

The historical Intraday EOD pipeline is the reconciliation source.

For each completed session compare:

```text
realtime tick count vs EOD sync count
realtime total volume vs EOD total volume
realtime 1m OHLCV vs rebuilt batch 1m OHLCV
```

Persist mismatch metrics and surface them in Operations/Internal Tools.

This prevents silent realtime data loss from contaminating later algorithms.

## Backpressure

Ingestor must not let downstream slowness block the market socket indefinitely.

Design for:

- bounded in-memory buffers;
- Kafka producer batching;
- retry/backoff;
- reconnect/resubscribe;
- metrics for lag, dropped/rejected messages, reconnects and publish latency.

No tick should be intentionally dropped without an observable counter/reason.

## Implementation Steps

### Step 1 — Provider capability spike

- [ ] Confirm streaming protocol, limits and reconnect semantics.
- [ ] Confirm whether trade id/sequence/aggressor side are available.
- [ ] Record provider timestamp precision and ordering guarantees.

### Step 2 — Shared contract

- [ ] Add versioned tick event model in `py_common` or equivalent shared contract location.
- [ ] Add normalization tests and fixtures.
- [ ] Define symbol/exchange normalization once.

### Step 3 — Ingestor

- [ ] Implement websocket connection lifecycle.
- [ ] Normalize and publish keyed events to Kafka.
- [ ] Add reconnect/backpressure/metrics.

### Step 4 — Tick archiver

- [ ] Consume raw tick topic.
- [ ] Micro-batch writes to partitioned Parquet.
- [ ] Add safe compaction job.
- [ ] Make restart/reprocessing idempotent.

### Step 5 — Realtime bars/features

- [ ] Build duplicate-safe 1m aggregator.
- [ ] Support late/out-of-order events.
- [ ] Reuse feature formulas/names from Intraday EOD.
- [ ] Publish or persist finalized bars/features as required.

### Step 6 — Reconciliation

- [ ] Compare realtime results with EOD historical sync.
- [ ] Store completeness/quality metrics.
- [ ] Send operational alert when mismatch exceeds threshold.

## Acceptance Criteria

- Realtime ticks survive analyzer/archiver restarts through Kafka replay.
- No per-tick Parquet append design is used.
- Raw tick archive is partitioned and compactable.
- Realtime 1m bars converge with batch-rebuilt 1m bars within defined tolerance.
- Feature names/semantics match historical intraday features.
- Duplicate and late ticks are handled explicitly.
- Data-quality mismatch is observable.
- Realtime algorithms can consume stable reusable features without depending directly on provider-specific payloads.
