# Plan 028 — Reusable Date-Range Job Backfill

Status: Proposed supporting implementation plan; not yet scheduled in the canonical roadmap.

Canonical scheduling owner: [`docs/plans/roadmap/implementation-increments.md`](roadmap/implementation-increments.md). Implementation requires a separately registered increment before autonomous execution.

## Goal

Add safe date-range backfill without creating parallel job definitions, job types, producers, topics, or consumer pipelines.

A backfill selects an existing job definition and a business-date range. Platform discovers the selected job's dependency graph, evaluates whether each dated work item is already active or complete, and prepares missing work through the existing producer registry and dependency-aware scheduler outbox.

The design must preserve the existing dispatch boundary represented by:

```java
jobProducerRegistry.getProducer(job.getJobType()).prepareDispatch(...)
```

It must not overload the runtime clock with historical business-date semantics.

## Outcome

After implementation, an authorized operator can preview or execute a bounded backfill against an existing job definition.

For each supported trading date, Platform will:

1. resolve the selected job and its upstream dependencies as a directed acyclic graph;
2. derive dated work identities in topological order;
3. inspect execution history and exact dataset manifests before deciding whether work is missing;
4. skip work that is already active or demonstrably complete;
5. enqueue missing upstream and requested work through the existing `JobProducerRegistry`, `JobProducer`, execution-history, and scheduler-outbox flow;
6. let dependency-aware outbox dispatch hold downstream work until the exact dated upstream input is READY;
7. preserve independent retry and terminal status for each dated work item.

Scheduled execution remains unchanged. Backfill is an execution mode of an existing definition, not a new definition category.

## Core Semantics

### Separate runtime time from business date

Backfill dispatch must carry both:

```text
observedAt   = actual UTC time when Platform performs the operation
businessDate = historical trading date represented by the work item
```

`observedAt` owns:

- claim and lease timestamps;
- execution audit timestamps;
- outbox creation, availability, retry, and delivery timestamps;
- operational logs and metrics.

`businessDate` owns:

- provider query date or range;
- dependency partition selection;
- output partition selection;
- dated history/idempotency checks;
- historical lineage.

The implementation must not loop through dates by replacing every existing `now` argument with a historical instant. Doing so would corrupt audit time, claim behavior, retry timing, and potentially scheduled next-run state.

### Reuse existing definitions and producers

The planner resolves the producer using the existing registry:

```java
JobProducer producer = jobProducerRegistry.getProducer(job.getJobType());
```

Backfill support extends the producer template with an explicit dispatch context or equivalent additive API. The implementation must reuse each existing producer's message construction and normal outbox path rather than introduce `BACKFILL_*` job types or duplicate producers.

A scheduled claim must not be reused across a date loop. The current producer flow releases its claim after preparing one execution. Backfill therefore requires a request-level orchestration boundary and separate dated execution preparation that does not mutate the definition's scheduled `nextRunAt`.

### First-class job keys and dependency graph

Job dependencies must move out of the untyped `configJson.dependsOnJobs` list into a first-class, independently persisted graph. Runtime configuration remains appropriate for producer parameters, filters, and dataset condition details, but it must no longer be the canonical owner of job-to-job topology.

Each existing job definition receives an immutable, unique, human-readable `jobKey` that is independent of its database UUID and stable across environments. A key identifies one logical definition, not merely a `JobType`; this is required because multiple definitions can share a type while differing by source, strategy, timeframe, sector, or provider. Example keys include:

```text
symbols.vn
prices.vn.sector.banking
indicators.vn.1d.default
signals.vn.1d.confirmed-trend
intraday-eod.vci.vn
```

Dependencies are stored as directed edges between keys:

```text
upstreamJobKey -> downstreamJobKey
```

The preferred persisted model is equivalent to:

```text
job_definitions
  job_key UNIQUE NOT NULL

job_dependencies
  dependency_key UNIQUE NOT NULL
  upstream_job_id FK job_definitions
  downstream_job_id FK job_definitions
  dependency_kind
  required
  created_at
```

