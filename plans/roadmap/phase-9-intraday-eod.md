# Phase 9 — Intraday EOD

## Goal

Introduce post-close intraday processing with the same contract, manifest, lineage, and single-writer guarantees established in earlier phases.

## Increment P9-I1 — Post-close intraday ingestion contracts and normalization

MVP decision (2026-09-05): all Phase 9 increments were deferred to [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md).

Owner reactivation decision (2026-09-10): P9-I1 alone is reactivated for a bounded slice using the vnstock Python package with VCI, completed sessions for HOSE, HNX, and UPCOM, all active symbols on each configured exchange, and normalized trades only. D9-1 through D9-6 are approved in [`docs/plans/013-intraday-eod.md`](../../docs/plans/013-intraday-eod.md). P9-I2 and P9-I3 remain superseded; bars, features, sectors, Console, and realtime coupling are excluded.

| Field                   | Value                                                     |
| ----------------------- | --------------------------------------------------------- |
| id                      | P9-I1                                                     |
| title                   | Post-close intraday ingestion contracts and normalization |
| status                  | pending_owner_verification                                |
| priority                | medium                                                    |
| depends_on              | []                                                        |
| blocks                  | [P9-I2]                                                   |
| owned_modules           | [contracts, apps/core, apps/ingestor, libs/py-common]     |
| execution_mode          | autonomous                                                |
| requires_owner_decision | false                                                     |
| pr                      | null                                                      |
| last_verified_commit    | null                                                      |
| implementation_commit   | 9ee71dc7fe04489125d8aa0b5e662aae5aef462c                |

Goal: ingest and normalize completed-session intraday trades using the approved provider, local-date validation, completeness/reconciliation, deterministic immutable publication, and bounded manual historical backfill.

### Implementation evidence

Source implementation now includes:

- Platform `SYNC_INTRADAY_EOD` seed/message/producer for the shared HOSE/HNX/UPCOM universe.
- Normal scheduled dispatch still resolves the latest completed weekday.
- Existing Phase 7 manual trigger boundary now accepts either one historical `tradingDate` or a bounded `startDate`/`endDate` range for `SYNC_INTRADAY_EOD`; the range is limited to 31 calendar days and future dates are rejected.
- Manual backfill and scheduled runs both fan out through the same `IntradayEodJobMessage` and Ingestor processing path. Message keys include symbol plus trading date so distinct backfill dates are deterministic work items.
- Ingestor accepts VCI for HOSE/HNX/UPCOM, normalizes provider timestamps to a local trading-date check, persists timestamps in UTC, collapses exact duplicate provider IDs, and rejects conflicting duplicates.
- Before publication, Ingestor reads the canonical EOD symbol Parquet for the requested date and reconciles final price, total volume, and total value using `py_common.intraday_reconciliation`. `REJECTED` candidates stop before publication; `READY` and `WARNING` outcomes are recorded in the immutable manifest.
- Empty/unavailable intraday data and missing/ambiguous canonical EOD rows are terminal errors for the symbol/date work item.
- Immutable publication still writes candidate data and version manifest before replacing `READY.json`, preserving the previous READY pointer when validation/reconciliation fails before publication.
- Focused source tests were added/updated for exchange fan-out, scheduled weekday behavior, single-date backfill, bounded range fan-out/weekend skipping, timestamp normalization, conflicting duplicate IDs, and EOD reconciliation READY/REJECTED behavior.

Implementation commits after the original `328450d` source slice include `022b855` (backfill date fan-out), `880fb24` (manual backfill parameter validation), `6aa6f13` (exchange alignment and EOD reconciliation), `0152ddc` (canonical EOD storage wiring), `43c8d0c` (reconciliation source tests), and `9ee71dc` (scheduled/backfill producer tests). These are implementation evidence only; none are verification evidence.

### Manual verification checklist

Owner instruction: **do not run verification automatically**. Every item below remains `NOT RUN` until the owner verifies it.

