# Plan 029 — Operator Trust Console

Status: Owner-approved Phase 13 supporting implementation plan. Canonical increment status, dependencies, readiness, and execution order belong to the [increment registry](roadmap/implementation-increments.md). This planning document does not claim that Phase 13 runtime behavior exists.

Owner decision recorded: 2026-10-06.

## Goal

Make Omni Console open with truthful answers to three operator questions, in this fixed order:

1. What is the pipeline doing now, how quickly is it progressing, and when will eligible scheduler-outbox backlog be published?
2. Is EOD data sufficient and trustworthy for the expected trading dates?
3. What small, fixed market review can be shown from existing trusted datasets?

Deliver this without conflating dispatch with processing, metadata readiness with row-level data health, or an estimate with an SLA. Establish the measurement baseline before Phase 12 chooses worker concurrency, Platform status batching, or an independent writer.

## Outcome

After Phase 13 is implemented and verified:

- Omni Console opens on fixed, code-owned sections ordered **Job Operations → Data Health → Market Review**;
- Job Operations distinguishes dependency wait, dispatch wait, worker wait, processing, status-application wait, and completion using authoritative evidence;
- operators can inspect current lag, outstanding work, daily throughput, processing and end-to-end duration, and scheduler-outbox publish-drain estimates;
- daily throughput uses jobs/minute as its primary unit while also showing jobs/hour and total jobs/day, separated by job type and service;
- EOD Data Health performs an explicit, manual, bounded read-only scan of actual Parquet and records exact result provenance;
- Data Health distinguishes never scanned, no issue, issues found, and stale scan results;
- a small Market Review reuses existing fixed widgets and bounded Query Service contracts after operational trust surfaces;
- Raw SQL remains available only as a de-emphasized secondary tool and is not expanded by this phase;
- no Phase 13 action repairs data, launches a backfill, rewrites execution history, mutates READY state, or automatically changes worker capacity.

## Verified Baseline and Conflicts

| Area                | Existing source capability                                                                                                                                                              | Missing capability                                                                                                                                         | Conflict resolved by this plan                                                                                                                                   |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Job operations      | Platform exposes a job catalog, safe manual trigger, execution status, and recent parent history. Console has a Jobs panel. Scheduler outbox persists dependency and publication state. | Authoritative stage timestamps, stage counts, queue/processing/end-to-end/status lag, daily throughput, stall heuristics, and publish-drain estimates.     | Child executions are currently marked `RUNNING` and receive `startedAt` during dispatch preparation. That state cannot truthfully mean active worker processing. |
| EOD inspection      | Query Service resolves logical READY identities and runs bounded DuckDB queries over Parquet. Existing dashboard endpoints expose selected EOD aggregates and manifest provenance.      | Manual row-level checks for expected dates, duplicates/conflicts, fields, OHLCV validity, corruption, and schema mismatch, with durable result provenance. | Manifest readiness/freshness is not proof that every expected symbol/date row is present and valid.                                                              |
| Console composition | A compile-time widget registry and fixed EOD/signal widgets exist. Market Dashboard is currently the default section.                                                                   | Operator-first composition and shared filters across operations, health, and a small market review.                                                        | The owner-approved product order supersedes the prior market-first default while retaining reusable widget/query source.                                         |
| Capacity planning   | Phase 12 requires measurement before concurrency and batching.                                                                                                                          | A truthful operator-visible baseline whose stages and rates are comparable.                                                                                | P12-I1 must depend on P13-I1 so concurrency, bulk-status, and writer decisions do not rely on ambiguous `RUNNING` rows.                                          |

Source presence is baseline evidence only. It does not make a Phase 13 increment implemented, verified, or completed.

## Operator Journeys

### Pipeline operations

```text
Open Omni Console
  -> Job Operations
  -> inspect outstanding work by truthful stage
  -> filter by date, job type, service, topic/group, stage, or status
  -> compare jobs/minute, jobs/hour, jobs/day, and duration percentiles
  -> inspect scheduler-outbox publishable backlog and estimated drain time
  -> distinguish idle, progressing backlog, suspected stall, and unknown evidence
```