`dependencyKey` must also be deterministic and portable, for example:

```text
<downstreamJobKey>:requires:<upstreamJobKey>
```

Foreign keys provide database integrity while stable keys support seed reconciliation, environment portability, APIs, logs, Mermaid generation, and operator diagnostics. Job types must not be used as graph node identity.

The graph repository must expose both directions without scanning JSON configuration:

```text
dependenciesOf(jobKey)   // direct upstream jobs
 dependentsOf(jobKey)    // direct downstream jobs
ancestorsOf(jobKey)      // complete upstream trace
 descendantsOf(jobKey)   // complete downstream impact
```

Transitive traversal may be implemented in Platform code or a bounded recursive SQL query, but it must use deterministic ordering, visited-node tracking, a depth/work limit, and explicit cycle reporting. The API and logs must return the traversed path so an operator can explain why a job was selected, waiting, blocked, or affected.

The planner starts with the selected existing definition and discovers supported upstream jobs through this graph. It must:

- resolve the selected definition by stable `jobKey` while retaining UUID foreign-key ownership;
- reject missing nodes, dangling edges, duplicate edges, self-dependencies, and ambiguous legacy mappings;
- detect dependency cycles before persistence or dispatch;
- produce a deterministic topological order;
- support reverse tracing from a selected job to all ancestors and forward impact tracing to all descendants;
- plan each dependency for the same applicable business date;
- avoid recursively invoking one producer from another producer;
- create downstream outbox work even when upstream work is not yet READY, allowing the existing dependency-aware dispatcher to return WAITING until exact inputs become available.

Dependency planning and dependency readiness remain separate concerns:

```text
planner: which dated work must exist?
dispatch guard: may this exact outbox item publish now?
```

### Dated work identity

History checks must not use only `JobType` or `triggeredAt`. `triggeredAt` is audit time and may be much later than the historical data date.

The canonical logical identity is:

```text
jobDefinitionId
+ workType
+ workKey
+ businessDate
+ executionMode
[+ inputDataVersion when output identity depends on exact upstream version]
```

A stable `runKey` or dedicated persisted/indexed columns must represent this identity. Querying unindexed JSON metadata is acceptable only for an explicitly bounded migration step, not as the final high-volume lookup design.

### Smart completeness and duplicate check

Before creating a dated execution, the planner classifies the work item:

| Classification     | Evidence                                                                                       | Action                                                      |
| ------------------ | ---------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| `MISSING`          | No matching history and no valid output                                                        | Enqueue                                                     |
| `DUPLICATE_ACTIVE` | Matching PENDING/RUNNING execution or dispatchable/waiting outbox exists                       | Do not enqueue another item; reference the active execution |
| `ALREADY_COMPLETE` | SUCCESS history plus exact output manifest and matching lineage                                | Skip                                                        |
| `STALE`            | Successful history exists, but approved input version or required lineage changed              | Recompute according to explicit policy                      |
| `REPAIR_REQUIRED`  | SUCCESS history exists, but output/manifest is absent or invalid                               | Recompute                                                   |
| `RETRYABLE`        | Latest matching execution failed with a retryable condition                                    | Create a fenced retry attempt                               |
| `BLOCKED`          | Invalid date, terminal dependency failure, corrupt input, unsupported job, or policy violation | Persist/report a terminal reason; do not publish            |
| `WAITING_INPUT`    | Exact input is not READY but can still become available                                        | Plan downstream work and leave dispatch waiting             |

History alone is not proof that data remains available. `ALREADY_COMPLETE` requires both execution evidence and exact manifest/output evidence.

### Request and execution hierarchy

A backfill request contains at minimum:

```text
jobDefinitionId
fromDate
toDate
optional work-key filter
dryRun
requestedBy
```

One request-level identity groups the operation. Dated work is represented by independently retryable executions:

