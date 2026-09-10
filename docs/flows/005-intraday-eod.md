# Intraday EOD Flow

## Scope

P9-I1 synchronizes normalized trades only for source VCI across HOSE, HNX, and UPCOM,
every active symbol on each configured exchange, and the latest completed weekday
selected after configured close. Bars, features, sector aggregation, Console, and
realtime processing are excluded.

## Flow

```mermaid
sequenceDiagram
  participant P as Platform
  participant K as Kafka
  participant I as Ingestor
  participant V as vnstock Quote/VCI
  participant S as MinIO

  P->>P: Select active symbols for each configured Vietnam exchange after close
  P->>K: topic-sync-intraday-eod per symbol/date
  K->>I: IntradayEodJobMessage
  loop last_time/truncTime cursor
    I->>V: Quote.intraday for exact session
    V-->>I: trade page
  end
  I->>I: Normalize UTC, validate ICT local date, deduplicate/reconcile
  I->>S: Write immutable symbol Parquet
  I->>S: Read back and validate
  I->>S: Write/read immutable version manifest
  I->>S: Replace READY.json last
  I->>K: terminal topic-sync-job-status
```

The Kafka message contains logical domain identity only. Ingestor resolves storage
through shared builders backed by `configs/shared/s3-paths.yaml`. Each provider
timestamp is converted to `Asia/Ho_Chi_Minh` and its local date must equal the requested
`tradingDate`; the persisted timestamp remains UTC. No holiday or session-segment
validation is performed. An empty/unavailable symbol, cursor ambiguity, conflicting
provider ID, local-date mismatch, reconciliation rejection, or storage validation
failure produces an error status and does not replace an existing READY pointer.

## Correction Window

For seven calendar days after the trading date, a complete corrected provider snapshot
may produce a new immutable version. Identical bytes retain the same SHA-256 identity.
No prior immutable object is mutated or deleted, and READY changes only after candidate
validation succeeds.