### EOD trust review

```text
Open Data Health
  -> select supported exchange/date/symbol scope
  -> inspect previous scan state and exact provenance
  -> explicitly start a bounded manual scan
  -> review expected-date classifications and data-quality findings
  -> use Dataset Explorer for read-only evidence when needed
```

The scan offers no repair, backfill, readiness mutation, dependency bypass, or browser-to-Kafka action.

### Market review

```text
Open Market Review
  -> inspect a small fixed set of existing trustworthy widgets
  -> apply bounded exchange/date/symbol filters
  -> see effective dates and data versions
  -> return to Job Operations or Data Health when a source is stale or unavailable
```

## Stage and Timestamp Semantics

Phase 13 must not redefine one overloaded execution status into several inferred stages. It must expose stage evidence explicitly and preserve compatibility for historical rows.

| Stage                  | Meaning                                                                                     | Minimum authoritative evidence                                                |
| ---------------------- | ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `PREPARED`             | Stable execution and durable dispatch intent exist.                                         | Execution/outbox transaction committed.                                       |
| `WAITING_DEPENDENCY`   | Dispatch intent exists but an enforced dependency is not ready.                             | Current scheduler-outbox dependency decision and retry time.                  |
| `WAITING_DISPATCH`     | Message is eligible or retryable but Kafka publication is not durably acknowledged.         | Scheduler-outbox state, eligibility, lease, attempts, and availability time.  |
| `WAITING_WORKER`       | Scheduler outbox publication is acknowledged but worker processing has not started.         | `publishedAt` plus no authoritative worker processing-start evidence.         |
| `PROCESSING`           | A worker has started the represented work.                                                  | Worker-originated processing-start evidence.                                  |
| `WAITING_STATUS_APPLY` | Worker completed processing and published terminal status, but Platform has not applied it. | Worker terminal/status-published evidence plus no Platform-applied timestamp. |
| `COMPLETED`            | Platform durably applied the terminal result.                                               | Platform terminal transition timestamp.                                       |
| `UNKNOWN`              | Available legacy or partial evidence cannot identify a truthful stage.                      | Explicit classification; never inferred as processing.                        |

Required timestamp semantics:

- `triggeredAt` remains request/schedule intent time;
- dispatch preparation must not set processing `startedAt`;
- `startedAt` means authoritative processing start after migration;
- additive stage timestamps must identify preparation, dependency wait, outbox publication, worker receipt/start, worker terminal-status publication, and Platform status application as applicable;
- all timestamps remain UTC at rest and on the wire;
- old rows without evidence remain `UNKNOWN` or use an explicitly named legacy field; they are not silently backfilled from creation/update timestamps;
- stage evolution must preserve the existing PENDING/RUNNING/terminal compatibility policy until coordinated readers and writers are deployed.

Whether these timestamps use additive status-event fields, headers, a separate operational event, or Platform-local observations must be decided during P13-I1 contract design after producer/consumer impact analysis. No physical storage path enters a business message.

## Job Operations Metrics

### Current outstanding work

The dashboard reports snapshot counts and oldest age for:

- dependency-waiting work;
- publishable scheduler-outbox backlog;
- claimed scheduler-outbox work;
- retryable work not yet eligible;
- published commands waiting for worker start;
- active worker processing;
- terminal statuses waiting for Platform application;
- completed, failed, suspected-stalled, and unknown work.

Counts are separated by job type and service. Topic, partition, and consumer group are included where authoritative Kafka evidence exists. Metrics must not use unbounded execution, symbol, message, or data-version labels.

### Daily throughput and duration

For a selected day or bounded date range, show:

- jobs/minute as the primary rate;
- jobs/hour;
- total jobs/day;
- prepared, published, processing-started, completed, successful, and failed absolute counts;
- processing-duration p50/p95/p99;
- end-to-end-duration p50/p95/p99;
- queue lag and status-application lag distributions where stage evidence exists;
- breakdown by day, job type, and service;
- sample count, measurement window, snapshot time, and evidence completeness.