```text
Backfill request A..B
  ├─ date A / upstream job / work key
  ├─ date A / selected job / work key
  ├─ date A+1 / upstream job / work key
  └─ date A+1 / selected job / work key
```

Each dated execution records `executionMode=BACKFILL`, `backfillRequestId`, `businessDate`, work identity, and approved input lineage. Parent/child aggregation must not mark the request successful until every non-skipped required item reaches an accepted terminal state.

### Preview and bounded execution

`dryRun=true` performs validation, graph expansion, history/manifest inspection, and classification without creating executions or outbox rows.

Both preview and execution must enforce:

- `fromDate <= toDate`;
- no unsupported future dates;
- exchange trading-calendar filtering where applicable;
- configured maximum date span and maximum expanded work-item count;
- deterministic pagination or chunking for large plans;
- an allow-list of job types that support historical execution;
- no force/bypass path around dependency or safe-write checks.

## Job-Type Rollout

### Stock-price synchronization

Reuse the existing stock-price definition and producer. Backfill parameters supply the requested range instead of the normal rolling window. The consumer's existing merge and date-row deduplication remain authoritative, but planner identity and manifests determine whether a dated partition needs work.

Recommended granularity:

```text
one execution per symbol per bounded date chunk
```

Chunking must preserve retry isolation and provider limits.

### Intraday EOD synchronization

Reuse the existing intraday-EOD definition, producer, topic, and consumer. Fan out one work item per `(symbol, tradingDate)`. Existing date validation and date-partitioned storage are retained.

Recommended granularity:

```text
one execution per symbol per trading date
```

### Indicator synchronization

Reuse the existing indicator definition and producer only after making its input and output semantics historical-date aware.

The message must identify the requested business date and exact approved upstream `dataVersion`, or an equivalent immutable logical reference. The consumer must read that exact input rather than the current global READY pointer. Output identity and lineage must prevent two historical dates from ambiguously overwriting the same result.

Until those requirements are implemented and verified, indicator backfill remains unsupported and the planner must return `BLOCKED` rather than enqueue misleading work.

### Other job types

Every additional job type requires an explicit capability entry defining:

- supported or unsupported;
- date granularity;
- dependency mapping;
- history/output completeness resolver;
- message parameter mapping;
- output identity and lineage behavior;
- retry safety.

No default "supported" behavior is allowed.

## Dataset Outputs

No new analytical dataset is introduced by the orchestration layer.

Existing supported jobs continue writing their existing datasets. Historical execution may add missing immutable versions or dated partitions under each dataset's existing ownership and logical path rules.

Indicator historical output identity may require a compatible extension to its existing dataset partition/version semantics. That change must be specified before implementation and must not introduce physical object paths into Kafka messages.

## Metadata Outputs

Backfill uses existing immutable version manifests and READY-last publication:

```text
write data -> validate -> publish immutable version manifest -> replace READY pointer last
```

Each completed dated work item must expose enough metadata to prove:

- business date or logical partition;
- output `dataVersion`;
- exact approved upstream `dataVersion` lineage;
- writer/job identity;
- successful validation before readiness publication.

Failed writes preserve the previous READY pointer. The orchestration layer does not become a second metadata writer; `SYNC_METADATA` remains the sole writer of canonical global discovery metadata.

## Algorithm Feature Outputs

No direct algorithm feature output.

The plan improves reproducibility and availability of existing historical features by ensuring they are computed against explicitly dated and versioned inputs.

## Algorithms Unlocked

The implementation makes the following workflows safer:

- gap repair for missing historical market-data partitions;
- deterministic historical indicator recomputation;
- reproducible signal regeneration after corrected upstream data;
- bounded replay for backtests and lineage audits;
- selective retry of only missing, stale, or corrupt partitions.

It does not itself define new trading signals, indicators, or model features.

## Contract Impact

### Kafka or service-to-service protobuf

**Changed for date-aware analytical jobs.**

