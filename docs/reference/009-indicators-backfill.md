# Indicators sector/date backfill

November 9 extension in progress: [Signals backfill](016-i10-signals-backfill-closure.md).
The API also accepts SYNC_SIGNALS with optional targetDefinitionId selecting one
active canonical strategy definition. Ambiguous/mismatched selection is 409.
Signals goals additionally require legacy Signals/Evaluation writers paused/drained.
Current worker-chain verification is pending; older Indicators evidence is historical.

For UI request discovery and compact polling, use the
[request list API](014-atomic-request-polling.md); full graph/member detail remains separate.

## Root execution counters

At request settlement, GraphWorkRequestService aggregates recordsSynced and
recordsSkipped over the request's is_target members and writes the root counters
in the finalization transaction. Indicators roots therefore exclude EOD/Symbols
ancestor records. Each counter remains null if any target has an unknown value;
known zero is preserved. Failure settlement uses the same aggregation rule.
Existing terminal roots are immutable and are not backfilled by this correction.

Regression: GraphWorkRequestsPostgresTest.rootCountsOnlyTargetsAndPreservesUnknownCounters
covers multiple targets, ancestor exclusion, zero, independently missing counters
and repeated reconciliation. The selected platform:test run of
GraphWorkRequestsPostgresTest passes 70/70 cases, no failures/errors/skips,
using the isolated Gradle init script and disposable PostgreSQL. Initial fixture
failure selected one symbol instead of two; correcting the fixture required no
production guard changes. JUnit/JaCoCo reports are retained locally under
`.agent/reports/20261108-target-counters`; no new numeric coverage gate is claimed.
Platform owns the change; Analyzer/Ingestor metrics publication, Query/Console,
shared contracts/libraries, schema, storage, configuration and deployment are
unchanged. The existing API fields now receive target aggregates on new settlement.
Plan 030/registry/roadmap retain I10 in_progress; architecture/ADR, flow, Kafka/data,
deployment/development ownership, service guides, indexes and agent guidance need
no ownership, schema or activation amendment. No live history repair is performed.

Current request policy: [separate rerun from provider refresh](013-backfill-attempt-and-data-policy.md).
Both flags default false; force implies rerun and fresh provider EOD. Selected
local policy/PostgreSQL/worker E2E checks pass; full I10 coverage/cutover remains
open. Older dated runs below exercised old semantics.

Current closed-owner follow-up: [safe settlement, reuse and attempt/output
diagnostics](012-i10-closed-owner-reconciliation.md). Focused PostgreSQL regressions
pass; the older dated checks below do not cover that source change.

Source boundary: 2026-11-05. Owner-requested bounded addition to P14-I10;
I10 remains `in_progress`. This is not the general Plan 028 backfill rollout.
Feature defaults remain off. No retained-environment migration or service restart
is performed by this source change.

## Goal and outcome

Accept one authenticated, idempotent Indicators request for current active sector
membership over a bounded historical date range. Expand the static dependency
graph into Symbols -> dated EOD -> dated Indicators. Frozen attempt membership,
registration before SUCCESS and exact work READY gate downstream dispatch.

## API

`POST /api/v1/jobs/backfills` requires the trusted proxy `X-Omni-User` operator UUID
and `Idempotency-Key`. Apply the existing private jobs API deployment boundary;
the proxy must remove caller-supplied identity headers and inject its authenticated
operator. A metadata-reader bearer token does not grant this mutation permission.

```json
{
  "goal": "SYNC_INDICATORS",
  "sectorCodes": ["BANK", "STEEL"],
  "sectorLevel": 1,
  "dateFrom": "2026-09-01",
  "dateTo": "2026-09-30",
  "rerun": false,
  "force": false
}
```