Provider-bound ingestion must not be averaged into CPU/storage-bound analysis without a visible breakdown. Missing stage evidence must reduce the sample count rather than invent a zero duration.

### Scheduler-outbox publish backlog and ETA

Scheduler-outbox visibility reports separately:

- currently publishable `PENDING` messages;
- `WAITING_DEPENDENCY` messages;
- currently claimed messages;
- retryable messages and their next eligibility time;
- oldest age by category;
- recent successful publication rate in jobs/minute and jobs/hour;
- total messages published by day;
- estimated time and timestamp at which the current publishable backlog will drain.

The estimate is:

```text
publishableBacklog = PENDING messages eligible for dispatch at snapshot time
recentPublishRate = successfully PUBLISHED messages / minutes in the stated window
publishDrainDuration = publishableBacklog / recentPublishRate
publishDrainAt = snapshotTime + publishDrainDuration
```

The UI may show the estimate only when:

1. the rate window has a configured minimum sample count;
2. the rate is greater than zero;
3. the dispatcher made sufficiently recent progress;
4. dependency-waiting and future-retry messages are excluded from `publishableBacklog`;
5. the snapshot time, sample count, rate window, rate, duration, and `ESTIMATE` label are visible.

Otherwise return a typed state such as `INSUFFICIENT_SAMPLE`, `NO_RECENT_PROGRESS`, `BLOCKED_BY_DEPENDENCY`, or `UNKNOWN`. The estimate predicts only publication of the currently eligible scheduler-outbox snapshot. It does not predict worker completion and is not an SLA.

### Idle and stalled heuristics

| Classification      | Required meaning                                                                                                |
| ------------------- | --------------------------------------------------------------------------------------------------------------- |
| `IDLE`              | No queued, claimed, processing, or status-application work in the selected scope.                               |
| `BACKLOGGED`        | Outstanding work exists and authoritative progress is recent.                                                   |
| `SUSPECTED_STALLED` | Outstanding work exceeds configured age and no qualifying progress/heartbeat occurred in the configured window. |
| `UNKNOWN`           | Evidence is incomplete, incompatible, or too sparse for a safe classification.                                  |

Thresholds are configuration, not product truths. P13-I1 baseline evidence must validate conservative defaults. A suspected stall is a warning only and cannot rewrite status, commit an offset, replay a message, or change concurrency.

## EOD Data Health Scan Contract

P13-I3 adds an explicit operator-started, bounded, read-only scan of actual EOD Parquet through trusted logical dataset resolution. It does not treat the global metadata document as row-level proof.

### Checks

For the requested supported scope, inspect:

- expected trading dates per symbol using approved trading-calendar and symbol-lifecycle evidence;
- duplicate rows and conflicting rows for the same logical key;
- required fields and nullability;
- date, symbol, and exchange identity consistency;
- OHLCV validity, including finite/non-negative values where applicable and coherent high/low/open/close relationships;
- Parquet readability/corruption;
- physical schema versus the supported canonical schema;
- exact file set inspected and scan truncation/bounds.

### Missing-date classifications

| Classification           | Meaning                                                                      |
| ------------------------ | ---------------------------------------------------------------------------- |
| `HOLIDAY`                | An approved exchange calendar proves the date is not a trading session.      |
| `PRE_LISTING`            | Approved symbol-lifecycle evidence proves the date precedes listing.         |
| `SUSPENSION_OR_NO_TRADE` | Approved evidence proves suspension or another supported no-trade condition. |
| `SUSPECTED_MISSING`      | A trading row is expected but absent and no approved exception explains it.  |
| `UNKNOWN`                | Evidence is insufficient to decide safely.                                   |

Absence alone must never be classified as pre-listing or suspension. The canonical sources for exchange calendars and listing/suspension evidence remain an unresolved owner decision and are a stop condition for those classifications. Until resolved, the scanner uses only `SUSPECTED_MISSING` or `UNKNOWN` where appropriate.

