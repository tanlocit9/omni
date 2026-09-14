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
| status                  | verification_pending                                      |
| priority                | medium                                                    |
| depends_on              | []                                                        |
| blocks                  | [P9-I2, P9-I4, P9-I5]                                     |
| owned_modules           | [contracts, apps/core, apps/ingestor, libs/py-common]     |
| execution_mode          | autonomous                                                |
| requires_owner_decision | false                                                     |
| pr                      | null                                                      |
| last_verified_commit    | null                                                      |
| implementation_commit   | 9ee71dc7fe04489125d8aa0b5e662aae5aef462c                  |

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
- Following EOD ownership, `(provider, exchange, trading_date, symbol)` identifies one independently READY-addressable partition at `intraday/trades/provider={provider}/exchange={exchange}/trading_date={trading_date}/symbol={symbol}/trades.parquet`.
- Immutable publication writes each symbol's candidate data and version manifest before replacing that symbol's `READY.json`, preserving its previous pointer when validation/reconciliation fails before publication.
- Objects under the former shared provider/exchange/date READY layout require republishing into symbol-level partitions and are not compatibility inputs.
- Focused source tests were added/updated for exchange fan-out, scheduled weekday behavior, single-date backfill, bounded range fan-out/weekend skipping, timestamp normalization, conflicting duplicate IDs, and EOD reconciliation READY/REJECTED behavior.

Implementation commits after the original `328450d` source slice include `022b855` (backfill date fan-out), `880fb24` (manual backfill parameter validation), `6aa6f13` (exchange alignment and EOD reconciliation), `0152ddc` (canonical EOD storage wiring), `43c8d0c` (reconciliation source tests), and `9ee71dc` (scheduled/backfill producer tests). These are implementation evidence only; none are verification evidence.

### Manual verification checklist

Owner verification was authorized and run on 2026-09-12. The local Nx project
checks passed, but runtime, deployment, PR, and CI items remain unresolved as
shown below.

| Status  | Check                                                                              | Evidence to inspect / run                                                                                                                                                                                                                                                      |
| ------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| NOT RUN | Exchange scope is consistently HOSE/HNX/UPCOM with VCI                             | `JobDefinitionConfig.java`; `SyncIntradayEodJobProducer.java`; `apps/ingestor/app/handlers/intraday_eod.py`; `SyncIntradayEodJobProducerTest.dispatchesActiveSymbolsForAllConfiguredVietnamExchanges`                                                                          |
| NOT RUN | Provider and message exchange validation reject unsupported/mismatched input       | `apps/ingestor/app/handlers/intraday_eod.py` (`_SUPPORTED_EXCHANGES`, provider check, symbolKey/message exchange check)                                                                                                                                                        |
| NOT RUN | Single-date historical backfill uses the same pipeline                             | `ManualJobTriggerService.java`; `SyncIntradayEodJobProducerTest.manualSingleDateBackfillUsesRequestedHistoricalDate`                                                                                                                                                           |
| NOT RUN | Bounded range backfill is limited and deterministic                                | `ManualJobTriggerService.java` (`MAX_INTRADAY_BACKFILL_DAYS = 31`); `SyncIntradayEodJobProducerTest.manualRangeBackfillSkipsWeekendAndFansOutSamePipeline`                                                                                                                     |
| NOT RUN | Future dates are rejected                                                          | `ManualJobTriggerService.validateIntradayBackfill`; add/run the service-level invalid-date assertions when executing Platform tests                                                                                                                                            |
| NOT RUN | Scheduled behavior remains latest completed weekday and cron is unchanged          | `JobDefinitionConfig.java`; `SyncIntradayEodJobProducerTest.usesProjectionExchangeAndLatestCompletedWeekday`                                                                                                                                                                   |
| NOT RUN | Deterministic rerun/idempotency for same provider/symbol/date                      | `SyncIntradayEodJobProducer.java` symbol/date Kafka key; `normalize_intraday_trades`; `ImmutableDatasetPublisher` SHA-256 `dataVersion`; existing `libs/py-common/tests/storage/test_immutable_publication.py`                                                                 |
| NOT RUN | Timestamp local-date validation and UTC persistence                                | `apps/ingestor/app/handlers/intraday_eod.py`; `test_intraday_eod.py::test_normalizes_redacted_vci_fixture`; `test_rejects_provider_timestamp_on_different_local_date`                                                                                                          |
| NOT RUN | Duplicate/correction input semantics                                               | `normalize_intraday_trades`; `test_rejects_conflicting_duplicate_provider_id`; exact duplicates collapse by `provider_id`                                                                                                                                                      |
| NOT RUN | Completeness outcome blocks empty/unavailable symbol result                        | `process_intraday_eod_message` rejects empty normalized result; manifest records `completeness=COMPLETE` only after non-empty normalized data and reconciliation                                                                                                               |
| NOT RUN | EOD reconciliation READY/WARNING/REJECTED behavior                                 | `libs/py-common/py_common/intraday_reconciliation.py`; `reconcile_against_eod`; `test_reconciliation_ready_against_canonical_eod`; `test_reconciliation_rejects_large_difference`                                                                                              |
| NOT RUN | Exact requested EOD date is required                                               | `reconcile_against_eod`; `test_reconciliation_requires_exact_eod_trading_date`                                                                                                                                                                                                 |
| NOT RUN | Rejected candidate cannot replace READY                                            | `process_intraday_eod_message` performs reconciliation before `publisher.publish`; `ImmutableDatasetPublisher.publish` writes READY last; `libs/py-common/tests/storage/test_immutable_publication.py`                                                                         |
| NOT RUN | Partition/object identity is deterministic and symbol-level                        | `StockDataPaths.intraday_trades`; `configs/shared/s3-paths.yaml`; `ImmutableDatasetPublisher`; expected logical path `intraday/trades/provider=vci/exchange=<exchange>/trading_date=YYYY-MM-DD/symbol=<symbol>/trades.parquet`; verify two symbols own distinct READY pointers |
| NOT RUN | Former shared date-level publications are rejected until republished               | `docs/data/002-data-lake.md`; Analyzer exact-partition resolver source tests; no compatibility fallback                                                                                                                                                                        |
| NOT RUN | Kafka topic/message contract remains shared across Platform/Ingestor               | `configs/shared/topics.yaml`; `IntradayEodJobMessage.java`; `apps/ingestor/app/messaging/messages.py`; `apps/ingestor/app/messaging/consumer.py`; `docs/data/001-kafka-contracts.md`                                                                                           |
| NOT RUN | Manual trigger allow-list explicitly enables the intraday job in deployment config | `ManualTriggerProperties.java`; runtime `app.scheduler.manual-trigger.allow-list` must include `SYNC_INTRADAY_EOD:VCI` (or the exact definition UUID) before operator backfill is usable                                                                                       |
| PASS    | Focused Ingestor tests                                                             | 2026-09-12: `nx run ingestor:test` passed (27 tests), including the intraday and message suites                                                                                                                                                                                |
| PASS    | Focused py-common tests                                                            | 2026-09-12: `nx run py-common:test` passed (104 tests), including reconciliation, immutable-publication, and path-builder coverage                                                                                                                                             |
| PASS    | Focused Platform tests                                                             | 2026-09-12: `nx run platform:test` passed, including the configured Platform test suite                                                                                                                                                                                        |
| PASS    | Affected Nx/project lint/build/test checks                                         | 2026-09-12 local project checks passed: py-common lint/test/build, Ingestor lint/test/build, Analyzer lint/test/build, and Platform test/build. No broader `nx affected`, CI, or runtime integration check was run.                                                            |