Sector codes are illustrative: use actual active catalog classification values.
Codes normalize to uppercase, deduplicate and sort. Level is 1-4; request accepts
1-100 codes, at most 366 calendar days and no future dates. Existing expansion
limits still apply (5,000 work units per expansion; 1,024 graph projections and
20,000 graph work units). Split larger requests into bounded date/sector windows.
Empty target expansion or
any requested sector without active members rejects the request transaction.
Exactly one active canonical Indicators definition and compatible upstream
definitions are required. Acceptance compares the expanded target set with every
active requested-sector symbol/date pair; a preset that excludes part of that
membership rejects the transaction instead of accepting a partial backfill.
Sector membership is current at acceptance, not a
historical reconstruction. Historical market work skips weekends; current
Symbols/Metadata snapshots may run on any calendar day. Holidays are not inferred as
no-trade. Missing exact EOD dates never become READY by inference.

HTTP 202 returns `requestId`, `executionId`, `status=ACCEPTED`, `goal`, `rerun`, `force`
and `membershipPolicy=CURRENT_ACTIVE_AT_ACCEPTANCE`. Acceptance is not completion.
Poll `GET /api/v1/jobs/atomic-work/requests/{requestId}` for durable membership and
state. Same actor/key and canonical request replay the original execution, even
after failure; changed scope/rerun/force with that key returns 409. Retry uses a new key.
Invalid input is 400; absent/ambiguous target is 409; disabled/unavailable admission
or unpaused writers is 503. Rejected requests do not create audit-only graph rows.
Retired generic trigger routes remain 410 under TD-017.

## Attempt policy, datasets and metadata

Default rerun=false/force=false shares compatible unfinished attempts with live
requesters. A new EOD execution reuses a validated existing day or fetches if
missing. rerun=true creates fresh attempts while retaining that EOD preference.
force=true implies rerun and requires provider fetch even when the requested day
exists. Indicators compute on new executions from their frozen approved EOD input.
Successful/failed attempts are not reused under the existing IN_FLIGHT_ONLY policy.
Queued legacy commands without dataPolicy retain their former force semantics;
see the policy handoff for matched-worker deployment and compatibility.

Existing EOD/Indicators writers merge and deduplicate at their existing keys,
preserving data outside the requested range. This is not an unconditional
destructive reconstruction of all history. File existence alone
does not grant READY: workers validate exact scoped rows, register output, ACK the
registration publication, and then report SUCCESS. Platform promotes only that
work after both committed registration and SUCCESS, using frozen attempts and
row-scoped versions. Failed/missing-day output cannot release Indicators.
Historical indicator warmup still depends on available provider/lookback history;
the API does not guarantee complete pre-range history or trading-calendar coverage.

Logical datasets and storage paths are unchanged. Workers write Parquet only;
`SYNC_METADATA` remains the sole canonical `_metadata/metadata.json` writer.
The enrolled chain uses PostgreSQL work readiness without waiting for metadata
sync. This does not refresh the legacy discovery document automatically.

## Contract impact and operations

- Public HTTP: additive Indicators-only endpoint and the body above; no Console UI.
- Kafka/protobuf: no field/topic/schema change. Existing envelope metadata carries
  `backfillPolicy.force`, `rerun`, `dataPolicy` and `verifyEodParquet`; these are request policy, not an
  analytical work identity parameter. Deploy matched Ingestor/shared Python code.
- Persistence: forward V25 permits `BACKFILL` on `atomic_work_requests`; underlying
  atomic attempts retain existing one-work runs. No existing migration is edited.
- Configuration: `APP_SCHEDULER_BACKFILL_ENABLED=true` scopes enrollment and READY
  promotion to backfill membership even with global graph/enrollment/promotion off.
  Admission also requires guarded planning and the registration consumer
  (`APP_OUTPUT_REGISTRATION_SHADOW_ENABLED=true`). Keep normal global flags unchanged.
- Analyzer/shared reader: with `OUTPUT_READINESS_MODE=manifest`, configure the
  existing output-readiness API URL/token and matching Platform reader token.
  Enrolled jobs use the private frozen-work reader; normal jobs still read the
  manifest. Missing work-reader credentials fail closed, without manifest fallback.
  PostgreSQL reader mode also supports enrolled input; shadow-only mode is not
  the scoped backfill configuration.
- Pause EOD/Indicators schedules and drain legacy writers before admission; keep
  them paused throughout the backfill window. Admission locks/checks definitions
  and unfinished legacy children, but is not a permanent cross-mode writer fence.
  FAILED status from operator cleanup/timeout and zero Kafka lag do not prove a
  worker stopped writing: establish actual worker drain/termination separately.
  Do not re-enable schedules or run other legacy Parquet writers during the window.