### Result provenance

Every result includes:

- dataset name;
- normalized logical partition;
- exact `dataVersion` read;
- inspected logical file/object identities without exposing unrestricted physical paths to the browser;
- requested scope and applied bounds;
- checks and check versions;
- scan start/completion time;
- scanner version;
- finding counts and typed findings;
- truncation, cancellation, timeout, and failure state.

### UI states

| State           | Meaning                                                                                            |
| --------------- | -------------------------------------------------------------------------------------------------- |
| `NEVER_SCANNED` | No completed scan exists for the exact requested identity/scope.                                   |
| `NO_ISSUE`      | The latest completed scan found no issue within its declared checks and bounds.                    |
| `ISSUES_FOUND`  | The latest completed scan produced one or more warnings/errors.                                    |
| `STALE`         | A prior result exists but its age, dataset version, or requested scope no longer satisfies policy. |

`NO_ISSUE` is not a universal guarantee beyond the displayed checks, files, bounds, and version.

## Unresolved Data Health Persistence Decision

The durable owner of scan runs/results is intentionally **not decided** by this plan.

Options to evaluate before P13-I3 can become `ready`:

1. Platform/PostgreSQL owns audited durable scan requests and results while Query Service performs bounded read-only scans; or
2. Query Service owns scan persistence, which requires an approved durable multi-instance-safe replacement or explicit promotion of its current local persistence boundary.

The decision must define authentication, audit identity, idempotency, retention, pagination, restart behavior, multi-instance behavior, schema migration, deployment, and deletion policy. P13-I3 remains `pending`; implementation must stop rather than silently choosing a persistence owner.

## Dashboard Order and Filters

The fixed code-owned order is:

1. **Job Operations** — pipeline stage, lag, throughput, duration, backlog, publish ETA, and warning state;
2. **Data Health** — manual EOD scans, classifications, findings, and provenance;
3. **Market Review** — a deliberately small reuse of supported freshness, breadth, movers, and signal widgets.

Filters are allowlisted and bounded. Applicable filters include day/date range, job type, service, topic/consumer group, stage, status, exchange, symbol, and EOD trading date. Each panel declares which filters it honors; the browser does not construct arbitrary SQL.

Dataset Explorer remains a secondary diagnostic tool. Raw SQL is de-emphasized from primary navigation and remains deferred functionality; Phase 13 neither deletes it nor expands Saved Queries, export, personalization, or arbitrary analytical workflows.

## Dataset Outputs

No analytical dataset output.

The Data Health result is operational evidence, not a market/analytical dataset and not a replacement for EOD Parquet.

## Metadata Outputs

No dataset metadata output.

Data Health scans do not write `_metadata/metadata.json`, immutable version manifests, or `READY.json`, and do not alter readiness or lineage.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No new trading algorithm is unlocked. Phase 13 improves human confidence in existing daily/EOD pipeline operation and input quality.

## Contract Impact

| Contract area                        | Decision                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka or service-to-service protobuf | Potential additive operational timing evidence in P13-I1. Exact transport is unresolved pending impact analysis. If changed, update Java producers/consumers, Python workers, schemas/fixtures, topic documentation, compatibility, and both-side tests together. No business payload may contain a physical storage path. |
| Object-storage JSON manifests        | Unchanged. Scans consume exact logical identity/version and never mutate metadata or READY.                                                                                                                                                                                                                                |
| Storage paths or dataset ownership   | Unchanged. Ingestor remains EOD producer; Query Service resolves trusted logical reads. No new browser-visible physical path.                                                                                                                                                                                              |
| Public Java or Python APIs           | Additive Platform operational stage/aggregate API and bounded Query Service scan execution API are expected. The persistence owner for scan requests/results remains unresolved.                                                                                                                                           |
| Configuration or environment         | Add bounded metric windows, minimum ETA samples, idle/stall thresholds, scan row/byte/file/time limits, and result-retention settings after persistence ownership is approved. Defaults fail conservatively.                                                                                                               |
| PostgreSQL/persistence               | Additive stage evidence and aggregate-query support are expected. Data Health run/result persistence is unresolved and must not be assumed. No analytical EOD copy is added.                                                                                                                                               |