- Stock-price and intraday-EOD messages must be reviewed to confirm their existing date/range fields are sufficient for the chosen granularity.
- Indicator messages require an additive business-date and exact input-version contract before historical indicator backfill is enabled.
- Platform producers, Python consumers, status-message correlation, shared contract definitions, compatibility fixtures, and [`docs/data/001-kafka-contracts.md`](../data/001-kafka-contracts.md) must change together.
- Generated files under `libs/contracts/gen` must not be hand-edited.
- Kafka keys remain ordering/partition keys, normally the work key; date identity belongs in the payload and persisted run identity.

### Object-storage JSON manifest

**Potentially changed.**

Existing manifests remain JSON. Supported datasets must expose exact dated partition and lineage evidence. If current indicator manifests cannot distinguish historical business dates or immutable input versions, their schema must receive an additive versioned extension with backward-compatibility handling.

### Storage path or dataset ownership

**Unchanged for stock-price and intraday-EOD ownership. Potentially changed for indicator partition identity.**

Existing writers remain the sole owners of their datasets. Any indicator path change must use shared logical builders backed by [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml), preserve compatibility or document migration, and never place physical object paths in Kafka payloads.

### Public Java or Python API

**Changed.**

Expected additive surfaces include:

- a Platform backfill request/preview contract;
- a dispatch context separating `observedAt` from `businessDate`;
- a job-type backfill capability registry;
- dependency-DAG planning and completeness resolvers;
- repository queries and persisted/indexed dated work identity;
- date/version fields in affected Python message models and handlers.

The existing scheduled producer API remains supported.

### Configuration or environment contract

**Changed additively.**

Expected configuration includes maximum date span, maximum expanded work items, batch/chunk size, enabled job-type allow-list, and retry/concurrency limits. Defaults must fail closed for unsupported job types. No provider credentials, regions, or physical storage paths are added to request payloads.

## Persistence and Migration

A migration must provide an indexed, durable way to query dated logical work and the first-class dependency graph. The preferred model is explicit columns and normalized edges rather than date or dependency extraction from `meta_json`/`configJson`.

The migration must define:

- immutable unique `job_definitions.job_key` values for every existing definition;
- a normalized `job_dependencies` edge table with deterministic `dependency_key`, upstream/downstream foreign keys, uniqueness, and indexes in both directions;
- seed reconciliation that resolves keys to IDs and fails on missing, duplicate, self-referencing, or cyclic edges;
- a compatibility period in which existing `dependsOnJobs` values are read only for migration comparison, never as a second runtime source of truth;
- removal of migrated `dependsOnJobs` topology from job configuration only after graph parity is verified;
- business date and execution mode persistence;
- stable backfill request identity;
- logical uniqueness for active dated work;
- treatment of legacy histories with no business date;
- retry attempts without violating active-work uniqueness;
- rollback behavior that does not delete execution history, graph audit evidence, or published datasets.

Migration must generate and compare old-config and new-table edge sets before cutover. Any ambiguous mapping—especially multiple definitions sharing one `JobType`—blocks automatic migration and requires an explicit stable-key mapping. Legacy execution records without a business date remain `UNKNOWN`, not silently inferred from `triggeredAt`.

## Implementation Increments

This supporting plan proposes the following sequence. IDs are placeholders until the owner adds them to the canonical roadmap registry.

### Increment A — Stable job keys and first-class dependency graph

- Add immutable unique `jobKey` identity to every existing definition.
- Add normalized dependency edges with deterministic `dependencyKey` and bidirectional indexes.
- Migrate and compare existing `dependsOnJobs` metadata without retaining two runtime sources of truth.
- Add direct upstream/downstream and transitive ancestor/descendant queries.
- Add self-edge, duplicate-edge, dangling-node, cycle, depth, and graph-size guards.
- Add deterministic Mermaid/export output generated from the persisted graph for documentation and diagnostics.

### Increment B — Backfill identity, preview, and persistence

