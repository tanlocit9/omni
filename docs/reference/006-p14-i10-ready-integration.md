# P14-I10 enrollment, READY integration and exact-date admission

## Contract review — 2026-11-03 (UTC+7)

Continuation of owner commit `7a6699e8f08de5ae72d5fb52d8b89bd84aa1ad25`
on `codex/p14-i10-snapshots`. I10 remains **in_progress**; the full-lineage
owner decision and complete acceptance gate remain open. The November 2 results
below are historical Windows evidence. This section records fresh Linux checks
under `P14-I10-cloud-20261103`, without replacing the required-check manifest.

### Corrected private API contract

The private GET response is `{revision, items, hasMore, nextOffset}`. Pages are
bounded by both 200 items and 4 MiB serialized UTF-8 bytes. Large valid metadata
can produce a shorter page even when more rows exist. Platform reserves space for
the envelope and stops before the byte budget; an individual oversized item fails
with HTTP 413. Python advances by the returned item count, validates `nextOffset`
against that count, rejects non-progress/skipping cursors, and sends the same
revision on continuation. An absent cursor remains compatible with earlier v1
responses by deriving it from the actual count. Deploy the provider and all
Python cursor-aware readers together before postgres/shadow use: earlier readers
that step by a fixed 200 can skip byte-shortened pages. Revision changes and malformed
pages fail closed, without a JSON-manifest fallback.

PostgreSQL readers now derive the expected snapshot address from the logical
identity and exact dataVersion using the configured StockDataPaths. A mutable
canonical path, wrong version address or traversal path cannot become a query
binding merely because the API returned it. Canonical identity remains separate
for byte/schema/lineage hashing; this adds no physical paths to Kafka messages.
Both postgres and shadow reader factories propagate the shared path configuration.
Platform also rejects snapshot templates that omit dataset, partition hash or
dataVersion placeholders, matching the Python writer/reader requirement.
Public Query Service/Console DTOs remain unchanged.

Strict snapshot lineage also requires both persisted trend_momentum_v1 and
ichimoku_v1 references for every CONFIRMED_TREND_EQUALS row. Previously an absent,
empty or partial registeredComponents value could produce an EOD-only snapshot
claim. Immutable publication, snapshot reads and snapshot-backed approved reads
now reject it. Historical manifest discovery retains its compatibility behavior;
that compatibility is not proof for a baseline/snapshot. Backfill runs through the
strict snapshot path and cannot grant READY to incomplete historical provenance.
Unknown execution provenance may remain NULL; required input lineage is not waived.

The shared fixture
[output-readiness-page-v1.json](../../libs/contracts/fixtures/output-readiness-page-v1.json)
freezes Java-provider/Python-reader dates, schema, version addresses and NULL
historical provenance. Its symbolic byte/version values are wire evidence, not a
claim that real Parquet was read. Separate lineage tests perform a real Parquet
encode/decode and validate persisted component versions. Contracts generation now
also checks Java and Python output-registration artifacts (11 artifacts total).
No protobuf field, registration limit, migration or runtime default changed.

### Source / test matrix for this continuation

| Production boundary | Executed regression evidence |
| --- | --- |
| Platform OutputReadinessProvider.page | OutputReadinessProviderTest: shared fixture, revision/bounds, empty terminal cursor, large byte-bounded page, unsafe/missing identity path configuration, derived routes and known producer provenance. |
| Shared PostgresMetadataReader and metadata_reader | test_output_readiness: short-page continuation, cursor failure/non-progress, 4 MiB rejection, mutable/wrong path rejection, configured layout, four authority modes; existing auth/revision/failure/shadow cases. |
| Platform OutputDeclarations | OutputDeclarationsTest: disabled/unsupported protocols, all four supported logical declarations, malformed identity/typed parameters, server IDs and absence of physical paths. |
| Worker OutputPublication | test_output_publication: complete dates, wrong partition/symbol, server IDs, ACK failure, missing publisher, context cleanup, outcome origin dates. |
| Shared lineage and immutable store | test_output_lineage: real Parquet component lineage, deduplication, malformed/wrong-partition/version evidence rejection and strict two-component completeness with legacy compatibility; test_output_snapshots: namespace/version/schema/empty/unsafe input rejection without writes, existing duplicate/concurrent/read-back/ACK cases, real backfill scan/mutation, semantic schema/lineage mismatch and empty-output rejection. |
| Generated Java/Python contracts | contracts:test generates from canonical proto, builds schemas and checks both output-registration artifacts; generated sources remain ignored. |

