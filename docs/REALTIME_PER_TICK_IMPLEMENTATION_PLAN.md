# Realtime Per-Tick Market Data Implementation Plan

## Goal

Add realtime market-data ingestion only after the historical Intraday EOD pipeline is stable.

Kafka is the durable streaming boundary; Parquet is analytical/archive storage. Dataset metadata remains in MinIO/S3-compatible storage.

## Outcome

After this phase Omni can:

- ingest and replay normalized market ticks;
- build live bars/features;
- archive raw ticks in micro-batches;
- compact and reconcile a completed trading session;
- publish a final READY MinIO manifest for the canonical session archive;
- feed realtime signal/sector algorithms without waiting for EOD processing.

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
      v             v
Realtime Bar     Tick Archiver
Aggregator       micro-batch Parquet
      |             |
      v             v
live features    EOD compaction
                    |
                    v
             canonical archive
                    |
                    v
             READY manifest
```

Do not append one tick at a time to a canonical Parquet object.

## Dataset Outputs

Kafka raw event:

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
sequence?
trade_id?
side?
```

Raw tick archive:

```text
stock-data/intraday/ticks/
  date=YYYY-MM-DD/exchange=HOSE/part-*.parquet
```

Realtime bars/features reuse the same naming and semantics as Intraday EOD.

## MinIO Metadata Outputs

Do **not** rewrite the canonical manifest for every micro-batch flush.

Preferred lifecycle:

```text
micro-batch parts
   -> optional temporary/session stats
   -> EOD compaction
   -> reconciliation/validation
   -> final READY manifest
```

Final manifest path:

```text
stock-data/_metadata/datasets/realtime-ticks/
  date=YYYY-MM-DD/exchange=HOSE.json
```

It should contain:

```text
status
path
objectCount
totalBytes
rowCount
columnCount
minTimestamp
maxTimestamp
schemaHash
sourceExecutionId
generatedAt
reconciliation metrics
```

The manifest is the canonical completed-session readiness marker.

See `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`.

## Algorithm Feature Outputs

Tick intensity:

```text
ticks_1s
ticks_5s
volume_1s
volume_5s
trade_intensity_zscore
average_trade_size_5s
```

Short-horizon dynamics:

```text
return_5s
return_30s
return_1m
micro_momentum_30s
price_acceleration
distance_from_vwap
```

Provider-dependent (`CONDITIONAL`):

```text
buy_volume_5s
sell_volume_5s
volume_delta_5s
cumulative_volume_delta
buy_sell_imbalance
```

Realtime sector features:

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

## Algorithms Unlocked

- realtime signal confirmation;
- realtime sector leadership/rotation;
- short-horizon momentum/reversal;
- unusual volume/trade-intensity alerts;
- realtime breakout detection;
- streaming anomaly detection;
- later near-online feature scoring.

## Kafka Topics

V1:

```text
market-ticks.raw
market-bars.1m
```

Avoid topics per symbol/sector.

## Delivery Semantics

Aim for at-least-once ingestion with idempotent downstream processing.

Requirements:

- stable identity when provider supplies trade id/sequence;
- commit offsets after downstream state/write succeeds;
- duplicate-safe bar aggregation;
- explicit late/out-of-order handling;
- bounded lateness before bars become final.

Exactly-once end-to-end is not a V1 requirement.

## Realtime vs Batch Reconciliation

Intraday EOD is the historical reconciliation source.

Compare per completed session:

```text
realtime tick count vs EOD count
realtime total volume vs EOD volume
realtime 1m OHLCV vs batch-rebuilt 1m OHLCV
```

Only publish the final READY session manifest after reconciliation is within tolerance or explicitly marked with quality warnings.

## Backpressure

Design for:

- bounded in-memory buffers;
- Kafka producer batching;
- retry/backoff;
- reconnect/resubscribe;
- lag/reconnect/publish-latency metrics;
- observable rejected/dropped-message counters.

## Implementation Steps

1. Confirm provider streaming limits, ordering and optional fields.
2. Add versioned tick contract in `py_common`.
3. Implement websocket lifecycle and Kafka publishing.
4. Implement duplicate-safe realtime 1m aggregation.
5. Implement micro-batch Tick Archiver.
6. Add EOD compaction and batch reconciliation.
7. Write final MinIO READY manifest after validation.
8. Expose final session metadata in Internal Tools.

## Acceptance Criteria

- Kafka replay survives consumer restarts.
- No per-tick Parquet append design is used.
- Raw tick archive is compactable.
- Realtime bars converge with batch-rebuilt bars within tolerance.
- Feature semantics match Intraday EOD.
- Duplicate/late ticks are explicit.
- Canonical session metadata lives in MinIO, not PostgreSQL/Redis.
- Final READY manifest is written only after compaction/reconciliation.
