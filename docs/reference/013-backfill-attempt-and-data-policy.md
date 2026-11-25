# Backfill attempt and data policy

## Current contract - November 19, 2026

Owner-approved amendment with focused local verification: BACKFILL-POLICY-20261119
passes 6/6 approved checks. Platform passes 211 tests (163 PostgreSQL graph and
48 controller); Console passes 126 tests plus typecheck/lint; Ingestor passes 132
tests; changed-file formatting passes. No failures or skips in the selected
Platform suite. P14-I10 remains `in_progress`; full worker E2E, CI, deployment and
whole-component coverage qualification are not established by this checkpoint.
Earlier November 7 results below describe the former contract.

`POST /api/v1/jobs/backfills` accepts one optional `dataPolicy`:

- `PREFER_EXISTING` (default): new EOD work uses exact-day persisted rows when
  available and fetches missing rows.
- `REFRESH`: EOD must fetch provider data, even when stored rows cover the day.
  Provider failure or a missing provider day cannot fall back to cached rows.

`rerun` and `force` are removed from new HTTP requests and the Console. Supplying
removed or unknown fields is rejected. The 202 response returns `dataPolicy`.
Policy participates in canonical request idempotency, not `atomicWorkId`.
Same actor/key/scope/policy replays the accepted request; changing scope or policy
under that key conflicts. Use a new key for a new request or terminal retry.

```json
{
  "goal": "SYNC_INDICATORS",
  "sectorCodes": ["BANK"],
  "sectorLevel": 2,
  "dateFrom": "2026-10-19",
  "dateTo": "2026-10-23",
  "dataPolicy": "PREFER_EXISTING"
}
```

## Sharing between requests

Sharing compatible PENDING/RUNNING attempts with live owners is automatic across
requests. Scope, analytical parameters, exact upstream attempt binding and storage
policy must match. Terminal attempts are never reused. A stronger existing EOD
attempt may satisfy a weaker request.

If a later request requires REFRESH, an existing PREFER_EXISTING EOD attempt may
be promoted only while it is PENDING and its single outbox message has never been
claimed or published: zero attempts, no claim markers, no approved inputs or frozen storage write bases, no
worker receipt, timeout, registration or completion evidence. Promotion follows
run -> parent/child -> outbox lock order with SKIP LOCKED. If eligibility or locks
cannot be established, a new EOD attempt is created, and downstream bindings use
that attempt. Dispatched commands and frozen lineage are never rewritten.

The sealed outbox payload remains immutable. The one-child attempt's parent
stores `dispatchDataPolicy=REFRESH`; publish/replay reconstructs the effective
`metadata.backfillPolicy` from this durable overlay. Approved-input and storage-base
binding remain unchanged. Shared request membership and each request's accepted
policy remain immutable; promotion strengthens the shared work rather than
rewriting the earlier request.

Only EOD consumes this data policy. Symbols always fetches its current exchange
snapshot and may share compatible in-flight work regardless of the requested EOD
policy. Analyzer calculations use frozen upstream attempts, so a changed EOD
attempt naturally changes downstream sharing. Completed Symbols snapshots require
a new attempt without an extra rerun flag.

## Worker compatibility and data safety

New commands contain `backfillPolicy.dataPolicy` and `verifyEodParquet=true`, without
new force/rerun fields. Ingestor already consumes PREFER_EXISTING/REFRESH. Previously
accepted request JSON and emitted commands retain read compatibility, including
legacy commands without dataPolicy; they are not rewritten or reinterpreted at
the worker. Unknown policies fail before provider fetch or Parquet write.

Refresh is scoped to requested business days and preserves unrelated history.
Exact-date validation, registration ACK before SUCCESS, PostgreSQL-only authority,
READY-last, frozen lineage and versioned write ownership remain mandatory.
Deploy matched Platform/Ingestor/Console before using the new HTTP contract.
No retained job, schedule, provider operation or deployment is activated here.

## Current impact and regression inventory

| Boundary          | Source and regression scope                                                                                                                                       | Current evidence                                          |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| Platform HTTP     | IndicatorBackfillController; defaults, both policies, rejected removed/unknown fields, invalid scope, normalized response                                         | PASS selected local suite                                 |
| Graph admission   | GraphWorkRequestService; cross-request sharing, pending promotion, claimed/published/running/terminal refusal, concurrent acceptance, immutable membership/intent | PASS selected local suite                                 |
| Dispatch/replay   | SchedulerOutboxService; durable parent policy overlay with frozen inputs/storage bases                                                                            | PASS selected local suite                                 |
| Expansion         | WorkExpansion; request-level policy excluded from analytical parameters/identity                                                                                  | PASS selected local suite                                 |
| Console           | BackfillForm/types/API/component fixtures; two policy values, Symbols scope, stable idempotency retries                                                           | PASS selected local suite                                 |
| Ingestor/Analyzer | Existing dataPolicy reader and exact approved-input execution; HTTP E2E fixtures migrated to the new contract                                                     | 132 Ingestor tests pass; Analyzer/full worker E2E not run |