### Fresh checks and remaining gates

Executed on Linux from owner commit plus this continuation:

| Target | Fresh result |
| --- | --- |
| py-common:coverage | PASS: 338 passed, 5 opt-in real-Kafka tests skipped. |
| analyzer:coverage | PASS: 242 passed after locked dependency sync. |
| ingestor:coverage | PASS: 94 passed. |
| query-service:test | PASS: 40 passed. |
| contracts:test | PASS: canonical generation/schema build, 11 artifact checks. |
| platform:coverage | PASS: 591 test entries, 4 skipped, zero failures/errors; JaCoCo generated. |
| platform:test (focused) | PASS: 11 provider/declaration tests, no skips. |
| python-impact-coverage | PASS: all eight named shared components >=80% line/branch. |
| impact-coverage (Java) | FAIL: remaining I10 components below required thresholds; never treated as acceptance PASS. |

Attributable Python coverage (line / branch): OutputPublication 100% / 100%;
output_readiness 98.73% / 96.43%; output_lineage 98.15% / 97.06%; output_snapshots
98.59% / 95%; output_registration 95.62% / 86.54%; output_reconciliation
98.04% / 94.44%; approved_inputs 100% / 92.86%; parquet 98.70% / 96.43%.
The directly impacted read_bound_dataframe, snapshot_bound_output, _snapshot_bytes
and read_output_snapshot methods each have 100% executed line/branch coverage
(16/16 and 8/8; 8/8 and 2/2; 9/9 and 4/4; 9/9 and 4/4 respectively).
Reports are local under `coverage/libs/py-common/coverage.xml`. Backfill race cases
control in-memory storage reads; HTTP boundaries are mocked. Real Parquet codec
round trips are exercised, but live object storage/Kafka is not established.
These values do not establish all-worker, Platform or full-increment coverage.
Earlier environment/bootstrap, Java compilation and fixture/type failures were
diagnosed rather than hidden as passes. The Java shared fixture now compares the
serialized HTTP JSON, avoiding an in-memory Integer/Long node equality artifact.

Fresh Java attributable coverage (line / branch):

| Component | Line | Branch |
| --- | ---: | ---: |
| OutputReadinessProvider | 98.61% | 83.93% |
| OutputDeclarations | 100% | 100% |
| OutputReadinessStore | 100% | 80% |
| OutputWriteAdmission / OutputReconciliationPolicy | 100% | 100% |
| OutputBackfillService | 97.14% | 56.52% |
| OutputCompletionGate | 100% | 61.36% |
| OutputRegistrationStore | 95.16% | 58.82% |
| ExecutionTimeoutWatchdog | 94.74% | 53.85% |
| OutputRegistrationEvent | 100% | 64.13% |
| OutputBackfillController | 25% | 0% |
| OutputCompletionDiagnostics | 7.14% | 0% |
| OutputRegistrationConsumer / OutputRegistrationKafkaConfig | 0% | N/A (no branch counter) |
| OutputReadinessController | 50% | N/A (no branch counter) |

The last three straight-line components fail their line gate regardless of the
threshold helper's missing-branch counter handling. Report:
`apps/core/build/reports/jacoco/test/jacocoTestReport.xml`.
Compact conclusion: `FAIL P14-I10-cloud-20261103 check=other/impact-coverage
source=exit_code code=1`. Full-lineage decision, worker-to-query integration and
rollout remain unrecorded/unmet required checks as well. No owner attestation.
The 02:00 review should prioritize these coverage gaps and the full integration
matrix, preserving the owner's transport/migration decisions for the final report.
Test shutdown also logs Modulith/Hikari cleanup warnings after disposable database
teardown; the final Gradle target exits zero with no test failures, not proof of
production lifecycle behavior.