- Add request validation and dry-run preview.
- Separate `observedAt` and `businessDate`.
- Persist/index canonical dated work identity and request grouping.
- Implement history classification without dispatch.
- Add active-duplicate fencing.

### Increment C — Dependency DAG and existing-producer dispatch

- Add explicit job-type capability and dependency mapping.
- Detect cycles and produce deterministic topological plans.
- Add an additive producer dispatch context while retaining scheduled dispatch.
- Prepare dated upstream/downstream executions through the existing registry and outbox.
- Ensure backfill does not alter scheduled `nextRunAt` or reuse one claim across the range.

### Increment D — Stock-price and intraday-EOD rollout

- Map backfill dates/ranges into existing producer messages.
- Implement exact history-plus-manifest completeness resolvers.
- Enforce provider-safe chunking and trading-calendar rules.
- Verify independent retries and READY-last publication.

### Increment E — Historical indicator contract and rollout

- Add business date and immutable approved input identity to the indicator contract.
- Update producer, Analyzer consumer, storage identity, manifests, lineage, and compatibility tests.
- Block indicator backfill until exact-version reads and unambiguous dated outputs are proven.

### Increment F — Operator progress and recovery

- Expose preview classifications, request progress, per-date failures, skips, and retries.
- Permit retry of only eligible failed/repair-required partitions.
- Add bounded metrics and structured logs for plan size, classification counts, wait duration, and terminal outcomes.

These increments must not be implemented as one pull request because persistence/concurrency, Kafka contracts, and operator surfaces have distinct risk and verification boundaries.

## Likely Files and Modules

Platform:

- `apps/core/src/main/java/com/omni/platform/modules/scheduler/services/ManualJobTriggerService.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducer.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/producers/JobProducerRegistry.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/`
- `apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/`
- `database/migrations/`

Cross-service consumers and shared contracts:

- `apps/ingestor/app/handlers/stock_prices.py`
- `apps/ingestor/app/handlers/intraday_eod.py`
- `apps/analyzer/app/indicators/`
- `libs/contracts/proto/`
- `libs/py-common/`
- `configs/shared/s3-paths.yaml`

Canonical documentation:

- `docs/flows/001-job-execution.md`
- `docs/flows/002-stock-sync.md`
- `docs/flows/003-indicator-signal.md`
- `docs/flows/005-intraday-eod.md`
- `docs/data/001-kafka-contracts.md`
- `docs/data/002-data-lake.md`
- `docs/data/003-database.md`

## Repository Guidance Updates

Implementation must review and update, where applicable:

- [`AGENTS.md`](../../AGENTS.md);
- [`CLAUDE.md`](../../CLAUDE.md);
- [`.roo/rules`](../../.roo/rules);
- [`docs/README.md`](../README.md);
- affected job, data-lake, Kafka, database, and flow documentation listed above.

No repository-guidance change is required for this planning-only document because it does not yet alter runtime architecture or agent workflow. Guidance synchronization becomes mandatory in the implementation increments.

## Verification

The planning baseline originally ran no executable verification. Subsequent implementation work must use declared Nx targets and record an attributable coverage report rather than inferring coverage from test-file presence.

Critical Plan 028 components must each achieve **greater than 80% line coverage and greater than 80% branch coverage** in the owning increment's attributable report. The threshold applies independently to:

- range validation, trading-calendar expansion, and bounded plan sizing;
- stable job/dependency key validation, bidirectional traversal, graph expansion, topological ordering, and cycle rejection;
- dated work identity and history/manifest completeness classification;
- active-work uniqueness and concurrent duplicate fencing;
- separation of `observedAt` from `businessDate`;
- existing-producer dispatch adaptation and preservation of scheduled `nextRunAt`;
- exact-date/exact-version dependency resolution;
- stock-price and intraday-EOD backfill adapters;
- indicator date/version contract and historical output isolation when Increment D is enabled.