Query Service, shared protobuf/Python libraries, physical storage paths, schema,
configuration flags, authorization and deployment ownership are unchanged.
Persistence uses existing execution JSON metadata; no migration is introduced.
The graph impact result is bounded (23 impacted files, 1395 unresolved call sites,
index built at the current HEAD before these edits), so source/contract inspection
is required alongside graph results. It does not prove isolation or test coverage.

Focused JaCoCo: controller 111/111 lines, 79/82 branches; graph request service
457/467 lines, 220/254 branches. Critical promotePendingDataPolicy is 13/13 lines
and 6/6 branches; bindApprovedInputs is 24/25 lines and 26/30 branches. The selected
suite covers lock contention, rollback, ambiguous publication, frozen write bases,
claim/retry envelope stability and malformed binding refusal.

Whole WorkExpansion branch coverage is 112/157 (71.34%); whole
SchedulerOutboxService coverage is 139/239 lines (58.16%) and 74/146 branches
(50.68%). These remain below the full-component 80% gate and are not hidden by
higher method or aggregate coverage. Additional broad component coverage and
worker-chain evidence remain required before I10 acceptance. Local artifacts:
`.agent/reports/backfill-policy-20261119/` and check_result gate
`BACKFILL-POLICY-20261119`. Static documentation review finds no new inconsistency
in the changed current contract/links/status claims; historical evidence remains
explicitly separate. No increment completion or deployment is claimed.

## Historical November 7 evidence - former flags contract

The following results and interpretation describe the superseded rerun/force
contract. They are retained for traceability, not current verification evidence.

## Impact and verification inventory

| Production boundary                       | Regression source / intended branches                                                                                                                  | Executed now                            |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------- |
| IndicatorBackfillController               | Controller tests: omitted/false flags, rerun, force overriding rerun, normalized policy/idempotency response                                           | PASS: 21 controller cases               |
| GraphWorkRequestService and WorkExpansion | PostgreSQL policy cases: in-flight sharing, distinct rerun/force attempts with unchanged work IDs, same-key policy conflict, persisted worker policy   | PASS: 68 PostgreSQL + 9 expansion cases |
| Ingestor stock_prices                     | covered/missing EOD across legacy-force, legacy-normal, PREFER_EXISTING and REFRESH; changed fetched value; unknown policy rejects without fetch/write | PASS: 40 focused cases                  |

Graph impact over four source files was truncated to 60/72 impacted nodes and had
979 unresolved calls. Cross-service reconciliation: Platform owns the HTTP/attempt
policy and payload; Ingestor applies provider refresh; Analyzer still computes
Indicators from frozen EOD with no algorithm change. Query and Console have no
source/UI change. Shared Python and protobuf schemas are unchanged; metadata is
additive. Existing storage paths, database schema/reuse_policy, dataset ownership
and configuration defaults remain. Operations require matched workers and keep
the existing writer-drain/reader readiness boundaries.

### Actual cross-service policy execution

BackfillDataPolicyIntegrationTest adds six HTTP/Kafka/Python worker/MinIO Parquet/
registration/READY cases to platform:test-integration, alongside the existing
JobRunCrossServiceIntegrationTest and I10WorkReadinessIntegrationTest. Only the
external market provider is stubbed. The fixture records provider calls and reads
actual canonical Parquet before/after the real handler; it does not substitute
PostgreSQL readiness for a file read or replace computation/publication code.

| Case                                                       | Observed result                                                                                            |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Default, covered day and no PostgreSQL READY for that work | SUCCESS and new exact-day EOD/Indicators READY; zero provider calls despite a provider-failure switch      |
| rerun, covered day and no prior PostgreSQL READY           | Same Parquet reuse behavior with rerun policy; no provider call                                            |
| File exists but requested day is absent                    | Exactly one fetch, day row appears and both outputs become READY; unrelated rows preserved                 |
| force with an existing day                                 | Exactly one fetch, canonical close changes 177 to 777; unrelated row digest preserved; new exact-day READY |
| force provider failure                                     | Expected FAILED request; cached row remains 777, no new-attempt READY and Indicators not published         |
| force provider response lacks requested day                | Same fail-closed behavior; cached day is not used as fallback                                              |