Dependency bootstrap used declared Nx sync-ci targets with the lockfile and
`--inexact` to retain other services in the shared environment. Missing pinned
native grpc-tools assets required `npm rebuild grpc-tools` because no Nx dependency
bootstrap target exists. No schema/compiler replacement or generated-source edit.
`NX_GRADLE_DISABLE=true` disables automatic project-graph Gradle inference only;
the explicit Platform coverage target still executes Java/Gradle tests. The existing
check_python_coverage.py/check_jacoco_coverage.py helpers supply increment-specific
threshold analysis; no equivalent Nx target exists. Java
networking uses the session proxy. No lint, format, deployment or retained upgrade
is implied by these unit/contract checks.

Graph CLI 2.3.9 was installed in an isolated workspace tool environment. The fresh
minimal build indexed 644 files, 4944 nodes and 69796 edges (flows skipped).
Depth-2 impact for provider/reader/publication found 104 impacted nodes and eight
additional files; storage/lineage analysis found 83 nodes and eight additional
files. Result lists are bounded and unresolved call sites remain; these are
navigation evidence. Installation happened after the initial pagination edit and
cannot retroactively satisfy its missed pre-edit graph step. Subsequent snapshot
identity and lineage work used the fresh graph before edits. The cross-service
matrix below remains authoritative beyond graph hops.

### Documentation audit and ownership reconciliation

| Area reviewed | Update or concrete no-update reason |
| --- | --- |
| Plan 030, increment registry, roadmap/index, release notes | Record current source/evidence, preserve in_progress and owner-decision=true, label pending-era statements historical; correct stale plan and milestone summary. |
| Data lake, contracts README, Platform and Query Service guides | Document byte/item pagination, cursor compatibility and configured immutable address validation; link paired fixture/evidence. |
| Architecture and ADR-008 | No ownership/authority amendment: existing approved snapshot path and coordinated cutover decision apply; bounds enforcement implements that decision. |
| Execution/indicator/stock flows; Kafka contract | Stock/indicator guides now describe opt-in worker publication and origin-date behavior; Kafka planning-era wording is explicitly historical. No business wire/order change: logical refs only, ACK precedes SUCCESS, READY commits last. Execution guide already links here. New fixture is private HTTP. |
| Database | Label old no-tables planning paragraph historical; no schema/migration delta. V19–V22 and retained V20 identity gate remain unchanged. |
| Deployment and developer ownership | Add matched provider/cursor-reader deployment requirement and incomplete-history exclusion. No new flag, secret, routing owner or service; existing drain/shadow/cutover guards apply. Bootstrap exception is local only. |
| Analyzer/Ingestor/Console guides | Existing handoff links remain valid; shared-library tests strengthen their current protocol. No application ownership or public DTO change. |
| AGENTS, CLAUDE, Roo and governance | No policy change: graph, source/test attribution, verification authorization and deferred recovery boundaries remain authoritative. |

Static audit: Plan 030 retains all ten mandatory sections; P14-I10 has one active
registry row, remains in_progress after completed I9, and no downstream increment
is promoted. Directly changed links resolve to existing files and the review
heading. Current and historical results remain distinguished. Graph refresh after
staging included new Java/Python tests. The post-doc report covered
27 changed files and 65 symbols; five heuristic gaps remain (three provider
symbols, two storage methods reached only through callers). Executed test mapping
and measured coverage are recorded separately above. Flows were skipped, so zero
detected flows is not zero cross-service impact. Final post-document change
detection repeated after final evidence edits: same 27 files / 65 symbols, risk
0.40 and five heuristic candidates. Static audit is CONSISTENT within the changed
document/claim scope; full acceptance is FAIL/INCOMPLETE as recorded above.
Static consistency does not supply missing acceptance evidence.

### Questions to collect after the 02:00 review

1. For histories exceeding 128 input-version links, 4096 dates or the 256 KiB
   registration envelope, choose a paged/finalized transport or explicitly keep
   oversized datasets outside the cutover. No truncation or implicit limit increase.
