# Intraday End-of-Day Sync — P9-I1

Status: **implementation complete / pending owner verification**. Verification commands are intentionally not run by agent instruction. Canonical checklist and commit evidence live in [`plans/roadmap/phase-9-intraday-eod.md`](../../plans/roadmap/phase-9-intraday-eod.md).

## Goal

Persist completed-session intraday trades for VCI across HOSE/HNX/UPCOM using deterministic normalization, canonical EOD reconciliation, immutable READY-last publication, and the existing scheduler/manual-trigger boundary.

P9-I1 remains intentionally bounded to normalized trades. Bars, reusable intraday features, sector aggregation, Console-specific UI work, and realtime coupling remain deferred to later increments.

## Approved decisions

| Gate | Decision |
| --- | --- |
| D9-1 Provider | `vnstock.api.quote.Quote`, source `VCI`; required fields `time`, `price`, `volume`, `match_type`, `id`; provider `id` is trade identity. |
| D9-2 Trading date | Convert provider timestamps to `Asia/Ho_Chi_Minh` and require local date = requested `tradingDate`; persist timestamp UTC. No holiday/calendar-version validation in P9-I1. |
| D9-3 Completeness | A symbol/date candidate must produce non-empty terminal normalized trades. Mixed dates, cursor ambiguity, fetch failure, or conflicting duplicate provider IDs reject the candidate. |
| D9-4 Reconciliation | Compare final price to canonical EOD close and summed trade volume/value to canonical EOD volume/value. Close warns >0.01%, rejects >0.05%; volume/value warn >0.10%, reject >0.50%. |
| D9-5 Corrections | Rebuild a complete immutable candidate; validation/reconciliation must finish before READY replacement. Identical normalized bytes retain content-derived identity. |
| D9-6 Layout | `intraday/trades/provider=vci/exchange=<exchange>/trading_date=YYYY-MM-DD/<symbol>.parquet`, one symbol object per partition leaf. |

## Execution paths

### Scheduled

`SYNC_INTRADAY_EOD` runs after market close on weekdays. Platform resolves the latest completed weekday, enumerates all active symbols in configured HOSE/HNX/UPCOM exchanges, and emits one `IntradayEodJobMessage` per symbol/date.

### Manual historical backfill

The existing Phase 7 manual trigger API is reused; there is no separate backfill pipeline.

Accepted runtime parameter shapes for `SYNC_INTRADAY_EOD`:

```json
{"tradingDate":"2026-09-07"}
```

or:

```json
{"startDate":"2026-09-01","endDate":"2026-09-07"}
```

Rules:

- dates must be historical;
- single-date requests must be weekdays;
- range length is bounded to 31 calendar days;
- range fan-out skips Saturday/Sunday;
- scheduled cron cadence is unchanged;
- manual and scheduled runs produce the same message and pass through the same Ingestor handler;
- deterministic message work key is `<symbolKey>:<tradingDate>`;
- deployment must explicitly allow-list `SYNC_INTRADAY_EOD:VCI` (or the definition UUID) before operator-triggered backfill is available.

The allow-list is not changed automatically because repository guidance requires explicit owner/deployment authorization for that security boundary.

## Processing flow

```text
Platform scheduler/manual trigger
  -> SYNC_INTRADAY_EOD producer
  -> IntradayEodJobMessage(symbol, exchange, tradingDate, provider=VCI)
  -> VCI cursor fetch
  -> normalize + local-date validation + duplicate policy
  -> read canonical EOD symbol Parquet for exact tradingDate
  -> deterministic reconciliation
      READY/WARNING -> continue
      REJECTED      -> stop before publication
  -> encode Parquet
  -> immutable candidate data
  -> read-back validation
  -> immutable version manifest
  -> READY.json last
  -> terminal job status
```

## Canonical normalization

Persisted normalized trade fields include:

```text
trading_date
timestamp          # UTC
exchange
symbol
provider_id
price
volume
trade_value
match_type
```

Exact duplicate `provider_id` rows with identical comparable values collapse deterministically. Conflicting values under the same provider ID reject the candidate.

## Canonical EOD reconciliation

The handler reads the existing EOD Parquet through `ParquetStorage` using `StockDataPaths.eod(exchange, symbol)` and requires exactly one row matching the requested trading date.

Accepted canonical field aliases are:

- close: `ad_close` or `close`;
- volume: `total_volume`, `nm_volume`, or `volume`;
- value: `nm_value`, `total_value`, or `value`.

Missing/ambiguous canonical EOD evidence rejects the candidate. Reconciliation runs before immutable publication. `WARNING` may publish with evidence; `REJECTED` cannot replace the previous READY pointer.

Manifest evidence includes:

```text
status = READY
completeness = COMPLETE
reconciliation.status = READY | WARNING
reconciliation.close
reconciliation.volume
reconciliation.value
sourceExecutionId
normalizationVersion
rowCount
objectCount
dataVersion
path
```

## Idempotency and failure semantics

- Same normalized Parquet bytes derive the same SHA-256 `dataVersion`.
- Partition and object identity depend only on provider/exchange/trading date/symbol.
- Manual backfill uses the same immutable publisher as scheduled ingestion.
- Reconciliation rejection happens before publication.
- Candidate data/version-manifest validation happens before `READY.json` replacement.
- Failure before READY replacement preserves the prior READY pointer.

## Source evidence

Implementation is represented by the Phase 9 branch commits following the initial `328450d` slice:

- `022b855` — scheduled/manual date fan-out in the intraday producer;
- `880fb24` — bounded manual backfill parameter validation;
- `6aa6f13` — HOSE/HNX/UPCOM alignment and canonical EOD reconciliation;
- `0152ddc` — pass existing EOD `ParquetStorage` into the intraday handler;
- `43c8d0c` — reconciliation-focused Ingestor tests;
- `9ee71dc` — scheduled, single-date, and range fan-out producer tests;
- `190fab1` — owner verification checklist/status in the canonical roadmap.

Focused test source exists in:

- `apps/ingestor/tests/test_intraday_eod.py`;
- `apps/core/src/test/java/com/omni/platform/modules/scheduler/producers/SyncIntradayEodJobProducerTest.java`;
- `libs/py-common/tests/storage/test_immutable_publication.py`;
- existing messaging/config tests referenced by the roadmap checklist.

## Owner verification

**NOT RUN.** Do not interpret source tests or commits as successful verification.

Use the complete checklist in [`plans/roadmap/phase-9-intraday-eod.md`](../../plans/roadmap/phase-9-intraday-eod.md). It covers exchange/provider scope, single-date/range backfill, future-date rejection, deterministic reruns, completeness, reconciliation, duplicate/correction behavior, immutable READY preservation, partition identity, Kafka contracts, timestamp/date semantics, scheduler behavior, manual-trigger allow-list, and affected Nx/project checks.

P9-I1 must remain `pending_owner_verification` and `last_verified_commit` must remain `null` until the owner runs and records the approved checks.

## Intentional technical debt

- No Vietnam exchange holiday/calendar-version model; weekday-only scheduling/backfill may safely fail on market holidays.
- No session-segment validation.
- No dedicated large-backfill queue/backpressure policy beyond the 31-calendar-day request bound.
- No automatic market-calendar correction-window enforcement.
- No bars/features/sectors/realtime work in P9-I1.

## Later increments

P9-I2 may add deterministic 1m/5m/15m bars and reusable intraday features after P9-I1 is independently owner-verified and explicitly reactivated. P9-I3 may then add sector aggregation/lineage under its own gate. Neither is activated by this implementation.
