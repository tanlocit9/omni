# I9 acceptance verification — 2026-10-31

Current local acceptance (2026-11-01): **P14-I8 and P14-I9 completed**.
See [source-bound checks, coverage and rollout rehearsal](i8-i9-closure-20261101.md).
I10 remains planned/pending; no default activation, retained-environment deployment,
PostgreSQL readiness or P12 writer is implemented by this closure. Earlier dated
handoffs below record their historical status and do not override this result.

Owner: [Plan 030](../../../../docs/plans/030-static-graph-dispatch-planner.md).
Registry: **P14-I8 and P14-I9 verification_pending; P14-I10 pending**.

## Worker coverage completion — 2026-11-01

Owner requested closing the remaining worker coverage gaps and committing the
related I9 changes. This section supersedes the October 31 whole-handler coverage
limits below; it does not retroactively rebind that historical gate to new inputs.

| Worker / source                                                   |   Line | Branch | New executed tests / safety behavior                                                                                                                                                                                                                     |
| ----------------------------------------------------------------- | -----: | -----: | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| IndicatorJobHandler (`apps/analyzer/app/indicators/handler.py`)   |   100% |   100% | Legacy path signature preserves indicator source and EOD lineage; unrelated path configuration TypeError fails before read/write                                                                                                                         |
| SignalJobHandler (`apps/analyzer/app/signals/handler.py`)         | 98.88% | 95.83% | Four missing/non-READY EOD/indicator cases prevent writes; actual Ichimoku missing-column calculation returns NO_DECISION; missing/empty/invalid-date legacy EOD avoids intraday resolution; unavailable/invalid exact-date intraday stays non-persisted |
| SectorWaveJobHandler (`apps/analyzer/app/sector_wave/handler.py`) |   100% |   100% | Six actual-calculation cases: SYMBOL/SECTOR/GLOBAL across atomic/undated routes; skip absent/empty members and catalog partitions; fallback to all three exchanges; preserve every non-target day including future output rows                           |

Eighteen new cases increase the full Analyzer suite from 174 to **192 PASS**, zero
failures/errors/skips; focused selection executes **100 PASS**. The named file
threshold checker enforces >=80% line and branch for all three handlers without
lowering thresholds, excluding code or mocking message validation to force an
unreachable state. The single uncovered signal handler line is its defensive
unsupported-strategy branch; validated public messages reject that strategy before
dispatch, as exercised by the existing message-contract rejection test.

Renewed shared-library coverage also attributes the reused atomic boundaries:
messaging atomic identity 94.32% line/90.38% branch; approved-input binding
100%/92.86%; storage cutoff/day-preserving writes 100%/100%; status service
100%/91.67%; acknowledged record processing 100%/100%. These are named component
measurements, not a claim that every unchanged shared module has 80% coverage.

`analyzer:lint` passes for all 68 files. Its first run exposed pre-existing style
issues in nine files: indicator handler, five Sector Transition modules and three
existing worker/Sector Transition tests. Scoped Nx formatting/import fixes, two
equivalent combined `with` statements, and a wrapped comment/docstring resolve
those issues. Normalized AST comparison against the baseline proves unchanged
executable structure, accounting explicitly for import order, one removed unused
`datetime.date` import, docstrings and equivalent nested `with` spelling. The
initial fixture failures assumed 60 backtest rows instead of 59 after the initial
return offset; corrected assertions passed without modifying calculations.

The follow-up gate is `P14-I9-worker-coverage-20261101`, with eighteen required checks:
scoped Python format, focused tests, full Analyzer coverage, per-handler thresholds,
Analyzer lint, docs format, baseline/source attribution and four real-worker
container workflows (atomic sharing, legacy, overlap and recovery), plus scoped Java
and shared-test format, renewed full Platform/shared coverage, all 22 Java
thresholds, shared Kafka integration and clean Platform build. Exact Nx
commands, XML, normalized AST proof and 567-file binding are retained under its
ignored check directory. Read its result with
`python tools/check_result.py conclusion --increment P14-I9-worker-coverage-20261101`.