2. Confirmed-signal history without persisted component versions stays excluded
   from snapshot backfill. Decide whether to rebuild it from validated inputs or
   keep it excluded for cutover; do not invent historical versions.
3. Before a retained migration, reconcile the original V20 identity/checksum with
   the deployed Flyway history. Disposable database tests cannot answer that.

The scheduled review is 2026-11-03 02:00 Asia/Bangkok (UTC+7), with latest-head
review/fixes and a consolidated question report. Durable worker recovery remains
deferred. Full worker → Kafka → Platform → storage/API → Query integration,
attributable coverage for every impacted critical component, retained upgrade,
shadow parity and coordinated rollout/rollback remain acceptance gates.

### Follow-up review after the interrupted delivery

The first large GitHub tree upload did not return a completed response. On resume
after 03:00 UTC+7, local HEAD remained 7a6699e, the staged source tree was unchanged,
and remote HEAD still matched the owner baseline; no push had been confirmed.
The one-shot 02:00 task records last_run_time 2026-11-02T18:55:32Z but has no
recorded run/result body. That timestamp does not prove a successful review or
report delivery. No automatic-run findings or test results are inferred.

The foreground follow-up re-read cursor/identity validation, strict component
lineage and provider template bounds, reconciled the preserved check conclusion,
and found no further actionable defect in those corrected paths. Checks were not
repeated for this static review because production source remained unchanged.
The known coverage and full-integration gates above still prevent I10 completion;
the collected owner questions remain the three listed above. Delivery uses
individual blob uploads and a guarded branch update to preserve owner changes.

## Current state

The following source baseline and November 2 measurements are retained for
history; fresh November 3 results are above.

P14-I10 remains **in_progress**. This continuation supersedes the source-state
inventory in [handoff 005](005-p14-i10-completion-watchdog-handoff.md), not its
unresolved runtime/migration/coverage gates. Local checks are recorded under
`P14-I10-continuation-20261102`; they are not deployment or cutover evidence.
No production data was inspected or repaired, no environment flag was activated,
and no retained database migration was run. Testcontainers checks use disposable
PostgreSQL instances. Durable worker recovery, tracing and P12 stay deferred.
The registry records owner_decision_required=true while the full-lineage transport
decision below is pending; this does not authorize a larger contract implicitly.

## Owner decisions

- Missing EOD on the exact work date is a repairable failure, never inferred
  no-trade/valid-empty. Keep a failed attempt terminal. A later request creates a
  new attempt; missing input coverage defers its pending outbox eligibility by
  30 seconds so other eligible work can proceed. No automatic replay of the 921
  reported failures is authorized or implemented.
- Historical READY baselines are allowed without known producer execution.
  Preserve unknown provenance as NULL; never create a synthetic execution/attempt.
  Require validated immutable bytes/schema/actual dates/lineage, an unchanged
  generation fence, drained writers and no unresolved timeout ownership.

## Implemented source

- OutputDeclarations freezes required logical outputs and immutable event/item/
  attempt IDs into command metadata for EOD, indicators, signals and signal outcome
  evaluation. GraphWorkRequestService enrolls each new supported attempt in the
  same transaction as its pending outbox, before any dispatch can observe it.
  Reuse cannot attach newly enrolled work to a legacy unenrolled attempt.
- Shared OutputPublication captures successfully persisted snapshots in a task-local
  context. Worker Kafka adapters ACK registration before SUCCESS. Snapshot/validation/
  registration failures take the existing ERROR path. Replay reuses server IDs;
  changed payloads conflict visibly, without a new recovery journal.
- Outcome evaluation can explicitly report server-approved OUTPUT_UNCHANGED when
  it scanned existing rows, had no missing EOD and wrote nothing. Output dates remain
  origin dates T, not evaluation day D. This grants no new dataset coverage/pointer.