### Acceptance criteria pending external and runtime verification

- D9-1 through D9-6 remain recorded as approved with contract/fixture evidence.
- Scheduled and manual backfill use the same ingestion contract and publication boundary.
- Backfill supports one historical weekday or a bounded historical range without changing cron cadence.
- Provider timestamps convert to `Asia/Ho_Chi_Minh`, match the requested local date, and persist normalized in UTC.
- Every published candidate has non-empty normalized trades and an explicit EOD reconciliation result.
- Each `(provider, exchange, trading_date, symbol)` partition has an independent READY pointer.
- Reconciliation rejection occurs before immutable publication and therefore cannot replace the previous READY pointer.
- Re-running identical normalized bytes produces the same content-derived dataVersion.
- Former shared date-level publications are republished before use and are never inferred as symbol-ready.
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
| pr                      | null                                            |
| last_verified_commit    | null                                            |

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

## Increment P9-I4 — Intraday-confirmed daily signal

Owner decision (2026-09-11): implement this bounded follow-up while P9-I1 remains `verification_pending`. This does not reactivate P9-I2/P9-I3 or permit realtime scope.

| Field                   | Value                                       |
| ----------------------- | ------------------------------------------- |
| id                      | P9-I4                                       |
| title                   | Intraday-confirmed daily signal             |
| status                  | in_progress                                 |
| priority                | high                                        |
| depends_on              | [P9-I1 implementation source, P8-I4 source] |
| blocks                  | []                                          |
| owned_modules           | [apps/analyzer, apps/core, docs]            |
| execution_mode          | autonomous                                  |
| requires_owner_decision | false                                       |
| pr                      | null                                        |
| last_verified_commit    | null                                        |

Goal: use exact-date completed-session VCI trades to confirm or suppress the existing two-component daily candidate under `CONFIRMED_TREND_EQUALS_V2_INTRADAY`, preserving the public strategy key, V1 history, Analyzer ownership, and existing notification flow.