Final follow-up conclusion: **PASS P14-I9-worker-coverage-20261101 required=18
pass=18 fail=0 unknown=0 missing=0 sources=exit_code**. Renewed execution: Platform
539 entries (535 executed, four opt-in skips), each of those four worker workflows
executed separately with zero skips; shared Python 200 PASS plus five separately
executed broker cases; clean Platform build PASS (`-x test` after full coverage).
Fresh JUnit timestamps are checked against each workflow's recorded start, and all
567 bound source files are rehashed before commit. The 22 renewed Java component
rates match the table below; all meet >=80% line and branch.

Source attribution: eighteen former inputs changed and one new test file was
added. Twelve changes are the Analyzer test/style scope described above; the other
six normalize GraphScheduledEntries, four Java test files and the Kafka offset
test. All other former inputs are byte-identical. All fourteen owned Java inputs
retain identical non-comment lexical tokens, and the shared Kafka test retains an
identical AST. Scoped Nx formatting covers all files intended for commit rather
than only newly added tests. The full Java suite and its 22-class threshold check
are renewed after this last formatting pass, as are shared Python coverage and
Kafka tests. Worker workflows are rerun against the final formatted source rather
than inferred from old PASS artifacts. Earlier passing runs on pre-format inputs
are not counted as the final verification.

Graph MCP tools are not exposed in this session. The normal Git pre-commit hook
revealed the installed local `code-review-graph` CLI, so the fallback is now used
explicitly: incremental update, `detect-changes --base 54cf5c8`, and the installed
package's impact query at depth two. The initial capped impact was expanded to
5,000 results: **170 source nodes, 3,321 reachable nodes, 618 additional files,
no output truncation**. Change detection reports 115 changed functions/classes and
50 heuristic test-link gaps at its pre-final-commit snapshot. These are graph
reachability/test-link observations, not executed coverage or concrete defects.
UTF-8 output avoids the existing hook's cp1252 brief-panel rendering error;
the explicit full-JSON detection succeeds. No hook or graph dependency was changed.

The large reachability set spans Platform, Analyzer, Ingestor, shared Python,
Query Service, Console, migrations and tooling. Normalized AST/Java-token proofs
bound the current formatting changes; the existing scheduled-coverage repair is
exercised by selector/claim/PostgreSQL cases. Full Java and Analyzer execution plus
the criterion/component matrices reconcile graph test-link warnings. Unchanged
notification/API/tooling modules retain their message fields and behavior; no
network notification or operational mutation is introduced. Graph artifacts are
saved beside the gate (`graph-impact.json`, `graph-changes.json`, graph-update log).
Direct caller and canonical-document review confirms: Analyzer gains tests/style;
Platform/Ingestor/shared Python adapters keep their contracts; Query Service is
exercised by worker workflows; Console, proto/configuration, storage ownership and
database schema are unchanged. Existing Platform/Console/shared/contract evidence
is reused only for unchanged inputs where not renewed above. No live data/offset changes, provider calls,
flag activation, migration, P12 writer or I10 readiness implementation.

Documentation disposition: update Plan 030, registry/roadmap, ReleaseNotes and this
diagnostics report/index. Architecture, ADR-008, flow, Kafka, data lake, database,
deployment, ownership/service guides, indexes, Plan 027, AGENTS/CLAUDE/rules retain
their runtime/contract instructions because only tests/style change. Existing links
point to this current evidence. Static consistency review and post-edit CLI change
detection accompany finalization. The still-invalid I8 gate and retained rollout
remain open; fresh graph analysis does not substitute for those gates.

I8 prerequisite read: `INVALID P14-I8 reason=cannot_read_required.json`; this task
does not invent a prerequisite PASS or owner attestation.

## October 31 scope and attribution