- Apply V25 through the normal reviewed migration/startup process after deploying
  compatible workers. Drain accepted requests before disabling the backfill flag:
  with global graph mode off, that flag also enables request reconciliation.
  Keep registration/read services running throughout the drain. No automatic cancellation, cleanup,
  flag activation or live migration is part of API creation.

## Local operator package - 2026-11-06

The owner requested local configuration and a Postman-importable BANKS example
for 2026-11-02 through 2026-11-06. The ignored root `.env` now opts this local
workspace into backfill admission, guarded planning and registration consumption.
The matching reader token is stored only in ignored local env files. Host-local
Analyzer uses `apps/analyzer/.env.local`, manifest mode, and the private readiness
URL on localhost:8080. These changes need process restart; they do not change
shared defaults, start services, apply V25, alter definition schedules or submit
a request. Docker needs an appropriate container-reachable reader URL.

The local package is under `.agent/operations/backfill-banks-2026-W45/`:
`banks-week.postman_collection.json`, `banks-week.curl.txt`,
`banks-week.payload.json` and `README.md`. Import the collection, inspect the
definition, create the request, then poll until `request.state=SUCCESS`.
The collection stores returned request/execution IDs and keeps the operator
identity and idempotency key stable. If Friday data is unavailable, shorten the
range before submission. No HTTP requests or verification commands were run for
this configuration/package step; the November 5 source checks below remain dated
evidence, not a new live-runtime pass.

`SYSTEM_OPERATOR_UUID` is a configured UUID with a fixed repository development
default, not a generated value. PowerShell `$env:SYSTEM_OPERATOR_UUID` does not
automatically read `.env`. JPA auditing's fallback does not bypass the jobs API's
required `X-Omni-User` header; the collection supplies the fixed development UUID
explicitly. Use the actual trusted operator identity outside this local setup.
This package adds no API, schema, algorithm or increment scope; registry/Plan 030
status and deployment acceptance remain unchanged.

## Parquet date merge correction - 2026-11-06

Local BANKS execution history reported `Cannot compare Timestamp with
datetime.date` on Indicators. Regression tests reproduced the same exception at
`py_common.storage.atomic_work.write_day`: Parquet DATE history decodes to calendar
dates, while indicator calculations return pandas Timestamps. Concatenating these
before a raw-column sort fails before the output storage call. Earlier mocked
history contained only Timestamps and did not exercise this storage roundtrip.

The shared helper now sorts by a temporary `pd.to_datetime` key, preserving the
original history values/columns. The storage codec still owns canonical DATE
coercion. Requested-day replacement, outside-range history, frozen input versions,
missing-day rejection and legacy writes are unchanged. Normalizing the stored
frame directly was rejected during verification because caller tests require
preserving the pre-codec history representation.

| Impacted source/path                                  | Executed evidence                                                                                                                                                |
| ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| py-common `storage/atomic_work.py:write_day`          | New timestamp-result/calendar-history cases for date and evaluation_date, with real ParquetCodec roundtrip, plus retained invalid/missing-day/legacy-write tests |
| Analyzer Indicators                                   | Handler regression now covers timestamp and actual DATE Parquet history, enrolled and legacy reads; retains unrelated-day values and input versions              |
| Analyzer Symbol/Sector Features and Rotation Backtest | Existing shared-helper caller tests pass, including outside-day DataFrame preservation                                                                           |

Before the fix, each approved coverage target failed two new calendar-history
cases with the reported TypeError. Final reruns:
`npx.cmd nx run py-common:coverage --skip-nx-cache` passed 403 tests with 5 skips;
`npx.cmd nx run analyzer:coverage --skip-nx-cache` passed 268 tests.
Shared storage/atomic_work.py and Analyzer indicators/handler.py both have 100%
line/branch coverage in these runs; sector_wave/handler.py has 96.77% line and
89.29% branch coverage. Before/after JUnit and final coverage reports are retained
under ignored `.agent/check-results/P14-I10-date-merge-20261106/`.

