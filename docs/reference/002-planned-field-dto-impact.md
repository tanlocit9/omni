# Planned Field and DTO Impact Inventory

Status: Design review, 2026-10-07. Baseline: docs/refactor at 1a36086d85104ef38c3dd8abc64147d5d80bb649.
This is a cross-plan reference, not a new implementation phase. The [increment registry](../plans/roadmap/implementation-increments.md) remains the scheduling owner. No runtime/source completeness or executed verification is asserted.

## Scope and interpretation

Reviewed delivery-bearing plans 011, 012, 013, 014, 021, 022, 023, 024, 027, 028 and 029, their declared fields/contracts, and current registry ownership. Older completed, superseded, compatibility, deployment and tool-adoption plans remain historical or deferred; this review does not reopen their migrations. Inspect the actual source/schema before treating any planned field as absent or requiring another migration.

Change kinds: REUSE (existing identity/meaning prescribed by the plan), ADD (new contract surface in the design), SEMANTIC (meaning/behavior changes), DERIVED (read projection), UNRESOLVED (name/type/transport/storage not yet approved). A candidate DTO name is a design label, not an existing class or an approved wire contract.

Impact: LOW = presentation/local read projection; MEDIUM = additive API/configuration or bounded local behavior; HIGH = cross-service contract, persisted schema, business-rule, identity, transaction, retry/offset or ownership change. Impact is independent of execution priority and historical verification.

Every implementation slice records types, nullability, units/timezone, defaults, producer/consumer, identity, authorization, persistence, compatibility and rollback. Optional timestamps without evidence are null/UNKNOWN, never synthesized. Transport and persisted names may differ only through an explicit mapping.

## Plan impact and delivery boundaries

| Owner plan | Fields / DTO surface | Kind and impact | Behavior change / minimum boundary |
| --- | --- | --- | --- |
| [011](../plans/011-telegram-notification-format-modernization.md), P8-I1/I2 | NotificationRequest classification through NotificationKind; optional typed content; existing channel/type/severity/metadata | ADD/UNRESOLVED, MEDIUM | Renderer selection, HTML safety, length and sound policy. First classify without changing delivery; renderer families and routing cutover are separate slices. No Kafka expansion inferred. |
| [012](../plans/012-confirmed-trend-equals-mvp.md), P8-I4 | modelVersion; components[].strategy/signal/mappedValue/score/signalDate/reasonCodes; signal-history strategy filter and selected-strategy response | ADD + SEMANTIC, HIGH | New combined decision/model and default query/notification strategy. Separate pure combiner, persistence, read API, UI and notification policy. Component metadata is suggested, exact types remain to be frozen. |
| [013](../plans/013-intraday-eod.md), P9-I1 | IntradayEodJobMessage symbol/exchange/tradingDate/provider; tradingDate or startDate/endDate request; normalized trade fields and reconciliation manifest | ADD/REUSE + SEMANTIC, HIGH | Dated fan-out, conflict rejection, immutable per-symbol READY publication. Source/evidence reconciliation first; do not reimplement already-present behavior. Bars/features stay out. |
| [014](../plans/014-realtime-per-tick.md), P10 deferred | Canonical MarketTick identity, event/received time, numeric price/size, provider-dependent trade identity; archive/reconciliation evidence | ADD/UNRESOLVED, HIGH | Tick dedup/replay/archive and eventual live collector have different failure/ownership boundaries. Retain source evidence; provider discovery does not authorize live transport. |
| [021](../plans/021-intraday-confirmed-rules.md), P9-I4 | IntradayConfirmationFacts; intradayConfirmation; inputDataVersions; modelVersion=CONFIRMED_TREND_EQUALS_V2_INTRADAY | ADD + SEMANTIC, HIGH | Intraday confirms/suppresses existing daily direction; may yield NO_DECISION. Derivation, versioned metadata persistence, dependency gate and notification presentation are separate slices. |
| [022](../plans/022-notification-outbox.md), P8-I5 | notification_outbox_messages identity/provider/channel/kind/schema_version/payload/deduplication_key/status/claim-retry/sent_at; manual accepted-delivery response | ADD + SEMANTIC, HIGH | HTTP 202 means durable acceptance, not provider delivery. Enqueue/ack transaction, retry dispatcher, digest pagination and producer cutover must be separately reviewable. |
| [023](../plans/023-dependency-aware-outbox-dispatch.md), P4-I3 | DependencyRequest domain/jobDefinitionId/executionId/parentExecutionId/workType/workKey/runKey; DependencyDecision READY/WAITING/BLOCKED/retryAt/reasons | ADD/REUSE + SEMANTIC, HIGH | Acceptance becomes independent of readiness; dispatcher gates publication. Preserve exact lineage/FIFO/fencing; migration/status aggregation and registry adaptation are separate slices. |
| [024](../plans/024-polyglot-correlation-structured-logging.md), P11 deferred | correlationId/requestId headers and persisted diagnostic context; structured failure envelope | ADD/REUSE, MEDIUM locally / HIGH cross-service | Diagnostics only, never ownership/idempotency. Logger adapters, HTTP propagation, DB/outbox context, Kafka propagation and collector deployment are separate. No full rollout prerequisite for a correctness fix. |
| [027](../plans/027-concurrent-workers-and-writer-batching.md), P12 deferred | writeMode/writeKey; versioned write-intent contract with intentId/operation/logical output/candidate/execution identity; applyStatuses internal API | ADD + SEMANTIC, HIGH | Offset ownership, parallel admission, bulk transactions and a new sole writer are independent changes. Keep serial offset-safety fix distinct from measured concurrency; writer cutover is last. |
| [028](../plans/028-reusable-date-range-backfill.md), proposed | jobKey/dependencyKey graph; businessDate/executionMode/runKey/backfillRequestId; request range/dryRun/requestedBy and classified preview | ADD + SEMANTIC, HIGH | Replaces graph ownership and introduces historical execution identity. Graph migration, read-only preview, bounded enqueue, dated producer rollout and recovery are separate; no automatic promotion from Data Health. |
| [029](../plans/029-operator-trust-console.md), P13 active | Stage timing evidence; execution/aggregate read DTOs; guarded ETA; bounded health scan request/result/cache; idle observation proposal | ADD/DERIVED + SEMANTIC, LOW–HIGH by slice | Timing transport and startedAt migration are HIGH; metrics/scanner APIs MEDIUM; fixed UI LOW. Use the detailed slice table below and in Plan 029. |