## Service Impact Matrix

| Surface          | Impact                                                                                                                                                                                                  |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Platform         | P13-I1/P13-I2 stage evidence, scheduler-outbox metrics, aggregate API, compatibility, migrations, and tests. It may become Data Health audit/result owner only after the unresolved decision.           |
| Analyzer         | Emit authoritative worker receive/start/status-publication evidence for affected jobs; no concurrency, calculation, dataset, or writer change.                                                          |
| Ingestor         | Emit equivalent authoritative timing evidence; retain sole EOD production and validation ownership; no provider concurrency or repair path.                                                             |
| Query Service    | Execute bounded read-only EOD scans using logical resolution and DuckDB; expose typed findings/provenance; do not assume persistence ownership. Reuse existing fixed dashboard reads for Market Review. |
| Omni Console     | Make Job Operations the landing section, add operations and health states/filters/provenance, keep a small Market Review, and de-emphasize Raw SQL.                                                     |
| `libs/py-common` | Add reusable timing/status helpers only if cross-worker behavior is genuinely shared; preserve storage and READY-last abstractions.                                                                     |
| `libs/contracts` | Change only if P13-I1 selects a language-neutral transport contract. Never hand-edit generated output.                                                                                                  |
| PostgreSQL       | Expected additive Platform operational-stage storage/indexes. Data Health storage is unresolved. No analytical cache.                                                                                   |
| Configuration    | Add bounded windows, thresholds, and scan limits with documented defaults and deployment impact.                                                                                                        |
| Tests            | Platform migration/API/aggregation/ETA tests; worker timing compatibility tests; Query Service scan tests; Console state/filter/accessibility tests; cross-service impact-to-test matrix.               |
| Operations       | Cardinality-safe metrics, data-retention decision, threshold tuning from baseline, and explicit no-SLA estimate wording.                                                                                |

## Implementation Increments

Canonical statuses and dependencies remain in the increment registry.

### P13-I1 — Truthful execution stage model and baseline instrumentation

**Dependencies:** P4-I3, P7-I3.  
**Blocks:** P13-I2 and P12-I1.

Outcome:

- define and persist authoritative stage evidence and timestamp semantics;
- correct new `RUNNING`/`startedAt` semantics so dispatch preparation is not processing start;
- preserve explicit compatibility for historical/partial rows as `UNKNOWN`;
- measure stage counts, lags, rates, durations, and evidence completeness by job type/service;
- capture a representative baseline before concurrency, bulk status application, or writer decisions.

P13-I1 does not implement worker concurrency, bulk Platform status application, independent writer behavior, replay, or ad hoc status repair.

### P13-I2 — Job Operations dashboard and stall heuristics

**Dependencies:** P13-I1.  
**Blocks:** P13-I4.

Outcome:

- expose current outstanding counts and oldest age by truthful stage;
- provide jobs/minute, jobs/hour, total jobs/day, absolute outcomes, and p50/p95/p99 durations by day/job type/service;
- show scheduler-outbox publishable, dependency-waiting, claimed, and retryable backlog separately;
- calculate guarded publish-drain duration/timestamp for only the eligible snapshot;
- classify idle, progressing backlog, suspected stall, and unknown without automatic remediation.

### P13-I3 — Manual EOD Parquet Data Health scan

**Dependencies:** P3-I4, P7-I2.  
**Blocks:** P13-I4.

Outcome:

- run operator-started bounded scans of actual EOD Parquet;
- detect expected-date gaps, duplicate/conflicting rows, required-field and OHLCV violations, corruption, and schema mismatch;
- present exact dataset/partition/version/file/check/time provenance and four UI states;
- remain warning-only and read-only.