Graph impact output remains bounded/truncated and is reconciled against the
callers above. Platform, Ingestor, Query Service and Console production sources
do not call this helper and are unchanged; persisted DATE semantics and Kafka
contracts are unchanged. No migration, configuration, path, dataset ownership or
READY authority change is introduced. Existing architecture, ADR, data-lake,
deployment and service guides already describe these contracts and need no new
behavioral guidance. Plan 030/registry retain I10 in_progress; no scope, acceptance
or agent-policy change is needed. This section and release notes record the
bounded correction. Static documentation review found no conflicting status or
contract claims in this follow-up; it does not establish runtime acceptance.

No live retry, restart, state cleanup or new Kafka E2E was performed for this
fix. Restart the Analyzer process to load it. A failed request/key is terminal;
drain its remaining members before retrying with a new idempotency key. A FAILED
request root alone does not establish that all of its members have finished.

## Request settlement and worker receipt - 2026-11-06

Owner decision: continue healthy branches after a target failure, and finalize
the request only after every member has reached SUCCESS/FAILED/ERROR/BLOCKED.
Previously the first target failure made the request FAILED immediately.
`atomic_work_admissible()` requires a PREPARED owner, so this revoked admission
from unpublished healthy siblings and left misleading RUNNING rows behind.
The local snapshot at 20:52 UTC+7 showed 160 PENDING outbox messages rejected by
that owner check while Kafka lag was zero and consumers were active. This was
request admission starvation; no consumer concurrency increase is part of the fix.

New requests remain PREPARED while any member is unfinished, including non-target
ancestors. Dependency failures still block their descendants, while independent
eligible members retain dispatch rights. Once every member is terminal, any failed
or blocked member produces FAILED; otherwise the request becomes SUCCESS. Existing
terminal request identity/state remains immutable; old FAILED requests are not
reopened and their unpublished orphan work does not resume automatically.

`GET /api/v1/jobs/atomic-work/requests/{id}` retains request/members and adds:

- `memberCounts`: counts by persisted execution status (nonzero statuses only);
- `unfinishedMembers`: count outside the four terminal states;
- `hasFailure`: whether any member is FAILED/ERROR/BLOCKED;
- `isSettled`: every member has a terminal execution status.

Membership can settle just before periodic root finalization; continue polling
while request.state is PREPARED even when isSettled has become true.

Members also expose `outbox_status`, `started_at` and `finished_at`. These fields
help distinguish waiting dispatch from an observed worker start. Historical
RUNNING-at-preparation rows are retained; neither that legacy status nor a FAILED
request root proves a worker is currently executing or that the request is settled.

New atomic children start PENDING with no started_at. Shared Analyzer consumers
and the Ingestor router publish and await a RUNNING receipt before invoking the
handler: startedAt is present, finishedAt is null, and metaJson.workerStarted is
true. Existing work/attempt attribution is carried unchanged. Invalid/unidentifiable
commands do not receive fabricated start signals. A start-publication failure
prevents handler execution and command offset advancement. RUNNING alone cannot
acknowledge the command: terminal status publication still precedes offset commit,
and registration plus SUCCESS still precede READY. Late RUNNING cannot reopen a
terminal execution.

The canonical execution enum already includes RUNNING, and Java accepts it.
The active legacy status topic uses JSON; this change adds Python emission and
permits null finishedAt only for RUNNING, without editing protobuf/generated
output. Old terminal-only workers remain readable but skip the intermediate
receipt. Restart matched Platform, Ingestor and Analyzer code for the full behavior;
keep cron paused and retain the existing reader/registration setup. No migration
or capacity/concurrency setting change is needed. The local Postman package now
has an `attempt` variable (v2) for a fresh request after loading patched services;
it polls settlement rather than assuming an early failure has drained all work.