| Status | Check | Evidence to inspect / run |
| ------ | ----- | ------------------------- |
| NOT RUN | Exchange scope is consistently HOSE/HNX/UPCOM with VCI | `JobDefinitionConfig.java`; `SyncIntradayEodJobProducer.java`; `apps/ingestor/app/handlers/intraday_eod.py`; `SyncIntradayEodJobProducerTest.dispatchesActiveSymbolsForAllConfiguredVietnamExchanges` |
| NOT RUN | Provider and message exchange validation reject unsupported/mismatched input | `apps/ingestor/app/handlers/intraday_eod.py` (`_SUPPORTED_EXCHANGES`, provider check, symbolKey/message exchange check) |
| NOT RUN | Single-date historical backfill uses the same pipeline | `ManualJobTriggerService.java`; `SyncIntradayEodJobProducerTest.manualSingleDateBackfillUsesRequestedHistoricalDate` |
| NOT RUN | Bounded range backfill is limited and deterministic | `ManualJobTriggerService.java` (`MAX_INTRADAY_BACKFILL_DAYS = 31`); `SyncIntradayEodJobProducerTest.manualRangeBackfillSkipsWeekendAndFansOutSamePipeline` |
| NOT RUN | Future dates are rejected | `ManualJobTriggerService.validateIntradayBackfill`; add/run the service-level invalid-date assertions when executing Platform tests |
| NOT RUN | Scheduled behavior remains latest completed weekday and cron is unchanged | `JobDefinitionConfig.java`; `SyncIntradayEodJobProducerTest.usesProjectionExchangeAndLatestCompletedWeekday` |
| NOT RUN | Deterministic rerun/idempotency for same provider/symbol/date | `SyncIntradayEodJobProducer.java` symbol/date Kafka key; `normalize_intraday_trades`; `ImmutableDatasetPublisher` SHA-256 `dataVersion`; existing `libs/py-common/tests/storage/test_immutable_publication.py` |
| NOT RUN | Timestamp local-date validation and UTC persistence | `apps/ingestor/app/handlers/intraday_eod.py`; `test_intraday_eod.py::test_normalizes_redacted_vci_fixture`; `test_rejects_provider_timestamp_on_different_local_date` |
| NOT RUN | Duplicate/correction input semantics | `normalize_intraday_trades`; `test_rejects_conflicting_duplicate_provider_id`; exact duplicates collapse by `provider_id` |
| NOT RUN | Completeness outcome blocks empty/unavailable symbol result | `process_intraday_eod_message` rejects empty normalized result; manifest records `completeness=COMPLETE` only after non-empty normalized data and reconciliation |
| NOT RUN | EOD reconciliation READY/WARNING/REJECTED behavior | `libs/py-common/py_common/intraday_reconciliation.py`; `reconcile_against_eod`; `test_reconciliation_ready_against_canonical_eod`; `test_reconciliation_rejects_large_difference` |
| NOT RUN | Exact requested EOD date is required | `reconcile_against_eod`; `test_reconciliation_requires_exact_eod_trading_date` |
| NOT RUN | Rejected candidate cannot replace READY | `process_intraday_eod_message` performs reconciliation before `publisher.publish`; `ImmutableDatasetPublisher.publish` writes READY last; `libs/py-common/tests/storage/test_immutable_publication.py` |
| NOT RUN | Partition/object identity is deterministic | `StockDataPaths.intraday_trades`; `configs/shared/s3-paths.yaml`; `ImmutableDatasetPublisher`; expected logical path `intraday/trades/provider=vci/exchange=<exchange>/trading_date=YYYY-MM-DD/<symbol>.parquet` |
| NOT RUN | Kafka topic/message contract remains shared across Platform/Ingestor | `configs/shared/topics.yaml`; `IntradayEodJobMessage.java`; `apps/ingestor/app/messaging/messages.py`; `apps/ingestor/app/messaging/consumer.py`; `docs/data/001-kafka-contracts.md` |
| NOT RUN | Manual trigger allow-list explicitly enables the intraday job in deployment config | `ManualTriggerProperties.java`; runtime `app.scheduler.manual-trigger.allow-list` must include `SYNC_INTRADAY_EOD:VCI` (or the exact definition UUID) before operator backfill is usable |
| NOT RUN | Focused Ingestor tests | inspect project targets, then run the focused intraday/message tests including `apps/ingestor/tests/test_intraday_eod.py` and `apps/ingestor/tests/test_messages.py` |
| NOT RUN | Focused py-common tests | inspect project targets, then run reconciliation, immutable-publication, and path-builder tests |
| NOT RUN | Focused Platform tests | inspect project targets, then run `SyncIntradayEodJobProducerTest`, `JobDefinitionConfigTest`, messaging/consumer contract tests, and `ManualJobTriggerServiceTest` |
| NOT RUN | Affected Nx/project lint/build/test checks | inspect `project.json` and affected project targets first; run only the owner-approved affected checks; do not infer a full-repository verification requirement |