- V21 adds monotonic enrollment generations and READY pointers. JobService promotes
  only after enrolled SUCCESS plus registration commit together. Timed-out attempts
  cannot promote; lower generations cannot replace newer successful pointers.
  OutputWriteAdmission holds later generations for the same logical partition until
  earlier enrolled writers terminate. Unresolved timeout ownership continues to
  block later writers. Production enrollment is serialized by existing graph catalog
  locks, preventing a hidden lower generation from appearing after admission.
- V22 adds immutable baseline evidence, separate baseline pointers and a unified
  read view. Registered pointers always take precedence. A sequence plus shared
  execution-statement advisory locks and an exclusive backfill commit lock prevent
  scan/commit races without serializing normal executions on a counter row.
- The private paged GET `/api/v1/jobs/output-readiness` provides snapshot addresses,
  canonical hash identity, schema/statistics/lineage and exact coverage. Revision
  changes abort multi-page reads. Platform admission and Python readers have explicit
  PostgreSQL adapters; API/DB failures never fall back to JSON after cutover.
- Reader and reconciler bearer tokens have separate read-only GET and backfill-only
  POST scopes. Existing operator/proxy authentication remains supported. Tokens are
  environment secrets, blank/disabled by default; never expose them to Console.
- Backfill endpoints `/api/v1/jobs/output-readiness/backfill/fence` and `/backfill`
  accept bounded validated baselines. Existing registered versions cannot be
  overwritten by reconciliation. Any unresolved timeout currently blocks backfill;
  no automatic ownership-resolution endpoint or repair loop is added.
- Shared metadata readers support manifest, shadow and postgres modes. Shadow
  compares versions/schema/bytes/row counts/coverage/lineage and retains explicit
  manifest authority. Its process-local comparison is diagnostic, not a durable
  rollout attestation. PostgreSQL mode uses only the API.
- SYNC_METADATA can run fenced historical backfill before cutover, or validate
  existing READY snapshot versions after cutover. PostgreSQL mode does not write
  canonical metadata JSON. Backfill and postgres reader modes cannot be combined.
- Metadata synchronization records actual date sets. Admission binds coverage to
  the same approved version; min/max timestamps do not prove missing intermediate
  dates. Signal inputs and component histories validate approved bytes before use;
  `require_day=True` remains enforced. Errors include INPUT_DATE_NOT_COVERED.
  Newly bound confirmed-signal rows persist their exact component registrations
  in the existing input_versions field; shared lineage extraction includes those
  versions after Parquet round trips. Historical rows without that evidence do not
  acquire invented component links.

## Cross-service blast radius

Graph MCP was unavailable during initial work. A CLI shim was discovered late;
its existing index belonged to another branch and was stale. Incremental refresh
completed but its first report hit a Windows cp1252 rendering error. A full build
with flows skipped then succeeded (614 files, 4656 nodes, 54719 edges). Explicit
library-based incremental indexing added the new untracked production files
(65 nodes/470 edges, no parse errors), which the CLI tracked-file scan had missed.
Read-only CLI change detection against HEAD reported 68 files, 107 changed symbols,
95 candidate test gaps and risk 0.60. These bounded candidates are navigation,
not executed test attribution; skipped flows do not establish zero flow impact.
This cannot retroactively supply missed pre-edit graph analysis. The following
manual reconciliation remains separate from graph evidence and executed coverage.

| Owner | Impact or concrete boundary |
| --- | --- |
| Platform | Graph enrollment/outbox metadata, completion validation, READY promotion/provider/API, backfill fencing, exact-date admission, narrow machine authentication and diagnostics. Existing request aggregation/retry remains authoritative. |
| Ingestor | EOD snapshot registration before SUCCESS. Catalog/intraday publication protocols remain separate and unchanged. |
| Analyzer | Indicator/signal/outcome publication, approved signal/component inputs, EOD readers in sector features/outcomes, latest-signal reader, SYNC_METADATA backfill/reconciliation owner. Sector outputs/intraday protocols are not moved into the three-dataset catalog. |
| Query Service | Explicit authority adapter and byte/schema/lineage validation of snapshot-backed inputs before DuckDB binding. Existing public catalog/query shape is retained. |
| Console | No source change: existing Query Service proxy/catalog paths consume the same public DTOs; no DB/Kafka access or browser credentials. |
| Shared libraries/contracts | Additive command metadata and private HTTP DTOs; existing registration wire remains unchanged. Shared publication/metadata/schema/lineage validation changes. Generated contracts are not hand-edited. |
| Persistence | Candidate V21/V22 only; V19/V20 identity/retained-history reconciliation is still required before release. Baseline provenance is explicitly NULL. |
| Configuration/operations | All opt-in flags remain off. Separate tokens and coordinated authority selection; writer drain, original migration identities, Kafka provisioning, shadow/cutover/rollback and exact-date repair remain operational gates. |

