# Intraday End-of-Day Sync Implementation Plan

Status: P9-I1 bounded implementation is active following owner approval on 2026-09-10. P9-I2 and P9-I3 remain deferred technical debt. See [`plans/roadmap/phase-9-intraday-eod.md`](../../plans/roadmap/phase-9-intraday-eod.md).

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

## Implementation Eligibility

The owner approved all six P9-I1 decisions and reactivated the bounded increment on 2026-09-10. The first delivery slice is vnstock 4.x with source VCI, HOSE/HNX/UPCOM, all active symbols on each configured exchange, the latest completed trading session only, and normalized trades only. Bars, features, sectors, Console work, and realtime coupling remain deferred.

| Gate                         | Approved decision                                                                                                                                                                                                                                                                                                                                             | Evidence / deterministic policy                                                                                                                                                                                                                                                     | Current state |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| D9-1 Provider schema         | Use `vnstock.api.quote.Quote` with source `VCI`. Canonical required source fields are timezone-aware `time`, `price`, `volume`, `match_type`, and provider `id`; `id` is the trade identity and `match_type` remains provider evidence rather than a canonical aggressor side.                                                                                | A live redacted HPG probe established fields/dtypes. Normal, empty, and cursor-page fixtures retain redacted live structure; conflicting duplicate/correction cases are synthetic fixtures derived from that schema. VCI pagination uses `last_time`/`truncTime`, not page numbers. | `approved`    |
| D9-2 Trading-date validation | Platform remains scheduled after all configured Vietnam exchanges close and selects the latest completed weekday. Ingestor converts every provider timestamp to `Asia/Ho_Chi_Minh` and requires its local date to equal the requested `tradingDate`; persisted timestamps remain UTC. No repository calendar, holiday, or session-segment validation is used. | Focused fixtures cover accepted timestamps and rejection when the converted local date differs from the requested date.                                                                                                                                                             | `approved`    |
| D9-3 Completeness            | Every active symbol on each configured HOSE, HNX, or UPCOM exchange must reach a terminal successful trade result. Empty/unavailable symbols, exhausted-page ambiguity, mixed dates, repeated cursors, conflicting duplicate IDs, or fetch failures prevent exchange-date READY and publish no replacement pointer.                                           | Cursor fetching continues until fewer than the configured page size are returned. Exact duplicate provider IDs with identical values collapse deterministically; conflicting duplicates reject the partition.                                                                       | `approved`    |
| D9-4 EOD reconciliation      | Compare final normalized trade price with canonical `ad_close`; compare summed trade volume and value with canonical EOD volume/value. Close warns above 0.01% and rejects above 0.05%; volume/value warn above 0.10% and reject above 0.50%. Use the greater of relative tolerance or one provider price/quantity tick where applicable.                     | Boundary fixtures cover pass, warning, and rejection. Warning may publish READY with evidence; rejection preserves the previous READY pointer.                                                                                                                                      | `approved`    |
| D9-5 Corrections             | Accept provider corrections for seven calendar days after the trading date. Rebuild an immutable version from the complete corrected session, validate it, then replace READY last. Identical corrected input is idempotent; correction failure preserves the prior READY pointer.                                                                            | Synthetic correction fixtures prove changed provider-ID values reject as conflicting within one fetch and complete later snapshots publish a new immutable version only after validation. Corrections after the cutoff are unavailable/manual-review outcomes.                      | `approved`    |
| D9-6 Partition layout        | Partition by `(provider, exchange, trading_date)` with one symbol Parquet object per partition, deterministic UTC timestamp/provider-ID ordering, Zstandard compression, and no cross-symbol compaction in P9-I1.                                                                                                                                             | Logical object shape: `intraday/trades/provider=vci/exchange=hose/trading_date=YYYY-MM-DD/{symbol}.parquet`; immutable versions and READY metadata remain owned by shared builders.                                                                                                 | `approved`    |

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

Use the canonical metadata contract in [`docs/plans/003-dataset-metadata-manifest.md`](003-dataset-metadata-manifest.md).

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

- Use only the identity selected by D9-1; timestamp alone is never assumed unique.
- Re-running identical provider input for the same approved partition must produce identical normalized rows, object ordering, and data identity.
- Apply D9-2 local-date validation after converting provider timestamps to `Asia/Ho_Chi_Minh`; keep normalized persisted timestamps UTC.
- Apply D9-3 completeness and D9-4 reconciliation before publishing READY.
- Apply D9-5 correction semantics without mutating or deleting the last valid READY version before replacement validation succeeds.
- Write only the object and partition layout approved by D9-6.