Aggregate project coverage cannot substitute for a critical component below either threshold. Generated code, DTO-only accessors, migrations, and framework bootstrap code may be excluded, but exclusions must be explicit in the coverage configuration and evidence. Safety-critical branches such as duplicate-active, stale, repair-required, cycle, unsupported-job, invalid-range, waiting-input, terminal-blocked, retry, and READY-last failure behavior require direct assertions even when the numerical threshold is already met.

Before implementation verification, inspect the exact Nx targets declared by each affected project. Expected gates include, subject to explicit owner approval:

- focused Platform unit tests for range validation, DAG ordering, cycle rejection, history classifications, active-work fencing, and runtime/business-date separation;
- PostgreSQL integration tests for concurrent duplicate requests, persistence uniqueness, claim/outbox fencing, retry attempts, and legacy-history treatment;
- producer tests proving scheduled behavior remains unchanged and backfill does not update scheduled `nextRunAt`;
- Ingestor tests for stock-price chunk/range semantics and intraday exact-date isolation;
- Analyzer tests proving indicator messages read the exact approved input version and cannot overwrite another historical date ambiguously;
- contract lint, generation check, breaking check, and producer/consumer compatibility fixtures if shared contracts change;
- manifest and storage tests proving validation and immutable publication occur before READY replacement;
- graph impact analysis before contract/persistence changes and graph change detection after edits;
- static documentation-consistency audit after documentation synchronization;
- an Nx-owned coverage target (to be added because Platform currently exposes only `test`, `build`, `build-fast`, and `serve`) that generates machine-readable XML plus an HTML report and fails when any listed critical component is at or below 80% line or branch coverage;
- approved project lint, test, coverage, and build targets through Nx;
- exact-head CI and runtime/object-storage evidence before any increment is marked complete.

Current evidence: `platform:test` was executed after this plan update and exposed three attributable scheduler-outbox compatibility failures. Focused dispatcher tests passed. No Plan 028 implementation or Plan 028 coverage report exists yet, so the greater-than-80% requirement remains unresolved rather than passed.

## Acceptance Criteria

1. A request selects an existing job definition; no duplicate backfill definition or `BACKFILL_*` job type is introduced.
2. Scheduled execution behavior and schedule advancement remain unchanged.
3. Backfill carries separate actual runtime and historical business-date values.
4. The planner validates a bounded range, trading dates, job support, and expansion limits before persistence.
5. `dryRun` returns deterministic dependency/work classifications without creating execution or outbox rows.
6. Every definition has a unique stable `jobKey`; every graph edge has a deterministic `dependencyKey` and valid upstream/downstream foreign keys.
7. Job topology is no longer runtime-owned by `configJson.dependsOnJobs`; migration parity is proven before the normalized graph becomes the sole source of truth.
8. Direct dependencies, direct dependents, complete ancestors, and complete descendants are traceable by key with deterministic paths and bounded cycle-safe traversal.
9. Dependency graphs are deterministic, cycle-safe, exportable as Mermaid, and planned in topological order.
10. Producers are resolved through the existing producer registry and reuse the normal execution/outbox path.
11. No producer recursively dispatches another producer, and no scheduled claim is reused across a date loop.
12. Duplicate/completeness checks use job definition, work identity, business date, and where required exact input version; they do not infer business date from `triggeredAt`.
13. `ALREADY_COMPLETE` requires matching SUCCESS history plus valid exact output manifest and lineage.
14. Concurrent requests cannot create duplicate active work for the same canonical dated identity.
15. Downstream work waits for the exact dated/versioned dependency and never substitutes the latest unrelated READY input.
16. Stock-price and intraday-EOD backfills preserve existing writer ownership, date semantics, validation, and READY-last publication.
17. Indicator backfill remains blocked until exact-date/version input and unambiguous historical output semantics are implemented and tested.
18. Parent/request progress distinguishes skipped, active-linked, waiting, blocked, failed, repaired, and successful partitions.
19. Producer, consumer, persistence, migration, manifests, storage builders, tests, configuration, generated Mermaid, and canonical docs are updated together for each changed contract.
20. Every critical Plan 028 component listed under Verification has independently measured line and branch coverage greater than 80%, with direct assertions for its safety-critical branches and no reliance on aggregate project coverage.
21. Required targeted tests, Nx test/coverage/build checks, graph analysis, documentation consistency, exact-head CI, and runtime evidence pass and are recorded before the owning roadmap increment is completed.