All six new cases and the two existing cross-service cases pass: 8/8, no skips.
Seven request probes (one seed plus six cases) and five provider invocations
include the seed, missing-day fetch, successful refresh and two expected failures.
Initial fixture failures (generic reflection overload and missing symbol sector
classification) are retained separately; the final fixture uses explicit Object
targets and provider-supplied classification. Production guard semantics were
not relaxed to make the tests pass.

The owner-authorized manifest `P14-I10-policy-integration-20261107` concludes PASS,
required=3/pass=3 for integration/postgres-policy, test/ingestor-policy and
integration/kafka-chain. Executed target scopes:

```text
npx.cmd nx run platform:test --skip-nx-cache --args="--tests com.omni.platform.GraphWorkRequestsPostgresTest --tests com.omni.platform.modules.scheduler.controllers.IndicatorBackfillControllerTest --tests com.omni.platform.modules.scheduler.expansion.WorkExpansionTest"
npx.cmd nx run ingestor:test --skip-nx-cache --args="-k atomic_stock_work --no-cov"
npx.cmd nx run platform:test-integration --skip-nx-cache
```

Both Platform commands additionally used --rerun-tasks and the ignored
`--init-script ../../.agent/operations/i10-policy-isolated-build.gradle`, redirecting
build output away from the live devtools classpath. All test databases/brokers/
object stores were disposable. JUnit, focused JaCoCo, source snapshots, provider
call logs and Parquet proofs are retained under the manifest's ignored artifacts.
Initial failure reports are separate from the final e2e results.

Focused Java class coverage: controller 100% line/100% branch; graph service
98.10%/86.50%; WorkExpansion 91.34%/78.98%. WorkExpansion class branch coverage
remains below the default 80% threshold; focused policy execution does not close
full-I10 attributable coverage. Python ran without coverage instrumentation;
no Python numeric pass is inferred. No lint/format, standalone build target,
live provider call, migration, production backfill or deployment was run.

Registry, Plan 030, reference/009, release notes, flow, Kafka/deployment and service
guidance describe the policy separation. Architecture/ADR/data-lake/database and
agent rules need no new ownership/schema decision; they retain all activation and
READY safeguards. The closed-owner checkpoint now has focused PostgreSQL evidence;
its complete acceptance/cutover gate remains open.
Static changed-scope review is not I10 acceptance, deployment or live evidence.

### Bounded graph candidate lookup

GraphWorkExpansion previously cached producer expansion but rescanned every EOD
candidate for each Indicator symbol. A valid three-day, 1,357-symbol request could
therefore exceed GRAPH_CANDIDATE_BOUND_EXCEEDED despite a small selected graph.
The cache now indexes exact keys and exchange membership, and memoizes sector
membership by dependent scope/date/level. Final scope/date matching, upstream
strategy selection, complete definition bindings and frozen identities remain.
The 200,000 generated-row/visit budgets, 20,000 works, 100,000 edges and 1,024
expansions are unchanged; actual membership scans are still charged to the budget.

The regression exercises 4,071 targets, 8,144 selected works and 8,142 edges,
including duplicate upstream definitions, exact-day edges and current Symbols
snapshot exchange mapping. Local check manifest:
`P14-I10-graph-candidates-20261107` concludes PASS: 16 graph tests and 68
PostgreSQL request tests, zero failures/errors/skips. The platform:test command
selected GraphWorkExpansionTest and GraphWorkRequestsPostgresTest, with the same
isolated Gradle init script and --rerun-tasks described above. JUnit, JaCoCo and
source snapshots are retained in the manifest artifacts. GraphWorkExpansion
coverage is 93.96% line/83.51% branch; the new Expansion helper is 96.88% line/
71.88% branch, below the default 80% branch gate. This bounded regression pass
does not establish full increment coverage. Kafka/worker E2E was not rerun for
this graph-only correction; earlier policy E2E evidence predates this source.

Blast radius: Platform planning and request preparation use the same indexed graph;
PostgreSQL request regressions cover persisted membership/attempt behavior.
Analyzer/Ingestor algorithms, Query Service and Console are unchanged because
the resulting work/edge contract is preserved. Shared contracts/libraries,
configuration, database schema, storage paths and dataset ownership are unchanged.
Operations retain all existing bounds and activation controls. No live request,
Kafka cleanup, migration, deployment or I10 completion is claimed.

Documentation review: Plan 030 describes indexing; this reference and release
notes record the bounded correction. Registry/roadmap remain in_progress with the
same dependencies; architecture/ADR, flows, Kafka/data-lake/database, deployment,
development ownership, service guides, indexes and agent guidance require no
contract or activation amendment for an internal lookup optimization.