| Impact/source                           | Acceptance and attributable tests                                                                                                                                                                                                         |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Platform JobRunWorkContext              | PENDING/no start timestamp at preparation, asserted in JobRunWorkContextTest and GraphWorkRequestsPostgresTest                                                                                                                            |
| Platform GraphWorkRequestService        | Real PostgreSQL tests keep a healthy sibling admissible after target failure, wait for unfinished target/non-target members, and finalize FAILED only after settlement; existing graph transaction/shared-attempt tests remain applicable |
| Platform JobService (existing consumer) | Real PostgreSQL receipt test moves PENDING to RUNNING and proves a late receipt cannot reopen FAILED                                                                                                                                      |
| py-common messages/lifecycle            | Receipt identity, optional parent, malformed payloads, work attribution, null running completion and required terminal completion tests                                                                                                   |
| py-common Kafka service/processing      | Start ACK precedes handler, failure prevents work/commit, and returning RUNNING cannot commit a completed command                                                                                                                         |
| Ingestor router                         | Start publication success/failure ordering before provider handler                                                                                                                                                                        |
| Cross-service                           | I10WorkReadinessIntegrationTest verifies workerStarted and non-null start timestamps on completed backfill members through actual Kafka/worker/Platform processing                                                                        |

Python coverage checks passed: py-common 419 passed/5 skipped, Analyzer 268
passed, Ingestor 98 passed. Platform coverage passed 749 tests (744 passed,
5 skipped, no failures/errors). `platform:test-integration --skip-nx-cache`
passed both real-worker tests, with no skips/failures/errors; the enrolled backfill
case confirms workerStarted receipts and start timestamps on completed members.
No live restart, retry, root reset or cleanup
was performed by this follow-up. Previously closed requests need a fresh request
key and separate review of remaining published work; this change does not add a
durable worker recovery protocol or automatically kill executions.

Cross-service reconciliation: Platform owns membership and settlement; shared
Python owns receipt/commit mechanics; Ingestor and Analyzer emit receipts. Query
Service and Console have no production source changes: persisted READY schemas
and existing read routes remain, with additive diagnostics fields. Database
constraints, Kafka topic names, storage paths, dataset ownership and configuration
defaults are unchanged. Architecture/ADR/data-lake and repository ownership/agent
guidance need no change because their owners and READY invariants are preserved.
Flow, Kafka/database docs, affected service guides, deployment notes, Plan 030 and
release notes record the new semantics; registry/roadmap status stays I10
in_progress with its existing verification/cutover gates. Graph output is bounded
and truncated, so this explicit reconciliation and the executed matrix own the
impact evidence. Static consistency review keeps source/local checks separate
from retained deployment and full-increment acceptance.

Attributable coverage (line/branch): GraphWorkRequestService 98.79%/89.51%,
JobRunWorkContext 87.18%/94.44%, Python messaging/lifecycle.py and
messaging/messages.py 100%/100%, kafka/processing.py 100%/100%,
kafka/job_status_service.py 100%/92.86%, Ingestor messaging/consumer.py
96.43%/100%. Reports are preserved under ignored
`.agent/check-results/P14-I10-lifecycle-20261106/`. Numeric component coverage is
paired with the source/test matrix above; it is not whole-I10 acceptance.

## Current Symbols snapshots on weekends - 2026-11-07

A historical Indicators request accepted after midnight Saturday correctly
projects its Symbols ancestors to the current calendar day. Platform WorkExpansion
already permits current SYNC_SYMBOLS and SYNC_METADATA snapshots on weekends,
but the shared Python AtomicWorkEnvelope only exempted SYNC_METADATA. Ingestor
therefore rejected the Symbols command before provider work with
`Unsupported atomic job type or weekend business date`.

Allow both current snapshot types in the Python weekend exception. EOD,
Indicators and other dated market work remain weekday-only; exact-date coverage,
domain-target matching, canonical work hashes and frozen input lineage are
unchanged. Do not shift the Symbols snapshot to an earlier trading day merely
to bypass validation: its date identifies the current catalog snapshot, not the
historical EOD date range. No protobuf, Kafka field, storage path, configuration
default, database or Java production change is introduced.

The integration fixture also needs two explicit clocks: its historical EOD
provider retains BUSINESS_CLOCK, while snapshot validation switches to the real
clock before the HTTP backfill request. Otherwise a Saturday HTTP acceptance
fails the current-day guard against the fixture's frozen Friday. The switch is
fixture-only and does not bypass production current-snapshot validation.