Supporting detail and the verification checklist are in [`docs/plans/021-intraday-confirmed-rules.md`](../../docs/plans/021-intraday-confirmed-rules.md). Local Analyzer and Platform checks passed on 2026-09-12; PR/commit, CI, runtime object-storage/provider, notification-runtime, and production evidence remain unresolved.

## Increment P9-I5 — VCI health metrics and basic visibility

Owner priority decision (2026-09-14): measure the existing bounded VCI processing path before implementing P8-I5 Notification Outbox, then assess VCI capacity and consider multi-provider ingestion only as later, separately approved work.

| Field                   | Value                                                                            |
| ----------------------- | -------------------------------------------------------------------------------- |
| id                      | P9-I5                                                                            |
| title                   | VCI health metrics and basic visibility                                          |
| status                  | pending                                                                          |
| priority                | critical                                                                         |
| depends_on              | [P9-I1]                                                                          |
| blocks                  | []                                                                               |
| owned_modules           | [apps/ingestor, apps/core, apps/query-service, apps/omni-console, configs, docs] |
| execution_mode          | autonomous                                                                       |
| requires_owner_decision | false                                                                            |
| pr                      | null                                                                             |
| last_verified_commit    | null                                                                             |

### Goal

Instrument the existing P9-I1 VCI completed-session workflow sufficiently to make provider and pipeline health observable without changing how market data is collected or increasing provider pressure.

### Outcome

Operators can inspect average, P50, and P95 VCI call duration; processing, storage, and child end-to-end duration; throughput; backlog; failures; and HTTP 429 responses through an existing health endpoint or existing dashboard surface. Metrics use bounded labels and distinguish provider wait from local processing and persistence.

### Dataset Outputs

No analytical dataset output.

### Metadata Outputs

No dataset metadata output.

### Algorithm Feature Outputs

No direct algorithm feature output.

### Algorithms Unlocked

No analytical algorithm is unlocked. The measurements support a later owner capacity assessment without pre-deciding a concurrency or provider strategy.

### Scope and approach

1. Measure each existing VCI request from dispatch to response and aggregate average, P50, and P95 duration over a documented bounded window.
2. Measure normalized processing time, storage/publication time, and child end-to-end duration independently so provider latency is not conflated with local work.
3. Count completed work and normalized rows for throughput; expose queued/in-flight work or oldest pending age for backlog; count failures by bounded sanitized category; count provider HTTP 429 responses separately.
4. Reuse an existing health endpoint or dashboard. Do not build a WebSocket feed, Kafka/live metrics collector, or new always-on collection service.
5. Preserve the existing sequential/bounded provider behavior. Do not add concurrency to evade rate limits, provider rotation, fallback, or source mixing.

### Contract Impact

| Contract area                        | Decision                                                                                               |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| Kafka or service-to-service protobuf | Unchanged. No topic, message, producer, consumer, or Proto3 change.                                    |
| Object-storage JSON manifests        | Unchanged. Existing immutable-version-before-READY and lineage semantics remain intact.                |
| Storage paths or dataset ownership   | Unchanged. No dataset, path, partition, writer, or ownership change.                                   |
| Public Java or Python APIs           | Unchanged. Instrumentation remains internal and visibility reuses an existing operator-facing surface. |
| Configuration or environment         | Future implementation may add bounded metric-window settings only; this plan-only update changes none. |

### Repository Guidance Updates

Review `AGENTS.md`, `CLAUDE.md`, `.roo/rules/`, the existing health/dashboard documentation, and the P9-I1 flow during implementation. No agent-guidance update is required for this plan-only change because collection, contract, storage, and verification rules are unchanged.

### Verification

Build, test, lint, and format are not run for this plan-only update. Implementation verification must use defined Nx targets after owner approval and include deterministic timing aggregation, metric-label bounds, 429/failure classification, backlog/throughput behavior, endpoint or dashboard access, and regressions proving unchanged P9-I1 collection/publication behavior.

### Acceptance Criteria

- Average, P50, and P95 VCI call durations are available for a documented bounded aggregation window.
- Processing, storage, and end-to-end child durations are separately observable.
- Throughput, backlog, failures, and HTTP 429 responses are visible with bounded, non-sensitive labels.
- An existing health endpoint or dashboard presents the measurements without introducing a competing telemetry runtime.
- No WebSocket provider runtime, Kafka tick transport, always-on/live collector, provider fallback/rotation, or multi-provider ingestion is implemented.
- No concurrency is added to bypass or mask provider rate limits.
- No capacity conclusion is encoded in P9-I5; capacity assessment follows measured evidence and remains deferred until separately scheduled.
- Required tests, approved Nx checks, documentation, PR/commit, and CI evidence are recorded before completion.

### Stop Conditions

Stop if implementation requires a new cross-service contract, physical storage path exposure, a new data writer, an always-on collector, unbounded metric cardinality, provider credentials in telemetry, increased request concurrency, provider fallback, or owner approval of a capacity/product decision.