## Configuration and coordinated cutover

**Open contract decision:** the frozen registration/baseline output currently has
at most 128 input-version links and 4096 dates, with a 256 KiB envelope. Shared
signal history and long-lived indicator history can exceed these limits. This
continuation fails closed instead of truncating lineage or dates. Full catalog
cutover remains blocked until the owner chooses a paged registration/finalization
contract or explicitly accepts that oversized datasets remain excluded. A paging
proposal is awaiting approval; no new transport version or fragmentation protocol
has been implemented or silently substituted.

This is a runbook, not authorization to execute deployment or modify retained data.

1. Reconcile retained Flyway history, especially the unknown earlier V20 checksum.
   Review candidate V21/V22 before applying. Do not use repair to hide a mismatch.
2. Provision the existing registration topic/consumer permissions. Configure private
   API routing and distinct secret reader/reconciler tokens. Preserve the trusted
   proxy boundary for operator headers.
3. Enable worker snapshots and the registration consumer, then opt-in enrollment
   for new graph attempts. Drain legacy unenrolled producers; do not retrofit them
   after dispatch or silently enroll shadow evidence. Keep readers on manifest.
4. Freeze affected mutators. Run explicit historical backfill with
   OUTPUT_READINESS_BACKFILL_ENABLED=true and manifest authority. Unknown execution
   provenance remains NULL. A generation change aborts commit; orphan snapshots are
   retained for manual resolution. Source bounds (4096 coverage dates, 256 KiB
   registration/baseline, 200-item API pages) fail closed; do not truncate coverage.
5. Disable backfill, enable READY promotion, compare all affected partitions in
   shadow. Prove exact work/date coverage, immutable version reads, writer exclusion,
   restart/ACK/late-attempt behavior and retained-data parity. Preserve the current
   single-process shared signal-history writer constraint; no concurrency activation.
6. After the complete I10 gate and separate rollout approval, switch Platform
   APP_OUTPUT_REGISTRATION_READER_ENABLED and all Python OUTPUT_READINESS_MODE values
   to postgres together. Existing Console uses Query Service; there is no direct UI
   authority switch. Do not enable readers while any required partition is absent.
   Query Service snapshot authority currently requires S3; local-file mode fails
   closed for snapshot-backed partitions instead of validating different MinIO bytes.
7. Opt in APP_OUTPUT_REGISTRATION_RECONCILIATION_SCHEDULE_ENABLED after reader
   cutover. OutputReconciliationPolicy uses `0 0 */12 * * *` for SYNC_METADATA
   through existing next-run calculation, with the configured scheduler timezone
   (Asia/Ho_Chi_Minh by default) and UTC timestamps. Persisted definition identities
   and seeds stay unchanged; the first run retains its existing next_run. Only
   current SYNC_METADATA snapshots gain weekend support; market jobs stay weekdays.
8. Enable the separate watchdog only after its failure/race gate. Keep 2-minute
   scans and the 30-minute published deadline. Timeout does not release writer ownership.
9. On failure, stop new claims and keep PostgreSQL READY history, registrations and
   snapshots. Returning to JSON requires a separately validated resynchronization;
   never automatically fall back to an old manifest or discard post-cutover versions.

## Verification and coverage matrix