The real SyncSymbolsJobMessage regression failed on Saturday and Sunday before
the fix, then passed with identity/target mismatch rejection intact. Common
metadata-weekend tests now cover both days and still reject weekend Indicators;
the Ingestor test also rejects weekend EOD. The force-stock test fixture now
chooses a prior weekday so that its date-coverage test does not itself become an
invalid market command when run on a weekend.

Approved coverage reruns: Ingestor 100 passed, Analyzer 268 passed, py-common
420 passed/5 skipped. Attributable coverage is 94.32% line/90.38% branch for
shared messaging/atomic_work.py and 97.30%/83.33% for Ingestor messaging/messages.py.
Final forced paired Kafka integration execution passed 2/2 tests (no skips),
including the HTTP backfill chain and its scoped READY/query checks. The first integration
invocation was Gradle UP-TO-DATE, which is not fresh evidence for Python/date
changes. The forced execution passed one test and failed the I10 backfill step
because of the fixture clock mismatch above; the corrected fixture passed
the same approved integration target with --rerun-tasks (7 tasks executed).
Reports are retained under ignored
`.agent/check-results/P14-I10-weekend-snapshot-20261107/`.

Scope reconciliation: shared Python validates the envelope; Ingestor consumes
Symbols, and Analyzer consumes the remaining guarded types. Platform's existing
current-snapshot projection and weekend expansion tests supply the Java-side
contract; no Java behavior is altered. Query, Console, storage writers/readers,
metadata discovery and READY authority are unchanged. Plan 030 and Kafka/flow/
Ingestor guidance record the snapshot exception; architecture/ADR/database,
registry/roadmap and agent policies require no change because ownership, schema,
default activation and I10 in_progress status are unchanged. Graph results are
bounded/truncated and reconciled with these explicit boundaries. Static document
review preserves earlier dated evidence and does not imply retained deployment.

Restart Ingestor to load the corrected validator. Already failed executions do
not replay automatically; retry the same historical range with a new request key
after reviewing the prior request. No live restart, replay or state mutation was
performed during this fix.

## Cross-service blast radius

Graph was refreshed and impact analysis run; bounded output is truncated and has
unresolved calls, so it is navigation evidence only. Canonical ownership/flow,
Kafka, data-lake, database and rollout documents reconcile the following scope:

| Area                      | Impact or no-impact reason                                                                                                                                               |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Platform                  | New controller; BACKFILL graph acceptance, scoped enrollment/promotion, active-writer refusal and reconciliation. Scheduler leases/next-run are unchanged by acceptance. |
| Ingestor                  | Force policy checks exact existing EOD day; normal validation, upsert and registration remain required. Symbols uses existing current snapshot work.                     |
| Analyzer                  | Existing Indicators frozen EOD reads and registration reused; manifest-mode reader now resolves enrolled proofs through private API.                                     |
| Shared Python/contracts   | Hybrid reader change in py-common; existing message metadata only, no generated protobuf edits.                                                                          |
| Query Service             | No production source change: existing work-scoped reader consumes promoted results; real Query path covered in E2E.                                                      |
| Console                   | No source/UI change; existing read-only work inspection can display requests.                                                                                            |
| Persistence/configuration | Additive V25 mode and default-off backfill flag; no path or dataset ownership change.                                                                                    |
| Tests/operations          | Controller/auth, graph transaction/idempotency/admission and worker data-policy paths have local evidence in reference/013; rollout and full I10 gate remain open.       |

## Verification and remaining gates

The later [rerun/force policy](013-backfill-attempt-and-data-policy.md) and
[closed-owner reconciliation](012-i10-closed-owner-reconciliation.md) are not
covered by the historical executed results below; their later focused results
are recorded in those handoffs. Do not interpret earlier passes as full current-source acceptance.

Source/test matrix (executed outcomes recorded below after final runs):