The October 31 run extends the earlier bounded repair/worker gates into the local I9
check manifest. Baseline HEAD is `54cf5c8`, including the pre-existing dirty worktree;
this is not a clean-commit verification. No commit, deployment, live migration,
consolidation, offset reset, external provider request or runtime flag change.

Source hashes, required manifest, JUnit/coverage copies and workflow results live
under ignored `.agent/check-results/P14-I9/`. `source-binding-initial.json` binds the
Python/Console/contract checks; `source-binding.json` binds the expanded final Java
suite. Their differences are restricted to Java tests. Untracked test inputs are
included. Git HEAD alone and historical PASS files are not evidence of these tests.
Read the result only with `python tools/check_result.py conclusion --increment P14-I9`.

October 31 compact conclusion: **PASS P14-I9 required=17 pass=17 fail=0 unknown=0
missing=0 sources=exit_code**. All 566 bound source/test/config files were rehashed
without drift. This is the local check result, not completion or owner attestation.

## Acceptance / source / executed-test matrix

| Checkpoint / criterion                                                | Production source / boundary                                                                                | Executed evidence                                                                                                                                                                                                                                                                                 |
| --------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| I9.a graph projection, effective strategy and preset parameters       | GraphWorkExpansion, WorkExpansion, StaticTopologyDeclaration; JobRunPreparationService planning/persistence | GraphWorkExpansionTest, WorkExpansionTest, JobRunPreparationPostgresTest, GraphWorkRequestsPostgresTest; historical repair cases rerun in the full suite                                                                                                                                          |
| Independent schedules retain uncovered scopes/dates/parameters        | GraphScheduledEntries, SchedulerClaimService, JobScheduler                                                  | GraphScheduledEntriesTest, SchedulerEntryClaimTest; narrowIndicatorGraphDoesNotRemoveTheBroadStockSchedule and bounded seeded claim batches                                                                                                                                                       |
| I9.b unique concurrent acquisition and exact dependencies             | GraphWorkRequestService; V17 slots/attempts/upstream/member guards                                          | concurrentDifferentTargetsAcquireCommonAncestorsOnlyOnce; overlapping scopes; overlappingDateRangesShareOnlyTheirIntersectionAndReplayKeepsCancelledMembership; sharedSectorJoinWaitsForEveryExactBranchAndPropagatesFailure (two-symbol sector join, success/failure, cancellation of one owner) |
| Failure, retry, cancellation and durable replay                       | GraphWorkRequestService retry/cancel/reconcile; V17 immutable membership                                    | exact-ancestor failure, terminal fresh attempt, retry ownership/new-key guards, stale-claim/changed-target rejection, rollback, restart, cancelled replay after catalog/preset changes                                                                                                            |
| Candidate, claim and expired lease safety                             | JobRunDispatchStore, SchedulerOutboxRepositoryImpl, SchedulerOutboxService                                  | barrierIsRecheckedAtClaimAndUpstreamFailurePropagatesWithoutChangingOldAttempts; expiredTokenCanPersistWaitingAndUncoverReadyRows; expiredTokenCanPersistBlockedAndPropagateItsFailure; existing outbox/dispatch regressions                                                                      |
| Actual shared worker attempts, READY-last, approval and output replay | real Platform → Kafka → Ingestor/Analyzer → statuses; MinIO metadata and Query Service                      | JobRunAtomicSharingIntegrationTest: four equivalent requesters, three shared initial attempts/commands, twelve memberships, one-step runs; one cancellation before dispatch and one after indicator PUBLISHED; two requesters complete; replay ACK and committed offset 2; one dated query row    |
| Legacy and unfinished-work compatibility                              | graph-disabled V1/I8 route; common workers and Kafka supervisor                                             | JobRunCrossServiceIntegrationTest, JobRunSharedAncestorIntegrationTest, JobRunBacklogRecoveryIntegrationTest, five real shared Kafka integration cases; these do not independently prove I9 membership                                                                                            |
| I9.c eligibility, conflicts, opt-in and rollback                      | DefinitionConsolidationService, DefinitionConsolidationStartup; V18                                         | DefinitionConsolidationTest (inactive/retired/unscheduled/cronless/null/global cases); startup flag policy; real lease/operator conflict, UUID lock contention, alias offset and seeder preservation                                                                                              |
| Historical data / schema compatibility                                | forward V17/V18; V1 frozen path, V15 strict child identity                                                  | v16UpgradePreservesFrozenV1PathsOffsetsHistoryAndAllDeliveryStates: exact before/after business rows including histories, paths/fingerprints, FK links, offsets, PENDING leased/PUBLISHED/BLOCKED outbox, preset identities; second migrate is no-op; Flyway validate                             |
| Authenticated bounded diagnostics and Console                         | AtomicWorkDiagnosticsService, GraphWorkOperationsController, packaged SQL; Console WorkOperations           | diagnostics pagination/shared identity, HTTP authentication/400/404/durable request-work-consolidation reads, unavailable DB handling; Console suite; historical poison inventory on genuine V14 JDBC context                                                                                     |
| Worker/read contract regression                                       | Analyzer, Ingestor, py-common, Query Service; canonical contracts                                           | Analyzer 174, Ingestor 73, shared 200 (five real-broker cases covered separately), Query Service 40, Console 34; same canonical proto generation/artifact checks                                                                                                                                  |

