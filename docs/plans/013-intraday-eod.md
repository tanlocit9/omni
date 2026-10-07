# Intraday End-of-Day Sync — P9-I1

Status: **implementation complete / pending owner verification**. Verification commands are intentionally not run by agent instruction. Canonical checklist and commit evidence live in [`docs/plans/013-intraday-eod.md`](013-intraday-eod.md).

## Goal

Persist completed-session intraday trades for VCI across HOSE/HNX/UPCOM using deterministic normalization, canonical EOD reconciliation, immutable READY-last publication, and the existing scheduler/manual-trigger boundary.

P9-I1 remains intentionally bounded to normalized trades. Bars, reusable intraday features, sector aggregation, Console-specific UI work, and realtime coupling remain deferred to later increments.

## Approved decisions

| Gate                | Decision                                                                                                                                                                                                                                          |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D9-1 Provider       | `vnstock.api.quote.Quote`, source `VCI`; required fields `time`, `price`, `volume`, `match_type`, `id`; provider `id` is trade identity.                                                                                                          |
| D9-2 Trading date   | Convert provider timestamps to `Asia/Ho_Chi_Minh` and require local date = requested `tradingDate`; persist timestamp UTC. No holiday/calendar-version validation in P9-I1.                                                                       |
| D9-3 Completeness   | A symbol/date candidate must produce non-empty terminal normalized trades. Mixed dates, cursor ambiguity, fetch failure, or conflicting duplicate provider IDs reject the candidate.                                                              |
| D9-4 Reconciliation | Compare final price to canonical EOD close and summed trade volume/value to canonical EOD volume/value. Close warns >0.01%, rejects >0.05%; volume/value warn >0.10%, reject >0.50%.                                                              |
| D9-5 Corrections    | Rebuild a complete immutable candidate; validation/reconciliation must finish before READY replacement. Identical normalized bytes retain content-derived identity.                                                                               |
| D9-6 Layout         | Follow EOD ownership: `(provider, exchange, trading_date, symbol)` identifies `intraday/trades/provider=vci/exchange=<exchange>/trading_date=YYYY-MM-DD/symbol=<symbol>/trades.parquet`, with one independent READY pointer per symbol partition. |

## Execution paths

### Scheduled

`SYNC_INTRADAY_EOD` runs after market close on weekdays. Platform resolves the latest completed weekday, enumerates all active symbols in configured HOSE/HNX/UPCOM exchanges, and emits one `IntradayEodJobMessage` per symbol/date.

### Manual historical backfill

The existing Phase 7 manual trigger API is reused; there is no separate backfill pipeline.

Accepted runtime parameter shapes for `SYNC_INTRADAY_EOD`:

```json
{ "tradingDate": "2026-09-07" }
```

or:

```json
{ "startDate": "2026-09-01", "endDate": "2026-09-07" }
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
- Each symbol partition owns its immutable versions and READY pointer; publishing one
  symbol cannot move another symbol's pointer.
- Manual backfill uses the same immutable publisher as scheduled ingestion.
- Reconciliation rejection happens before publication.
- Candidate data/version-manifest validation happens before `READY.json` replacement.
- Failure before READY replacement preserves the prior READY pointer.
- Objects published under the former shared provider/exchange/date READY layout require
  republishing into symbol-level partitions; no consumer compatibility fallback is
  provided.

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

Use the complete checklist in [`docs/plans/013-intraday-eod.md`](013-intraday-eod.md). It covers exchange/provider scope, single-date/range backfill, future-date rejection, deterministic reruns, completeness, reconciliation, duplicate/correction behavior, immutable READY preservation, partition identity, Kafka contracts, timestamp/date semantics, scheduler behavior, manual-trigger allow-list, and affected Nx/project checks.

P9-I1 must remain `verification_pending` and `last_verified_commit` must remain `null` until the owner runs and records the approved checks.

## Intentional technical debt

- No Vietnam exchange holiday/calendar-version model; weekday-only scheduling/backfill may safely fail on market holidays.
- No session-segment validation.
- No dedicated large-backfill queue/backpressure policy beyond the 31-calendar-day request bound.
- No automatic market-calendar correction-window enforcement.
- No bars/features/sectors/realtime work in P9-I1.

## Later increments

P9-I2 may add deterministic 1m/5m/15m bars and reusable intraday features after P9-I1 is independently owner-verified and explicitly reactivated. P9-I3 may then add sector aggregation/lineage under its own gate. Neither is activated by this implementation.


## Field/DTO Inventory and Bounded Delivery — 2026-10-07

Design inventory, not a claim that fields are missing from source or already implemented. [Cross-plan register](../reference/002-planned-field-dto-impact.md) defines ADD/REUSE/SEMANTIC/DERIVED/UNRESOLVED and LOW/MEDIUM/HIGH impact. Exact names/types/nullability/defaults/transport must be reconciled with source before code or migration. Existing statuses, dependencies and owner gates remain unchanged.

| Surface | Field/DTO change | Impact and behavior |
| --- | --- | --- |
| IntradayEodJobMessage | symbol, exchange, tradingDate, provider plus existing execution/work identity | HIGH producer/consumer boundary; reuse source where present, freeze types/date timezone; one symbol/date per command. |
| Manual runtime parameters | tradingDate OR startDate/endDate | MEDIUM API validation/fan-out; historical weekdays, bounded range, existing audited allow-list. |
| Trade rows | trading_date, timestamp UTC, exchange, symbol, provider_id, price, volume, trade_value, match_type | HIGH persisted analytical contract; deterministic duplicates, conflicting provider IDs rejected. |
| Manifest evidence | completeness, reconciliation.status/close/volume/value, sourceExecutionId, normalizationVersion, rowCount, objectCount, dataVersion, path | HIGH publication behavior; immutable per-symbol partition and READY-last; physical path remains storage metadata, not a routing field. |

Small tasks: reconcile existing source/evidence → command/date validation → normalization → canonical EOD reconciliation → immutable publication. This is verification_pending capability, not permission to rebuild all tasks; retain already-present implementations. Bars/features and live realtime remain deferred.

## Outcome

Completed-session normalized trades and exact reconciliation evidence are available through independently addressable symbol/date READY partitions, subject to canonical P9-I1 verification_pending gates.

## Dataset Outputs

Normalized trade Parquet per provider/exchange/trading_date/symbol, using the canonical field list and existing shared storage builder. No bars/features/sector dataset is added.

## Metadata Outputs

Immutable version manifest and per-symbol READY pointer with completeness/reconciliation evidence and content-derived dataVersion. Preserve prior READY on rejected/failed publication.

## Algorithm Feature Outputs

DIRECT canonical trade/time/price/volume/value/identity facts; later intraday features remain deferred.

## Algorithms Unlocked

Exact-date Analyzer intraday confirmation can consume verified READY trades. No live-provider runtime or bars implementation is activated.

## Contract Impact

| Area | Decision |
| --- | --- |
| Kafka/protobuf | Reuse the declared IntradayEodJobMessage boundary; Java producer/Python consumer must agree on symbol/exchange/provider/tradingDate and execution identity. No Proto3 migration implied. |
| Object-storage JSON manifest | Completeness, reconciliation and immutable per-symbol identity are part of the published contract. |
| Storage ownership | Ingestor remains sole normalized-trade producer; per-symbol partition replaces former shared-date publication. No compatibility fallback is claimed. |
| Public APIs | Existing audited manual trigger gains validated single-date/range parameters; no independent backfill pipeline. |
| Configuration | Existing exchange/provider and manual-trigger allow-list boundary; no automatic widening of deployment permission. |

Blast radius: Platform date fan-out/manual validation, Ingestor provider/normalization/reconciliation/publication, shared py-common storage/fixtures, Analyzer readiness/lineage consumers, and configuration are applicable. Query Service/Console have no new intraday UI in this bounded slice; operations must understand provider completeness failures. Reconcile exact source/test coverage before completion.

## Repository Guidance Updates

At implementation synchronize Kafka/data-lake/job-flow/intraday-flow docs and Platform/Ingestor/shared-library READMEs; review AGENTS.md/CLAUDE.md/.roo/rules for changed workflow. This inventory adds no runtime agent/tool rule.

## Verification

The full Owner verification boundary remains NOT RUN for this review. Existing recorded historical evidence is unchanged. Reconcile producer/consumer, date/fan-out, provider completeness, duplicate/correction, canonical reconciliation, per-symbol identity, immutable publication failure and exact source/coverage checks; no new check result is inferred.

## Acceptance Criteria

All Approved decisions, execution validation, canonical normalization/reconciliation and Idempotency and failure semantics above remain mandatory. P9-I1 stays verification_pending until its canonical owner-required verification is complete. No relaxed criterion or later feature is introduced by task splitting.