P13-I3 cannot become ready until the owner approves durable result ownership and canonical calendar/listing/suspension evidence or narrows the classifications accordingly.

### P13-I4 — Operator-first fixed dashboard shell

**Dependencies:** P13-I2, P13-I3.

Outcome:

- make Job Operations the default landing section;
- order sections Job Operations, Data Health, then small Market Review;
- apply code-owned bounded filters and provenance presentation;
- keep Dataset Explorer secondary and Raw SQL de-emphasized/deferred;
- reuse existing widgets and Query Service contracts rather than introducing user-owned SQL or remote widget definitions.

## Technical Debt Kept Separate

Phase 13 excludes:

- bounded worker concurrency and manual Kafka offset ownership;
- Platform bulk status consumption/parent aggregation;
- the independent dataset-writer service and writer batching;
- scheduled/automatic Data Health scans;
- repair, backfill, READY mutation, automatic replay, or automatic remediation;
- new provider fallback/rotation or provider-limit bypass;
- arbitrary Raw SQL expansion, Saved Queries, exports, persisted layouts, personalization, or plugin loading;
- broad Market Dashboard growth, expensive hidden precomputation, and research/advice surfaces;
- a new alerting platform, multi-instance workers/writers, distributed locks, or HA promotion.

Those items remain owned by Phase 12 or existing technical-debt records and require their own dependencies and approval.

## Repository Guidance Updates

At implementation, review and update as applicable:

- `AGENTS.md`, `CLAUDE.md`, and `.roo/rules/` only if architecture, workflow, contract, or coding guidance changes;
- `docs/README.md` and `docs/INDEX.md`;
- `docs/plans/roadmap/README.md` and `docs/plans/roadmap/implementation-increments.md`;
- `docs/architecture/001-system-overview.md`;
- `docs/flows/001-job-execution.md`;
- `docs/data/001-kafka-contracts.md` only if transport semantics change;
- `docs/data/002-data-lake.md` only if scan/read semantics need canonical clarification;
- `docs/data/003-database.md` when persistence is approved;
- Platform, Analyzer, Ingestor, Query Service, and Console READMEs for changed runtime/API ownership;
- Plans 008, 009, and 027 plus technical debt 004 and 009 to remove competing scope claims.

This planning-only consolidation changes no runtime architecture or coding rule, so no immediate `AGENTS.md`, `CLAUDE.md`, or `.roo/rules/` edit is required.

## Verification

No build, test, lint, format, coverage, load, deployment, or runtime command was run for this planning change.

Before implementation, inspect exact project targets and obtain approval for a concrete command list. Implementation verification must include:

- code-review-graph impact analysis before contract/API/persistence changes and change detection after edits;
- explicit reconciliation of Platform, Analyzer, Ingestor, Query Service, Console, shared contracts/libraries, persistence, configuration, tests, and operations;
- an impact-to-test matrix for every changed or directly impacted production path and acceptance criterion;
- attributable line and branch coverage for critical components, defaulting to at least 80% each unless an instrumentation limitation remains explicit;
- migration and compatibility coverage for legacy execution rows and mixed-version producer/consumer rollout;
- stage transition success, wait, retry, duplicate, regression, failure, restart, and out-of-order timing coverage;
- daily grouping, timezone, sparse sample, rate-zero, changing-backlog, ETA exclusion, and percentile tests;
- scan success, no-row, duplicate/conflict, missing fields, OHLCV boundaries, corruption, schema mismatch, timeout, cancellation, changed data version, stale result, and provenance tests;
- UI coverage for every stage, estimate state, health state, filter, loading/empty/error path, accessibility, and partial-source failure;
- contract tests on both sides of any changed Kafka/HTTP boundary;
- approved targeted Nx checks before broader checks, with all unrun or inconclusive checks left unresolved.

## Acceptance Criteria

### P13-I1