## Risks and Mitigations

- **Audit-time corruption:** never substitute historical dates for runtime timestamps.
- **Duplicate active work:** enforce canonical dated identity transactionally, not only through preflight reads.
- **False completion:** require history plus manifest/output lineage.
- **Dependency drift:** pin exact approved input versions per dated work item.
- **Graph cycles:** validate before creating executions.
- **Large fan-out:** bound range and work count, then chunk deterministically.
- **Provider overload:** apply per-job chunk and concurrency policy.
- **Historical overwrite:** require date/version-aware output identity before enabling a job type.
- **Legacy ambiguity:** do not derive business date from legacy `triggeredAt`.
- **Schedule mutation:** use manual/backfill preparation that preserves definition scheduling fields.

## Stop Conditions

Stop implementation and request an owner decision if:

- indicator historical outputs require a breaking storage-path migration;
- a job's dependency graph cannot map an exact business date to an immutable input version;
- active-work uniqueness requires destructive history cleanup;
- provider limits make the approved range unsafe without a materially different batching strategy;
- compatibility requires a coordinated breaking Kafka cutover rather than an additive migration;
- two existing definitions ambiguously claim ownership of the same dataset partition.

## Rollback

- Disable affected job types in the backfill capability allow-list.
- Stop accepting new backfill requests while allowing already published work to finish safely.
- Preserve execution histories, request records, immutable dataset versions, and previous READY pointers.
- Do not delete published data as an orchestration rollback.
- Revert additive API/producer behavior only after draining or explicitly blocking pending backfill outbox rows.
- Keep scheduled execution operational throughout rollback.


## Field/DTO Inventory and Bounded Delivery — 2026-10-07

Design inventory, not a claim that fields are missing from source or already implemented. [Cross-plan register](../reference/002-planned-field-dto-impact.md) defines ADD/REUSE/SEMANTIC/DERIVED/UNRESOLVED and LOW/MEDIUM/HIGH impact. Exact names/types/nullability/defaults/transport must be reconciled with source before code or migration. Existing statuses, dependencies and owner gates remain unchanged.

| Surface | Field/DTO change | Impact and behavior |
| --- | --- | --- |
| Job graph identity | jobKey/job_key, dependencyKey/dependency_key, upstream/downstream foreign keys, dependency_kind/required | HIGH persisted topology ownership change; replaces legacy configJson.dependsOnJobs, requires independent migration/cycle/portability review. |
| Dated execution | businessDate, executionMode, backfillRequestId, runKey; optional exact inputDataVersion, existing work/execution identity | HIGH identity/idempotency; triggeredAt stays audit time and cannot substitute for business date. |
| Backfill request/preview | jobDefinitionId, fromDate, toDate, optional work filter, dryRun, requestedBy; classified counts/items/reasons and skipped/active references | MEDIUM read-only preview / HIGH bounded enqueue; exact DTO types/limits unresolved. Actor comes from authorized context, not trusted client text. |
| Historical producer inputs | Approved business date/input lineage and provider-specific supported history behavior | HIGH algorithm/data contract; reuse producers only where historical semantics are supported. |

Small tasks: stable-key/graph migration (separate prerequisite) → read-only classified preview → dated identity/persistence → bounded enqueue via existing dispatcher → one job-type rollout → progress/recovery. Do not bundle graph ownership rewrite, all historical producers and operator recovery into one release. Still proposed and not roadmap-scheduled.
