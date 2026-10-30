# P14 review — source and planned scope, 2026-10-30

Scope: current feat/P14-WIP workspace including uncommitted I10/identity planning.
This is static review plus SELECT-only SQL and an owner-selected Omni Console
dashboard/API source delivery. An offline HTML companion is retained.
Added test source is NOT RUN: no tests/build/lint/format, SQL execution, browser validation, retained migration,
configuration activation, deployment or new coverage evidence was performed.
Graph tools are unavailable; the service/contract impact below is manual reconciliation.

## Findings

### [P1] Non-signal terminal selection can suppress uncovered scheduled work

Source: [GraphScheduledEntries.java:34](../../../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/GraphScheduledEntries.java#L34),
especially line 39; [GraphWorkExpansion.java:174](../../../../apps/core/src/main/java/com/omni/platform/modules/scheduler/expansion/GraphWorkExpansion.java#L174).

When graph expansion is enabled, covers() accepts a JobType dependency immediately
for every upstream except SYNC_SIGNALS. Scope/date/parameter checks below that return
are signal-specific. Example: active scheduled stock-price preset covers all HOSE
symbols, while its only scheduled indicator descendant has scope SYMBOL/HOSE-VCB.
The selector removes the standalone stock preset, yet the indicator graph only binds
stock work matching VCB. Other stock symbols stop receiving their scheduled work.
Similar omissions are possible for non-signal timeframe/classification/date presets.
This is not a claim that the unchanged standard seed currently misses symbols.

The earlier strategy fix correctly retains ICHIMOKU/CONFIRMED entries, but does not
generalize coverage proof to other JobTypes. Existing GraphScheduledEntriesTest
covers restricted signal/evaluation inputs; no executed or newly added regression
proves this non-signal case. Finding is source-derived, not reproduced in this review.

Required fix: suppress a preset only when resolved downstream work provably covers
its complete intended membership/date/parameter set; conservatively retain it when
coverage is unknown. Add regressions for narrow indicator vs broad stock, sector
level/timeframe mismatch, and restricted dates. Recheck default seed selection and
request counts. Do not silently fix this runtime in a diagnostics-only delivery.

### [P2, known open gap] Published work can remain reusable indefinitely

Source: [GraphWorkRequestService.java:176](../../../../apps/core/src/main/java/com/omni/platform/modules/scheduler/work/GraphWorkRequestService.java#L176)
and reconcile() in that file.

acquire() reuses PENDING/RUNNING attempts with matching upstream bindings without a
publication-age limit. Reconciliation closes terminal attempts and propagates failed
upstreams; it does not expire published-but-unfinished work. If a worker never sends
terminal status, later requests can attach to that attempt and also wait indefinitely.
I10 watchdog is planned, not implemented. This is a previously identified gap,
not a second new defect. Diagnostic age >30 minutes must not itself be displayed as
FAILED or proof that MinIO writes stopped.

Required gate: I10 timeout/late-status/write-ownership behavior and regression evidence;
until then use read-only investigation and controlled operator recovery.

### [P1 safety limitation, previously deferred] Atomic work is not output exclusion

Source: [write_day](../../../../libs/py-common/py_common/storage/atomic_work.py),
[stock_prices.py](../../../../apps/ingestor/app/handlers/stock_prices.py),
[transition records](../../../../apps/analyzer/app/sector_transition/records.py).

Different dated work identities can read/merge/write the same mutable history object.
In-flight dedup only serializes acquisition of equal work IDs, not different days,
parameter sets or output-sharing jobs. If concurrent workers read the same old object,
last writer can discard the other's additions. Sequential append immutability does
not prevent that race. Signal history has a process-local lock; it is not a distributed
writer lease. This review did not reproduce concurrency and does not imply every
single-worker deployment currently races.

The indicator repair now checks the approved EOD version against exactly the bytes
decoded; the old general claim that this path has no byte binding is obsolete.
That fail-closed read does not add immutable versions or write exclusion to other
paths. Keep safe worker ownership/concurrency restrictions; prove output-key
exclusion/version safety before expanding concurrency or relying on I10 DB READY.
Writer/P12 is still deferred and metadata registration alone does not solve this.

## Increment reconciliation

| Increment | Canonical state | Source/output present | Remaining boundary |
| --- | --- | --- | --- |
| I1 | completed | Code-owned topology, traversal/validation. | Preserve declaration authority; no second mutable graph catalog. Historical registry evidence, no fresh execution here. |
| I2 | completed | Static DAG dispatcher contract and definition mapping. | Old frozen paths keep their version/fingerprint semantics. |
| I3 | completed | Durable JobRun preparation, target/ancestor steps and closure guards. | preparation remains default false; historical completion is not approval to activate current HEAD. |
| I4 | completed | Planned admission with DependencyRegistry, exact input approval, fenced outbox/claim/renew. | Graph completion is not dataset READY; guard paths remain required. |
| I5 | completed | Existing v1 SQL, measured historical benchmark and bounded read-model handoff. | New v2 assets below are unexecuted and not covered by old benchmark evidence. |
| I6 | superseded | Read-model scope merged into I5. | No separate pending implementation or status promotion. |
| I7 | completed | Legacy identity cleanup/strict FK migration; recorded local reverify gate. | Retained backup/quiescence/migration gates remain operational obligations. |
| I8 | verification_pending | Common request/date/scope expansion, atomic JSON envelope, frozen snapshots, worker dated units. | Current-source cross-service regression, attributable coverage, replay/retained migration and rollout still open. |
| I9.a | part of I9 | Graph-to-expansion composition; one atomic work per independent JobRun. | Historical planning-only PASS does not verify subsequent full runtime. Fix/verify uncovered non-signal entry case. |
| I9.b | part of I9 | Shared in-flight attempts, fixed upstream attempts, request membership, cancel/retry and reconciler. | Timeout gap, overlap/concurrency/restart/cancellation/lease parity; new SQL is evidence preparation only. |
| I9.c | part of I9 | Preset consolidation, schedule retirement, history aliases and conflict-checked rollback (V18). | Default off; retained migration/seeder/history/rollback and coarse-lock performance evidence required. |
| I9 overall | verification_pending | Full a/b/c source and strategy/outcomes follow-ups. | Must close I8 and all I9 behavior/coverage/operational gates, not just compile or count tests. |
| I10.a | pending | Plan/ADR only: metadata topic, bounded registration arrays, Platform inbox/upsert/status correlation. | No topic/schema/consumer or completion gate implemented. |
| I10.b | pending | Plan only: PostgreSQL readiness, exact work/date coverage, API/reader cutover, 12h reconciliation. | MinIO manifest remains active; version/write-ownership safety and backfill/shadow/cutover unresolved. |
| I10.c | pending | Plan only: 2min watchdog, 30min publication deadline, diagnostics/late fencing. | No execution timeout currently applied. |
| Trace / query provenance | reserved only | Naming/extension plan: atomicWorkId, atomicAttemptId, queryId, optional traceContext. | No tracing collector, query metadata migration or query-level trace persistence. P11/P12 not reactivated. |

## Identity and version audit

- Wire uses atomicWorkId; Platform snapshot uses workIdentity and
  workExpansion.atomicWorkId. Python carry_work_status adds metaJson.atomicWorkId.
  These are aliases of the same hash, not separate work keys.
- Graph parent atomicAttemptId maps to atomic_work_attempts.id; current child wire
  does not guarantee that field. Resolve persisted membership rather than inventing it.
- Graph requester graphRequestId is separate from Plan 024 diagnostic requestId.
- Query Service uses queryId publicly and query_id/data_versions in SQLite/audit.
  A query may consume versions from many works; no single producing-work ID suffices.
- JobService preserves structured acceptedExpansion, expandedWorkIds, workExpansion
  and approvedInputs specially. Other metadata uses MetadataUtils string conversion;
  future typed traceContext needs deliberate support, not an assumption about JSONB.
- Indicator read_bound_dataframe validates version using the exact byte buffer decoded.
  Do not reuse the old failed integration result as proof the repaired HEAD still fails.
- AtomicWorkId excludes trace/attempt/definition/cron identity. Re-execution preserves
  work identity and creates a new attempt. Output coverage T can differ from work day D.

## SQL and dashboard source prepared

[README](README.md) documents parameters, safety and local-use handoff.
The v2 snapshot covers request page -> distinct attempt -> one-hop dependency/owner
state, parent/run status, publication/lease/retry counts and identity checks. It does
not execute admission or call a mutating repository method. Separate aggregates avoid
multiplying counts by owner/outbox/dependency joins.

Published unfinished inventory covers old legacy/I8/I9 work independently of the
request creation window. Work inspection exposes exact upstream attempt bindings and
persisted input approvals. Every output has explicit caps; missing schema, partial
samples, unknown worker/data state and failed query are not successful zero backlogs.

Owner selected Console after the initial offline preparation. Work Operations now
loads bounded snapshots and work provenance through an authenticated Platform API.
Canonical packaged SQL is shared with psql wrappers. No browser DB connection, fake
runtime counters, polling, repair/cancel/retry or metrics exporter is added. Existing
Plan 029 truthful-stage/capacity scope remains separate; no deployment is claimed.
Prepared assets are not a new increment and do not reopen historical I5 completion.

## Cross-service impact and recommended acceptance matrix

| Surface | Review outcome / diagnostics change | Owner-local evidence still needed |
| --- | --- | --- |
| Platform topology/preparation/planner/claims | Scheduler behavior unchanged; new diagnostics service/controller GETs with bounds/read-only transactions/timeouts. Selector finding remains unfixed; expired-token WAITING/BLOCKED repair is present. | I8/I9 selection, overlap, fixed bindings, failed upstream, concurrent terminal/cancel/retry, all claim paths and stale leases. |
| Ingestor / Analyzer / py-common | No production changes. Dated history writes, byte-bound indicator read and outcomes immutable/as-of behavior reviewed. | Paired envelope/canonical identity, date/lookback/history retention, output collision, valid-empty, Kafka/status recovery and approval/bytes mismatch. |
| Query Service / Console | New Console Work Operations navigation/view/client; existing auth proxy reused. No Query Service/cache/SQLite/trace change. | Version/discovery parity at future I10 cutover; traces reserved only. Browser import/display checks before operational use. |
| Shared contracts/config | Additive HTTP diagnostic DTOs only; no topic/proto/generated/path/env/flag change. Existing JSON Kafka wire retained. | Future I10 paired producers/consumer, limits, stream orders, auth and alias conflict fixtures. |
| PostgreSQL / operations | Shared packaged SELECT-only diagnostic SQL + psql wrappers, V18 prerequisite, query/transaction/lock bounds; no migration or query run. | Real PostgreSQL results for empty/shared/failed/partial/legacy states, plans/buffers/timeouts, retained rollout with declared controls. |
| Documentation | Plan/registry statuses unchanged; link review/v2 read projection and distinguish future I10/trace panels. | Static graph change/impact tools remain unavailable; refresh graph proof later, never claim PASS from manual source alone. |

Recommended checks are NOT RUN. Reuse declared Nx targets and existing
GraphScheduledEntriesTest, GraphWorkExpansionTest, GraphWorkRequestsPostgresTest,
DefinitionConsolidationTest, AtomicWorkExpansionPostgresTest, JobRunAdmissionPostgresTest,
JobRunReadModelPostgresTest and paired Python regressions. Add behavior fixtures for the
new selector finding and v2 diagnostic queries when verification is approved. Require
an impact/source/test matrix and attributable >=80% line/branch for critical runtime
components; SQL/HTML coverage needs executed behavioral cases, not Java percentages.
Record I8/I9 results via tools/check_result.py and confirm the conclusion is ready;
agent must not infer completion from source, old logs or this dashboard.

## Documentation review and recommendation

Read canonical registry, Plan 030/Plan 029/Plan 027/Plan 024, ADR-004/008, architecture,
Kafka/data-lake/database/flow/deployment references and identity inventory. Existing
I10/trace edits are preserved. Current operational docs continue to describe manifest
readiness; future PostgreSQL rules are explicitly planned. Platform/Console guides, architecture/database/flow/deployment and ownership docs
now describe the additive HTTP/UI source. No Kafka/generated schema, tooling or
permission change requires new AGENTS/CLAUDE/editor rules.

Do not remove activation flags or mark I8/I9 complete. First address non-signal entry
coverage and obtain current-source local acceptance evidence. I10 implementation can
then follow its registered dependency and staged reader-cutover gates. No commit/push
or branch mutation is included in this request.

## Console follow-up source/test matrix — not executed

| Changed surface | Regression source / pending local gate |
| --- | --- |
| AtomicWorkDiagnosticsService | ValidationTest: invalid range/limits/future clock/hash rejected before DB; GraphWorkRequestsPostgresTest: actual packaged SQL, shared owner counts, read-only pagination/provenance. DB outage/timeout/schema/truncation cases remain required. |
| GraphWorkOperationsController / security boundary | GraphWorkRequestsPostgresTest: unauthenticated GETs, authenticated snapshots and invalid limit; no new security bypass. |
| Canonical resources + psql includes | Same files used by JDBC and CLI, named values only; PG16 execution/aggregate/legacy/error/performance cases pending. |
| Console App/api/operations/types/view/styles | WorkOperations.test.tsx: unknown/empty/errors, window-preserving pagination, selected work; App.test.tsx navigation. API request encoding, out-of-order/unmount response, browser responsive/accessibility and broad Console regression still need executed evidence. |

Recommended local commands (NOT RUN): nx run platform:test; nx run platform:coverage;
nx run omni-console:test; nx run omni-console:typecheck; nx run omni-console:lint;
nx run omni-console:build. Source presence does not prove successful compile or UI.