- [ ] Dispatch preparation no longer represents unstarted worker work as active processing.
- [ ] Authoritative stage timestamps have documented owners and compatibility semantics.
- [ ] Legacy/partial evidence is visibly `UNKNOWN`, not inferred.
- [ ] A representative baseline reports absolute counts, stage lag, processing and end-to-end durations, rates, failures, resource context, sample size, and observation window by job type/service.
- [ ] P12-I1 cannot proceed without completed P13-I1 baseline evidence.

### P13-I2

- [ ] Operator can select a day or bounded date range and see jobs/minute, jobs/hour, total jobs/day, absolute outcomes, and duration percentiles by job type/service.
- [ ] Operator can see how many jobs remain in every truthful stage and the oldest age in each stage.
- [ ] Scheduler-outbox publication backlog is separate from worker-processing and status-application backlog.
- [ ] Publish ETA excludes dependency-waiting and not-yet-eligible retry rows.
- [ ] Rate zero, sparse evidence, stale progress, and changing snapshots produce typed honest states rather than division-by-zero or fabricated ETA.
- [ ] Every estimate exposes snapshot time, rate window, sample count, rate, estimated duration/time, and non-SLA wording.
- [ ] Idle/stall classification is configurable, evidence-based, cardinality-safe, and warning-only.

### P13-I3

- [ ] Manual scan reads the exact logical EOD partition and `dataVersion` it reports.
- [ ] Checks cover expected-date evidence, duplicates/conflicts, required fields, OHLCV validity, corruption, and schema mismatch within explicit bounds.
- [ ] Holiday, pre-listing, and suspension/no-trade are used only with approved evidence; otherwise the result is suspected missing or unknown.
- [ ] Results expose exact scope, files, checks, versions, times, bounds, and truncation/failure state.
- [ ] UI distinguishes never scanned, no issue, issues found, and stale without overstating `NO_ISSUE`.
- [ ] No scan action repairs data, triggers backfill, rewrites Parquet, or changes metadata/READY.
- [ ] Durable scan-result ownership and calendar/symbol-lifecycle sources are owner-approved before implementation begins.

### P13-I4

- [ ] Omni Console opens in the fixed order Job Operations, Data Health, then Market Review.
- [ ] Filters are allowlisted, bounded, and declare their applied scope.
- [ ] Market Review reuses supported existing widgets/contracts and remains smaller than the two trust surfaces.
- [ ] Dataset Explorer remains available as a secondary diagnostic tool.
- [ ] Raw SQL is de-emphasized and not expanded, silently removed, or used to bypass fixed contracts.
- [ ] Partial failures do not hide healthy sibling panels, and all states remain accessible and truthful.

### Documentation and completion

- [ ] Canonical roadmap, supporting plans, technical debt, architecture, flow, data, service, and repository guidance are synchronized or have explicit no-update reasons.
- [ ] Contract impact and rollout are documented before any cross-service field changes.
- [ ] Required approved checks, impact reconciliation, attributable coverage, and applicable runtime evidence are recorded before any increment is marked completed.

## Owner Decisions and Stop Conditions

Resolved:

- all three priorities are approved active roadmap scope;
- Phase 13 uses four small increments;
- P13-I1 is a prerequisite for P12-I1;
- jobs/minute is the primary throughput unit, with jobs/hour and jobs/day also shown;
- throughput is separated by job type and service;
- scheduler-outbox publish-drain ETA is required and applies only to the current eligible backlog snapshot;
- dashboard order is Job Operations, Data Health, then a small Market Review;
- Raw SQL is de-emphasized/deferred.

Unresolved and blocking the applicable implementation decision:

1. durable ownership and persistence architecture for Data Health scan requests/results;
2. canonical exchange calendar, listing-date, and suspension/no-trade evidence;
3. initial stall, freshness, ETA sample, and recent-progress thresholds after P13-I1 baseline measurement;
4. the exact additive cross-service mechanism for authoritative worker stage timestamps.

Stop rather than guessing when any unresolved decision would change service ownership, persistence, compatibility, or classification truthfulness.