## Implementation Steps

1. Record owner decisions D9-1 through D9-6 and the bounded first delivery slice.
2. Capture provider and local-date fixtures without credentials or sensitive payload content.
3. Update the canonical roadmap to reactivate only P9-I1 and replace its obsolete dependency chain with dependencies actually required by the approved slice.
4. Add canonical trade contracts, validation policy, and approved path builders.
5. Implement ingestion and normalization for the bounded provider/exchange/history scope.
6. Validate completeness and EOD reconciliation, then publish data and metadata using the existing READY-last boundary.
7. Add deterministic 1m bars only if they are included in the approved first slice.
8. Defer 5m/15m bars, features, sectors, Console work, and realtime coupling to their owning increments.

## Contract Impact

- **Kafka/service-to-service protobuf:** the active JSON wire contract adds
  `topic-sync-intraday-eod` and matching Java producer/Python consumer fields. Proto3
  and generated contracts are unchanged for this bounded increment.
- **Object-storage JSON manifest:** adds immutable per-version manifests plus a small
  mutable `READY.json` pointer. Candidate failure preserves the prior pointer;
  `_metadata/metadata.json` remains solely owned by `SYNC_METADATA`.
- **Storage path/dataset ownership:** adds `intraday-trades` through shared builders;
  Ingestor owns normalized VCI trade objects for HOSE, HNX, and UPCOM.
- **Public Java/Python API:** adds the scheduler producer/message, reconciliation, path,
  and immutable publication APIs, plus the Ingestor VCI adapter. The command has no
  calendar-version field or shared calendar API.
- **Configuration/environment contract:** adds the shared path pattern, Kafka topic, and
  `exchanges` job configuration. The default is the shared HOSE/HNX/UPCOM universe; no
  market-calendar configuration is required. No credential, region, or physical path
  enters a job message.

## Repository Guidance Updates

Canonical Kafka, data-lake, and flow documentation is synchronized in
[`docs/data/001-kafka-contracts.md`](../data/001-kafka-contracts.md),
[`docs/data/002-data-lake.md`](../data/002-data-lake.md), and
[`docs/flows/005-intraday-eod.md`](../flows/005-intraday-eod.md). `AGENTS.md`,
`CLAUDE.md`, and `.roo/rules` require no change because their existing READY-last,
logical-routing, generated-contract, and verification-gate rules already cover P9-I1.

## Verification

Required checks are **not run** by owner instruction: inspect targets, then run focused
Platform producer/config tests, Ingestor fixture/router/handler tests, py-common
reconciliation/publication/path tests, owning-project lint/build targets, and only then
approved affected checks. No local pass, CI, commit, deployment, or production
verification is claimed.

## P9-I1 Acceptance Criteria

- D9-1 through D9-6 are recorded as `approved` with linked fixtures or contract evidence.
- The first provider, exchange/symbol scope, history range, and output boundary are explicit.
- Provider timestamps convert to `Asia/Ho_Chi_Minh`, match the requested local date, and persist normalized in UTC without holiday/session validation.
- Duplicate, gap, pagination, partial-session, and correction behavior follows approved deterministic policies.
- Completeness and EOD reconciliation produce explicit READY, unavailable, warning, or rejected outcomes without guessed thresholds.
- Re-running identical input is byte/order/data-version deterministic where the existing storage contract requires it.
- Failed or rejected publication preserves the previous READY version.
- Published metadata records exact source object identity, provider, partition, normalization version, reconciliation outcome, and source execution ID; it has no calendar-version lineage.
- Tests cover every decision fixture and the selected partition layout.

## Stop Conditions

Stop planning or implementation when any D9 gate remains `decision_required`, required provider fixtures cannot be captured, provider semantics conflict across sampled sessions, reconciliation cannot distinguish semantic differences from missing data, or the first slice would require reactivating unrelated Proto3, deployment, Console, or realtime debt.

## Later Increment Acceptance

After P9-I1 is independently proven, P9-I2 may add deterministic 1m/5m/15m bars and reusable features, and P9-I3 may add sector aggregation. Their activation requires separate roadmap status changes; neither is implied by approving P9-I1.