| Source/safety path                                                                                                      | Test evidence                                                                                                          |
| ----------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| IndicatorBackfillController request and error mapping                                                                   | IndicatorBackfillControllerTest; I10WorkReadinessIntegrationTest HTTP 401/202/replay                                   |
| GraphWorkRequestService acceptance, declaration selection, idempotency, no schedule mutation, unsafe admission rollback | GraphWorkRequestsPostgresTest backfill cases; original graph transaction/concurrency tests                             |
| OutputDeclarations + OutputReadinessStore scoped READY, downstream barrier                                              | I10WorkReadinessIntegrationTest with global graph/enrollment/promotion disabled for backfill                           |
| WorkExpansion sector/date projection and immutable identity                                                             | Existing expansion tests plus graph backfill dated-chain assertions; current Symbols snapshots also expand on weekends |
| StockPricesHandler force checks, merge preservation                                                                     | test_atomic_stock_work.py covered/missing date cases; real worker E2E                                                  |
| metadata_reader + read_input_document hybrid routing                                                                    | test_output_readiness.py; manifest-mode Analyzer real-worker E2E                                                       |
| V25 fresh/retained upgrade                                                                                              | SchedulerOutboxIdentityMigrationPostgresTest migrates through V25                                                      |

Local owner-approved checks (2026-11-05; all invoked with `--skip-nx-cache`):

| Nx target                 | Latest outcome                                                                                                                                               |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| platform:coverage         | PASS: 746 tests, 741 passed, 5 skipped, 0 failures/errors; graph PostgreSQL cases and migrations through V25 included                                        |
| analyzer:coverage         | PASS: 266 passed                                                                                                                                             |
| ingestor:coverage         | PASS: 96 passed                                                                                                                                              |
| py-common:coverage        | PASS: 399 passed, 5 skipped                                                                                                                                  |
| platform:test-integration | PASS: 2 passed, 0 skipped/failures/errors on final source; HTTP authentication/replay and scoped EOD/Indicators/Query with Platform manifest reader included |

Attributable Platform class coverage (line / branch): IndicatorBackfillController
100% / 100%; GraphWorkRequestService 98.76% / 87.50%; OutputDeclarations 100% /
100%; OutputReadinessStore 100% / 91.67%; WorkExpansion 97.40% / 91.08%.
Python file coverage: stock_prices.py 98.82% / 95.45%; output_readiness.py
91.19% / 84.38%. These named components pass the 80%/80% default; aggregate
coverage is not used to claim full I10 coverage. The source/test matrix above
identifies the safety paths and E2E boundary separately.

Reports are retained locally under ignored
`.agent/check-results/P14-I10-backfill-20261105/artifacts`: Platform JUnit/JaCoCo
snapshots plus the three Python JUnit/coverage reports. An initial HTTP E2E
fixture failed because it supplied a non-UUID operator to existing JPA auditing;
the fixture now uses an operator UUID and HTTP acceptance/replay passed.
No production auditor behavior was changed to accommodate the test.

No lint/format/build target, hosted CI, retained deployment, real provider backfill
or full I10 cutover is claimed. Full I10 acceptance and deferred
recovery/tracing/P12 work remain owned by Plan 030 and the registry.

## Documentation dispositions

Plan 030, registry/roadmap, release notes, indexes, Platform/Analyzer/Ingestor
guides, execution flow, data-lake/database, deployment and TD-017 link this bounded
exception. Architecture and ADR-008 record scoped admission and retain the same
service ownership and coordinated-cutover rules; this operator path does not
authorize global readiness cutover. Kafka documentation records the metadata
policy while schema ownership is unchanged. Query/Console guides need no behavior
change because their production APIs/UI are unchanged. Development ownership,
AGENTS, CLAUDE and editor rules already cover the same owners, graph analysis and
verification approvals; no instruction change is needed. Plan 028 remains a
supporting unscheduled general backfill plan, not completed by this endpoint.

## Static documentation audit

The verify-document-consistency workflow was applied to this bounded follow-up.
Registry status/dependencies remain unchanged and P14-I10 has one active row;
reference/009 is navigation, not a new increment. Plan 030 retains its required
sections. Directly added relative links and migration/source paths were inspected.
API, force policy, storage ownership, scoped authority and default-off rollout
claims agree across the affected documents. Historical dated evidence remains
historical. No actionable inconsistency was found in the changed scope; this is
static analysis, not an executable link checker or whole-repository audit.
Executed test/coverage evidence is recorded separately above; no attestation,
deployment or increment completion follows from documentation consistency.