## Phase 13 field/DTO register

Names below are candidate DTO/field spellings unless the plan already names them. Freeze exact wire names and types in P13-I1/P13-I3 contract design; no unapproved enum/status migration is implied.

| Surface / owner | Fields or facts | Kind / impact | Persistence and behavior |
| --- | --- | --- | --- |
| Existing execution / Platform | triggeredAt, executionId, parentExecutionId, workType, workKey | REUSE | Preserve request/audit and work identity semantics. |
| Existing outbox / Platform | publishedAt; existing claim, availability, attempt and dependency evidence | REUSE | Use durable evidence; do not duplicate columns merely for a dashboard. |
| Worker timing evidence / Python → Platform | candidate processingStartedAt, processingFinishedAt; execution identity; event identity/order policy if separate events are chosen | ADD/UNRESOLVED, HIGH | Exact event/header/status route unresolved. Worker processing completion does not prove terminal-status publication or Platform application. |
| Timing application / Platform | candidate statusReceivedAt, statusAppliedAt; preparation/dependency/receipt evidence only where authoritative | ADD/UNRESOLVED, HIGH | Decide whether existing transaction/audit evidence suffices before adding DB columns. Duplicate, delayed and out-of-order evidence cannot regress terminal state. |
| Existing startedAt / all readers/writers | Change from dispatch preparation to actual worker processing start | SEMANTIC, HIGH | Separate coordinated migration/cutover. Additive evidence may ship first; historical rows stay legacy/UNKNOWN. Do not silently reinterpret existing values. |
| ExecutionStageView / candidate Platform read DTO | stage, authoritative timestamps, snapshotTime, evidenceCompleteness; candidate queue/processing/endToEnd/statusApply durations | DERIVED, MEDIUM | stage is a projection distinct from persisted execution status. Durations need stated endpoints, units, valid samples and clock-skew handling. |
| JobOperationsSnapshot / candidate aggregate DTO | scope, stage counts/oldest ages, jobType/service breakdown, daily outcomes/rates/percentiles, sampleCount, window, snapshotTime | DERIVED, MEDIUM | Bounded aggregate queries; no unbounded metric labels or speculative zero durations. |
| PublishDrainEstimate / candidate DTO | publishableBacklog, recentPublishRate, publishDrainDuration, publishDrainAt, snapshotTime, sampleCount, rateWindow, typed estimate state | DERIVED, MEDIUM | Eligible snapshot only; no worker-completion ETA or SLA. Null ETA for insufficient evidence; units must be explicit. |
| Idle observation / candidate snapshot fields | classification, idleObservedSince, idleObservedDuration, observationStartedAt, evidenceCompleteness | UNRESOLVED/DERIVED, MEDIUM | Proposed polling observation, not exact idleSince or a historical fact. Requires a complete selected scope and continuous qualifying observations; unknown/restart resets or invalidates interval. Duration is a lower bound; polling can miss intervening work. Exact transition history requires a separate approved evidence/storage design. |
| DataHealthScanRequest / candidate Query Service DTO | logical dataset/partition, date/symbol scope, supported checks, bounded request, explicit refresh | ADD/UNRESOLVED, MEDIUM | Authorize and cap server-side. No raw SQL or unrestricted object path; no repair/backfill side effect. |
| DataHealthScanResult / candidate DTO | scan identity/state, dataset/partition/dataVersion, inspected identities, requested scope/applied bounds, findings/counts, check/scanner/evidence versions, start/completion times, truncation/cancellation/timeout/failure, completeness | ADD/UNRESOLVED, MEDIUM | Memory only. Separate scan execution state from health verdict; incomplete scans cannot become NO_ISSUE. File changes invalidate provenance. |
| Cache/read presentation / Query Service → Console | cache hit/miss, scannedAt, expiresAt, scope/dataVersion, versions, completeness; typed unavailable for evicted IDs | ADD/DERIVED, MEDIUM | TTL/entry/byte/finding/concurrency caps; no durable table. Restart loses scan history and in-flight state; replicas do not share cache. |
| Fixed dashboard UI / Console | Existing filters/contracts plus typed unavailable/UNKNOWN/stale presentation | REUSE/ADD, LOW | Default order changes; does not activate scanner or arbitrary query expansion. |