| Behavior/source | Test source / remaining evidence |
| --- | --- |
| Server declaration, production enrollment/outbox identity | GraphWorkRequestsPostgresTest.productionEnrollmentFreezesIdsBeforeOutboxCanDispatch |
| Both stream orders, immutable status, valid-empty, late timeout | Existing completion/watchdog cases in GraphWorkRequestsPostgresTest |
| Baseline null provenance, exact date gaps, generation fence, registration precedence | GraphWorkRequestsPostgresTest.baselineHasNullProvenanceAndCannotReplaceRegisteredReady |
| Worker actual-date publication, server IDs, missing-day rejection, ACK failure | py-common test_output_publication |
| API pagination revision, auth failures, unavailable API, explicit shadow authority | py-common test_output_readiness |
| Exact bytes/schema/lineage reads | test_parquet_storage, test_output_snapshots and affected worker suites |
| Missing-date signal failure without writes | Analyzer test_atomic_signal_with_stale_ready_eod_reports_error_without_outputs |
| Schedule/weekend/security scopes | OutputReconciliationPolicyTest, OutputReadinessTokenTest, WorkExpansionTest and shared test_atomic_work |
| Full gates still required | Owner decision for bounded full-lineage registration; attributable >=80% line/branch for every new critical component; real Kafka/offset/rebalance, actual workers/storage/API round trip, backfill concurrency/retained upgrade and coordinated rollout/rollback |

### Executed local checks

Recorded through `tools/check_result.py` under
`P14-I10-continuation-20261102`, an initial local check set, not the complete I10 gate:

| Approved target | Result |
| --- | --- |
| platform:coverage | PASS: 580 tests, 4 skipped, no failures/errors; JaCoCo generated |
| py-common:coverage | PASS: 285 passed, 5 skipped |
| analyzer:coverage | PASS: 242 passed |
| ingestor:coverage | PASS: 94 passed |
| query-service:test | PASS: 40 passed |
| contracts:test | FAIL before tests: pinned grpc-tools protoc.exe requires MSVCP140D.dll, VCRUNTIME140D.dll, VCRUNTIME140_1D.dll and ucrtbased.dll; exits 0xc0000135. The existing generate target cleans ignored gen output before failing. No generated files were hand-edited. |

Native DLL dependencies were read from the pinned executable's PE import table.
No compiler replacement, runtime installation or system PATH persistence was made.
Earlier failed runs caught fixture/schema/mapper wiring issues and a counter-row
fence contention risk; the delivered fence uses sequence/shared advisory locks.
Windows cache/temp access failures were resolved by approved-target runs outside
the sandbox, not by weakening tests.

### Attributable coverage remains incomplete

Examples from executed reports (line / branch percent):

| Critical source | Line | Branch |
| --- | ---: | ---: |
| OutputReadinessStore | 100 | 80 |
| OutputWriteAdmission | 100 | 100 |
| OutputReconciliationPolicy | 100 | 100 |
| OutputBackfillService | 97.14 | 56.52 |
| OutputReadinessProvider | 45.16 | 33.33 |
| OutputCompletionGate | 100 | 61.36 |
| OutputRegistrationStore | 95.16 | 58.82 |
| ExecutionTimeoutWatchdog | 94.74 | 53.85 |
| OutputDeclarations | 66.67 | 53.85 |
| Python output_publication | 90.74 | 72.22 |
| Python output_reconciliation | 98.04 | 94.44 |
| Python output_readiness | 86.76 | 66.67 |
| Python output_lineage | 86 | 75 |

Controller/consumer/diagnostics coverage is also incomplete. These measurements
do not satisfy the >=80% per-critical-component gate. Full-suite success must not
be reported as I10 acceptance, owner_verified or completed. Formatting/linting
await separate approval; real Kafka/retained rollout and full worker-to-query
integration were not run. No broad suite pass establishes the missing gates.

## Documentation reconciliation

Review registry/Plan 030/roadmap/release notes, ADR-008, architecture/flows,
Kafka/data-lake/database, deployment/developer ownership, service guides and indexes
against this source state. Current banners point here; historical checkpoint claims
remain dated. Governance, CLAUDE and Roo delegation do not need a policy change:
verification/cutover safeguards remain. AGENTS must distinguish optional source from
activated runtime. Static documentation analysis cannot close the missing graph or
behavioral gates. I10 stays in_progress.