### Acceptance criteria pending owner verification

- D9-1 through D9-6 remain recorded as approved with contract/fixture evidence.
- Scheduled and manual backfill use the same ingestion contract and publication boundary.
- Backfill supports one historical weekday or a bounded historical range without changing cron cadence.
- Provider timestamps convert to `Asia/Ho_Chi_Minh`, match the requested local date, and persist normalized in UTC.
- Every published candidate has non-empty normalized trades and an explicit EOD reconciliation result.
- Reconciliation rejection occurs before immutable publication and therefore cannot replace the previous READY pointer.
- Re-running identical normalized bytes produces the same content-derived dataVersion.
- P9-I1 does not activate bars, reusable intraday features, sectors, Console-specific UI work, or realtime coupling.

### Intentional technical debt / non-blocking exclusions

- No exchange holiday/calendar-version lineage. Scheduled and range backfill logic skips weekends only; a weekday market holiday can still produce unavailable data and fail safely.
- No session-segment validation (ATO/continuous/ATC boundaries) in P9-I1.
- No automatic retry/backpressure policy specifically for large backfills beyond the 31-calendar-day request bound.
- Correction-window age enforcement remains a provider/operational policy; P9-I1 preserves immutable READY-last semantics but does not infer market-calendar correction eligibility.
- P9-I2/P9-I3 remain separately gated for bars/features/sector aggregation.

## Increment P9-I2 — Intraday bars, reusable features, and manifests

| Field                   | Value                                           |
| ----------------------- | ----------------------------------------------- |
| id                      | P9-I2                                           |
| title                   | Intraday bars, reusable features, and manifests |
| status                  | superseded                                      |
| priority                | medium                                          |
| depends_on              | [P9-I1 completed and separately reactivated]    |
| blocks                  | [P9-I3, P10-I2]                                 |
| owned_modules           | [apps/analyzer, libs/py-common]                 |
| execution_mode          | autonomous                                      |
| requires_owner_decision | false                                           |
| pr                      | null                                             |
| last_verified_commit    | null                                             |

Goal: build canonical 1m bars, deterministic 5m/15m aggregates, and reusable intraday symbol features.

Acceptance criteria: repeated builds from same input produce identical bars/version identity, partial-session boundaries are tested, feature vocabulary matches planned realtime consumers, and each partition publishes READY manifests with lineage.

## Increment P9-I3 — Sector aggregation and lineage publication

| Field                   | Value                                      |
| ----------------------- | ------------------------------------------ |
| id                      | P9-I3                                      |
| title                   | Sector aggregation and lineage publication |
| status                  | superseded                                 |
| priority                | medium                                     |
| depends_on              | [P9-I2 completed, P1-I3]                   |
| blocks                  | [P10-I1]                                   |
| owned_modules           | [apps/analyzer, libs/py-common]            |
| execution_mode          | autonomous                                 |
| requires_owner_decision | false                                      |
| pr                      | null                                       |
| last_verified_commit    | null                                       |

Goal: build sector aggregates only from READY symbol partitions with exact lineage.

Acceptance criteria: sector aggregation uses one logical writer, all inputs are READY, exact trade/bar input versions are recorded, and failed corrected-session rebuild preserves previous READY.