The idle-observation row is a proposal added by this review, not an approved authoritative-idle contract. Freeze its evidence and restart semantics before implementation. A last completion timestamp or zero Kafka lag alone cannot establish an idle interval.

## Candidate type and evidence rules

| Field family | Candidate representation | Null / validation / behavior |
| --- | --- | --- |
| Timestamp evidence and scannedAt/expiresAt | ISO-8601 UTC instant on JSON; UTC instant in storage/runtime | Missing evidence is null, not createdAt/updatedAt fallback. expired scan identity returns unavailable. |
| Processing/stage/idle durations | Non-negative integer milliseconds with explicit endpoint/window | Null if endpoints absent, negative/skewed or outside the qualifying observation scope; do not clamp into a fabricated zero. Worker-local duration may differ from cross-service timestamp subtraction. |
| Counts, sampleCount, backlog | Non-negative integer | Counts require stated scope/snapshot/completeness; zero is meaningful only with authoritative coverage. |
| Rates | Finite non-negative numeric value plus explicit jobs/minute or jobs/hour unit and measurement window | Null/typed unknown for insufficient samples; do not divide by zero. |
| Identity/version/scan IDs | Opaque bounded strings using existing identity conventions; exact types frozen per owning contract | execution/work identity reused. New scan IDs are ephemeral, authorized and process-local; no durable history implied. |
| stage, estimate/scan/health states | Bounded string enums, each under its own candidate DTO | Projected stage is not execution status; scan running/failed/incomplete is distinct from health verdict. Exact enum/wire shape is a contract task. |
| Scope, check selection, filters and bounds | Typed bounded objects/arrays with server validation | Reject unsupported scope/raw SQL/physical paths. Unknown calendar evidence cannot prove missing-date exceptions. |
| Findings and provenance | Bounded typed collection/count summary, versioned rules and logical inspected identities | Overflow/truncation exposes incomplete result, not healthy. In-memory cache size includes retained summaries and in-flight state. |