The final acceptance subset executed **69 tests with zero failures/errors/skips**.
This includes both success and failure of the actual two-symbol atomic sector join.
The final full Platform/worker checks and numeric component coverage are reconciled
below. The October 31 critical threshold check covers 22 Java orchestration
components; the then-open Python handler gaps are closed by the November 1 section
above. Passing local checks remain distinct from increment completion.

## Recorded local checks

The final Platform run has **539 entries: 535 executed, zero failures/errors and
four opt-in skips**. Each skipped workflow executes separately below with zero
skips. The focused 69-test subset overlaps the full suite and is not added to its
count. Full JaCoCo/JUnit were copied before subsequent scoped tests and clean build.

| Required check             | Command / scope                                                                                                                                                                                                                                                                                                                                                                           | Executed result                                                                                                 |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| format/java                | `nx run platform:format`, explicit nine Java test paths                                                                                                                                                                                                                                                                                                                                   | PASS                                                                                                            |
| test/acceptance            | `nx run platform:test --skip-nx-cache --args="--tests *DefinitionConsolidationTest --tests *DefinitionConsolidationStartupTest --tests *GraphWorkRequestPolicyTest --tests *GraphWorkRequestsPostgresTest --tests *SchedulerOutboxIdentityMigrationPostgresTest --tests *JobRunDiagnosticsPostgresTest --tests *JobRunReadModelPostgresTest --tests *JobSchedulerDependencyPostgresTest"` | 69 PASS, zero skips                                                                                             |
| other/coverage-platform    | `nx run platform:coverage --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                                | 535 executed, four opt-in skips, zero failures/errors                                                           |
| other/coverage-critical    | `python tools/check_jacoco_coverage.py --xml <saved-full-report> <22 classes below>`                                                                                                                                                                                                                                                                                                      | Every named Java component >=80% line/branch; no matching Nx threshold target exists                            |
| build/platform             | `nx run platform:build --skip-nx-cache --args="-x test"`                                                                                                                                                                                                                                                                                                                                  | Clean build PASS; duplicate test execution excluded after the full coverage run                                 |
| other/coverage-analyzer    | `nx run analyzer:coverage --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                                | 174 PASS, zero skips; whole-handler limits below                                                                |
| other/coverage-ingestor    | `nx run ingestor:coverage --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                                | 73 PASS, zero skips                                                                                             |
| other/coverage-shared      | `nx run py-common:coverage --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                               | 200 PASS, five opt-in broker skips exercised separately                                                         |
| test/query                 | `nx run query-service:test --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                               | 40 PASS                                                                                                         |
| test/console               | `nx run omni-console:test --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                                | 34 PASS                                                                                                         |
| test/contracts             | Pinned Linux generation twice, then `nx run contracts:test --excludeTaskDependencies --skip-nx-cache`                                                                                                                                                                                                                                                                                     | Deterministic hashes, buf build and nine required artifacts PASS; default Windows generator limitation below    |
| integration/atomic-sharing | `nx run platform:test-atomic-sharing --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                     | One four-owner workflow PASS, zero skips                                                                        |
| integration/legacy         | `nx run platform:test-integration --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                        | One many-step workflow PASS, zero skips; metadata failure/partial warnings preserve discovery                   |
| integration/overlap        | `nx run platform:test-overlap --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                            | One fail-closed overlap/replay probe PASS, zero skips; not writer exclusion                                     |
| integration/recovery       | `OMNI_P14_E2E=1 nx run platform:test --skip-nx-cache --args="--tests *JobRunBacklogRecoveryIntegrationTest"`                                                                                                                                                                                                                                                                              | One worker-recovery workflow PASS, zero skips; I9 durable membership restart is tested separately in PostgreSQL |
| integration/shared-kafka   | `nx run py-common:test-integration --skip-nx-cache`                                                                                                                                                                                                                                                                                                                                       | Five actual-broker cases PASS, zero skips                                                                       |
| format/docs                | Scoped installed Prettier write/check on the ten changed Markdown files                                                                                                                                                                                                                                                                                                                   | PASS; ten changed Markdown files                                                                                |

Python coverage/query commands also emit attributable JUnit XML. Exact command
arrays, source bindings and check logs are retained in the local gate directory;
Nx cache and Nx Cloud are disabled. `OMNI_P14_E2E=1` is set by the dedicated targets
or runner, and zero-skipped JUnit is checked independently of process exit status.
Scoped documentation formatting uses installed Prettier directly because the root
Nx formatter only supports all/changed-workspace scope and would touch unrelated
dirty Java/Python files. It does not weaken the Java Nx command boundary.

## Attributable Java coverage

Source: final `platform:coverage` XML, threshold 80% line and 80% branch for each
of the following 22 orchestration components. Existing Python worker gaps are
listed separately; SQL/YAML/UI use the executed behavioral matrix above.

| Component                      |    Line |  Branch | Executed tests in the final full suite                                                              |
| ------------------------------ | ------: | ------: | --------------------------------------------------------------------------------------------------- |
| JobProducerRegistry            | 100.00% | 100.00% | JobProducerRegistryTest; GraphWorkRequestsPostgresTest                                              |
| JobProducer                    |  94.29% |  81.97% | JobProducerTest; AtomicWorkExpansionPostgresTest; GraphWorkRequestsPostgresTest                     |
| JobRunWorkContext              |  87.34% |  94.44% | JobRunWorkContextTest; AtomicWorkExpansionPostgresTest                                              |
| JobRunPreparationService       |  98.87% |  84.21% | JobRunPreparationPostgresTest; GraphWorkRequestsPostgresTest                                        |
| GraphWorkOperationsController  | 100.00% | 100.00% | GraphWorkRequestsPostgresTest (HTTP success/authentication/400/404)                                 |
| SchedulerOutboxRepositoryImpl  |  99.14% |  88.46% | SchedulerOutboxCandidateTest; SchedulerOutboxRunStepTest; JobRunDispatchSnapshotPostgresTest        |
| JobRunDispatchPlanner          | 100.00% | 100.00% | JobRunDispatchPlannerTest                                                                           |
| JobRunDispatchSnapshotReader   | 100.00% |  81.82% | JobRunDispatchSnapshotPostgresTest; JobRunDispatchBenchmarkPostgresTest                             |
| JobRunDispatchStore            | 100.00% |  90.28% | JobRunDispatchSnapshotPostgresTest; GraphWorkRequestsPostgresTest                                   |
| JobRunDispatchCoordinator      | 100.00% |  98.15% | JobRunDispatchCoordinatorTest; JobRunDispatchSnapshotPostgresTest                                   |
| DefinitionConsolidationService | 100.00% |  88.00% | DefinitionConsolidationTest; DefinitionConsolidationLockPostgresTest; GraphWorkRequestsPostgresTest |
| DefinitionConsolidationStartup | 100.00% | 100.00% | DefinitionConsolidationStartupTest                                                                  |
| AtomicWorkDiagnosticsService   |  95.45% |  82.35% | AtomicWorkDiagnosticsValidationTest; GraphWorkRequestsPostgresTest                                  |
| GraphWorkRequestService        | 100.00% |  86.05% | GraphWorkRequestsPostgresTest; GraphWorkRequestPolicyTest                                           |
| SchedulerOutboxDispatcher      | 100.00% | 100.00% | SchedulerOutboxDispatcherTest; JobRunDispatchSnapshotPostgresTest                                   |
| GraphWorkExpansion             |  93.84% |  83.51% | GraphWorkExpansionTest; JobRunPreparationPostgresTest; GraphWorkRequestsPostgresTest                |
| WorkExpansion                  |  96.92% |  88.59% | WorkExpansionTest; AtomicWorkExpansionPostgresTest; ExpansionRoutesTest                             |
| StaticTopologyDeclaration      | 100.00% | 100.00% | StaticJobTopologyTest; GraphWorkExpansionTest                                                       |
| SchedulerOutboxService         |  99.08% |  90.00% | SchedulerOutboxRunStepTest; SchedulerOutboxCandidateTest; GraphWorkRequestsPostgresTest             |
| SchedulerClaimService          | 100.00% | 100.00% | SchedulerEntryClaimTest; GraphWorkRequestsPostgresTest                                              |
| GraphScheduledEntries          | 100.00% |  92.86% | GraphScheduledEntriesTest; SchedulerEntryClaimTest; GraphWorkRequestsPostgresTest                   |
| JobService                     |  95.22% |  87.50% | JobServiceTest; JobServiceClaimTest; JobServiceStatusCompatibilityTest                              |

## Remaining increment and activation gates

The renewed overlap fixture observed ERROR for both runs and replay after the
underlying EOD object was replaced; no indicator output was accepted. Its PASS
means approved-version mismatch fails closed with consistent replay. It is not
evidence of successful concurrent writers or shared-output exclusion.

- I8 is still verification_pending and its canonical gate is INVALID (missing
  required manifest). Do not infer I8 completion from the checks recorded for I9.
- The October 31 worker branch gaps (66.7% indicator, 58.3% signal, 71.4%
  Sector Wave) are resolved by the November 1 source-bound checks above. This does
  not close all I8 acceptance criteria or extend the claim to unrelated modules.
- Retained-catalog preparation latency/contention and an actual retained-data
  consolidation/rollback rollout have not run. Disposable upgrade/rollback tests
  establish local compatibility, not deployment evidence or a new activation flag.
- Shared atomic execution is not distributed output exclusion. The overlap probe's
  fail-closed outcome cannot establish safe shared-writer/version concurrency.
  Writer/P12, I10 readiness cutover and universal historical version binding remain
  deferred. Expired-token WAITING/BLOCKED progress is now explicitly tested locally;
  do not restate that particular repaired path as an unverified source defect.
- The ordinary Windows contracts compiler still lacks Debug CRT dependencies;
  the exact pinned Linux fallback below verifies artifacts, not a repaired Windows
  installation. No hosted CI, live provider, production or owner attestation claim.

## Cross-service blast radius

Graph refreshed at HEAD `54cf5c8`. Impact analysis of GraphWorkExpansion,
GraphWorkRequestService, DefinitionConsolidationService and GraphScheduledEntries
found 43 changed nodes, five impacted nodes and three additional files at depth 2:
GraphWorkOperationsController, JobScheduler and JobRunPreparationService. No output
truncation was reported. Java reflection, SQL functions, Kafka and cross-language
relationships still require this explicit reconciliation.

| Area                                    | Impact / evidence boundary                                                                                                                                                                                             |
| --------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Platform                                | Graph preparation/acquisition/ownership, consolidation, guarded dispatch, status aggregation and diagnostic reads; full Java suite plus disposable PostgreSQL and real worker workflows                                |
| Analyzer                                | Existing atomic indicator handler and approved-input bytes/date checks exercised by real worker; signal/Sector Wave regressions in full Python suite; no algorithm change in this task                                 |
| Ingestor                                | Existing catalog/EOD atomic commands, durable writes and status publication exercised; deterministic provider replaces external network only                                                                           |
| Shared Python / contracts               | Atomic identity/cutoff, Kafka commit/recovery, manifest/lineage regression; protobuf schemas unchanged; generated artifacts reproduced with the exact pinned toolchain fallback below                                  |
| Query Service                           | Existing logical output and lineage read verified by worker fixture plus 40 service tests; no API/path/schema change                                                                                                   |
| Console                                 | Existing authenticated diagnostic API client/view tested (34 suite tests); no browser session or visual QA claim                                                                                                       |
| Persistence / storage                   | Disposable PostgreSQL V14 and V16→V18 tests, V17 invariants, V18 reversible alias state; MinIO manifest remains readiness authority; no live/retained-data operation or SQL numeric coverage claim                     |
| Configuration / deployment / operations | Test-only graph enablement; default/runtime flags preserved; no writer/P12, distributed output exclusion or version-binding cutover; retained-catalog contention/performance and deployment acceptance remain separate |

## Toolchain and fixture corrections

The default Windows grpc-tools 1.13.1 executable cannot start because its Debug CRT
DLLs are absent. Do not interpret that as a schema failure or a passing Windows
generator. The recorded contracts check downloads that exact package's official
Linux x64 compiler, verifies `libprotoc 3.19.1`, runs it in an existing container with
network disabled/read-only canonical proto mounts, generates twice and compares all
artifact hashes. It then runs `nx run contracts:test --excludeTaskDependencies
--skip-nx-cache` (buf build plus the repository artifact assertions). Compiler/package
hashes and commands are retained in `contracts-toolchain.json` and
`contracts-fallback.py`. No generated output is hand-edited and no dependency is
upgraded. The ordinary Windows generation environment remains unresolved.

The first full Platform run found two Saturday-sensitive dependency fixtures and a
historical V14 diagnostic fixture loading V18 JPA entities. Dependency fixtures now
select an explicit latest weekday; production weekend behavior is unchanged. V14
inventory uses real JDBC/repository transactions without current JPA; the actual
markBlocked/snapshot parity case moved to the latest-schema read-model fixture.
Initial failures are retained, never waived or counted as passing runs.

## Documentation synchronization disposition

Updated owners: this report, Plan 030, increment registry/roadmap summary, root
ReleaseNotes, documentation index, job-flow evidence pointer, Platform README and
diagnostics index/scenario. Architecture, ADR-008, job flow, Kafka, data lake,
database, deployment, ownership/service indexes, Plan 027,
AGENTS.md, CLAUDE.md and repository rules were reviewed for this test-only extension.
No contract, runtime ownership, readiness authority, deployment procedure or agent
policy changes are introduced. Existing current-runtime/deferred-I10/writer
boundaries remain; local evidence pointers correct stale verification wording only.
Static consistency audit passed for the ten edited Markdown files: 439 relative
path links resolve, named test classes have executed final-suite JUnit, all I9 plan
sections are present, and registry statuses/dependencies remain aligned. Graph
change detection is bounded; its missing selector test links are reconciled against
executed GraphScheduledEntriesTest/PostgreSQL regressions and 100% line/92.86%
branch coverage. Graph links are not proof of coverage. No CI, owner attestation
or activation is inferred.