These are candidate representation constraints for design review, not deployed schemas. Field-specific required/optional flags must be frozen before implementation, along with request/response examples and legacy compatibility.

In particular WAITING_STATUS_APPLY needs independently observable worker terminal-publication evidence. Receiving the terminal message at Platform cannot retrospectively prove an earlier publication wait; unsupported fine stages stay UNKNOWN. Scope/filter change or a gap in fresh observations invalidates the proposed idle observation interval.

## Small delivery slices for Phase 13

These are tasks under existing increments, not new IDs or new dependency edges. No increment becomes completed until all its original acceptance criteria pass. Each task has one primary behavior change; a shared-contract producer/consumer pair remains one coherent compatibility slice.

| Task | Bounded deliverable | Impact / rollback boundary |
| --- | --- | --- |
| P13-I1.a | Inventory existing timing/status fields and freeze evidence owners/types/compatibility; no runtime change | Design gate. Transport, persistence and clock semantics must be explicit before later tasks. |
| P13-I1.b | Add worker timing evidence through the selected contract and Platform ingestion, initially not changing startedAt | HIGH; coherent Python/Platform compatibility slice, additive nullable evidence. No logging-stack dependency. |
| P13-I1.c | Add bounded execution stage/read projection, using only available evidence | MEDIUM; unsupported fine stages return UNKNOWN; no persisted status-enum expansion by inference. |
| P13-I1.d | Coordinate startedAt/processing semantics and legacy reader migration | HIGH; separate deployment/rollback gate; do not mix it with scanner, UI or concurrency. |
| P13-I1.e | Record representative baseline with completeness, sample/clock limitations | Measurement task; no concurrent-worker rollout. |
| P13-I2.a | Read-only outstanding-work/rate/duration aggregate API | MEDIUM; explicit bounds/index/query-cost review, no remediation. |
| P13-I2.b | Guarded publication ETA projection | MEDIUM; separate from worker completion; unknown estimates remain typed. |
| P13-I2.c | Warning-only stall/idle observation contract and projection after evidence policy is frozen | MEDIUM; idle duration remains unavailable until contract/evidence supports it. |
| P13-I3.a | Freeze bounded request/result/check/version contract and read-only scan skeleton | MEDIUM; preserve calendar owner gate; no persisted result store. |
| P13-I3.b | Readability/schema/required-field/duplicate/OHLCV checks | MEDIUM; one check family per review where useful; bounded declared scope. Calendar-independent. |
| P13-I3.c | Memory cache, in-flight dedup and lifecycle/invalidation | MEDIUM; test incomplete result, refresh, authorization, eviction/cancellation/restart, no repair. |
| P13-I3.d | Expected-date checks after approved calendar/lifecycle evidence or explicit scope narrowing | Evidence gate; no fabricated holiday/listing/suspension classification. |
| P13-I4.a | Operator-first navigation and unavailable Data Health shell | LOW; existing canonical dependency P13-I2 still applies. |
| P13-I4.b | Bind fixed operations and health views to accepted DTOs; reuse small Market Review | LOW/MEDIUM; keep scanner activation independent; no arbitrary SQL/layout expansion. |

## Cross-service and verification boundaries

This review changes documentation only. Platform, Analyzer, Ingestor, Query Service, Console, shared contracts/libraries, PostgreSQL, Kafka, MinIO, configuration and operations have no runtime change in this patch. Their future implementation impact is listed per surface above and in each plan.

No generated contracts, messages, datasets, readiness pointers, migrations, runtime APIs or deployment settings are edited. No build/test/lint/format/provider/load verification is run. Code-review-graph is unavailable; this is plan-derived impact analysis, not source-graph completeness.

At implementation, map every changed field/behavior to producer/consumer, nullable/legacy handling, success/failure/duplicate/out-of-order behavior, authorization, migration and rollback tests as applicable. Preserve existing coverage gates. Current evidence/status/dependency/owner gates are unchanged.
