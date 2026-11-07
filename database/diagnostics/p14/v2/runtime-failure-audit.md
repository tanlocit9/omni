# Runtime failure audit — 2026-10-30

Owner: [Plan 030](../../../../docs/plans/030-static-graph-dispatch-planner.md).
This is an observation and bounded regression report, not remediation or rollout.

2026-11-04 follow-up: the legacy exact-day admission gap described below now has
a source repair and disposable PostgreSQL/Kafka/worker/Query regressions. See the
[I10 guard matrix and remaining gates](../../../../docs/reference/008-p14-i10-dispatch-guard.md).
The observations and counts below remain historical; this does not establish live
recovery or resolve unlocalized errors lacking stack/version evidence.

## Unfinished-work integration follow-up — 2026-10-31

This follow-up reuses the October 30 observations as scenario inputs; live database
counts/consumer membership were not refreshed and no live worker was restarted.

| Scenario                                                                    | Integration source                                         | Current evidence                                                              |
| --------------------------------------------------------------------------- | ---------------------------------------------------------- | ----------------------------------------------------------------------------- |
| Crash before terminal ACK; same command retained                            | test_kafka_offsets.py abrupt-exit case                     | PASS in fresh broker run                                                      |
| Crash after status ACK but before commit; duplicate delivery possible       | Same parameterized abrupt-exit case                        | PASS                                                                          |
| SUCCESS ACK then commit survives restart without redelivery                 | Existing committed-output/status case                      | PASS                                                                          |
| ERROR ACK then commit is not automatically retried                          | New acknowledged-error case                                | PASS                                                                          |
| Broker outage, HTTP 503 and automatic same-group recovery                   | test_consumer_recovery.py                                  | PASS                                                                          |
| PUBLISHED without terminal status, reconstruct dispatcher after one day     | Extended JobRunPreparationPostgresTest publication case    | PASS; no automatic completion or republish                                    |
| PUBLISHED/RUNNING worker crash and same execution/approval/date recovery    | New JobRunBacklogRecoveryIntegrationTest                   | PASS; child and parent SUCCESS after redelivery                               |
| Replay completed work without extra execution/outbox or duplicate dated row | Same new workflow, boundPayload replay                     | PASS; actual worker ACK SUCCESS and one dated output row                      |
| Shared version interleaving/replay, stale READY and Query/metadata safety   | Existing cross-service and shared-ancestor workflows rerun | PASS; version mismatch fails closed, stale input rejected without output loss |

Local gate **PASS P14-unfinished-integration-20261031, required=3/pass=3**:
five real-broker tests, 36 PostgreSQL component integration tests and three real
worker workflows passed: **44 tests, zero failures/errors/skips in the final runs**.
Read through `python tools/check_result.py conclusion --increment P14-unfinished-integration-20261031`.
Executed commands, each recorded through `tools/check_result.py run`:

- `nx run py-common:test-integration --skip-nx-cache`
- `nx run platform:test --skip-nx-cache --args="--tests *JobRunPreparationPostgresTest"`
- `nx run platform:test --skip-nx-cache --args="--tests *JobRunCrossServiceIntegrationTest --tests *JobRunSharedAncestorIntegrationTest --tests *JobRunBacklogRecoveryIntegrationTest"` with `OMNI_P14_E2E=1`.

Copied JUnit, JaCoCo, source hashes and workflow artifacts are in
`.agent/check-results/P14-unfinished-integration-20261031/`. Seven test/fixture
source hashes were unchanged across the final worker run; the Kafka and PostgreSQL
test sources also match their earlier passing runs. The first worker run failed
two scenarios because the Friday fixture met the real Saturday snapshot clock;
after fixture clock alignment, all three workflows were rerun without skips.
No full increment completion, numeric coverage threshold, CI, lint/format, provider
or retained-environment verification is implied.

The worker fixture now carries one accepted businessDate to the provider and stale-
input helper. Its preparation clock selects the latest weekday, avoiding Saturday/
Sunday zero-work expansion during tests. Disposable workers inject that same date
into the catalog/metadata current-snapshot clock; validation still runs rather than
being bypassed. Production weekend policy and clocks are unchanged.
Preparation is called directly for every fixture target with unique due instants;
scheduler entry selection remains covered by its separate claim suites.

The new workflow kills only its disposable Analyzer subprocess after an entry
marker and before terminal status. Restart uses the same consumer group and frozen
command; assertions bind identity, date, input version, output rows, offsets and
execution/outbox counts. More than one handler entry does not mean new work.
The observed crash/replay workflow had three handler entries, two SUCCESS ACKs,
one output row and unchanged execution/outbox counts. In the adverse overlap
workflow both commands and the replay returned ERROR for stale approved versions;
that proves fail-closed version binding, not successful concurrent writes or writer
exclusion. The stale-READY workflow still reproduces legacy admission of an input
missing the accepted day, followed by worker ERROR with the prior output preserved.
The orphan publication case uses real PostgreSQL with a mocked Kafka publisher;
it characterizes persisted state and dispatcher reconstruction, not broker-retention
recovery. A message already committed/missing from the broker cannot be restored
from RUNNING state alone. I10 watchdog/recovery policy and real provider validation
remain unimplemented/outside this scope.

Blast radius: Platform integration helpers and PostgreSQL test assertions, Analyzer/
Ingestor fixture clock/markers and shared Kafka integration tests. No production
code, schema, topic, broker offset, business data or activation/configuration change.
Query Service is exercised by the existing worker story; Console has no impact.
This report, Plan 030 and release notes own the evidence update; canonical runtime,
ownership, deployment, ADR/data/flow and agent policy remain accurate. No commit/push.

## Capture boundary

The first local PostgreSQL capture was at 2026-10-30 16:01:50 UTC (23:01:50 Vietnam).
Subsequent grouped queries and Kafka offsets are separate observations, not one
atomic cross-system snapshot. Database queries ran in READ ONLY transactions with
statement/lock timeouts and rolled back. No business data, migration, job status,
offset or live service was changed; tests use disposable containers/fake providers.
SQL and redacted grouped results are in the ignored local directory
`.agent/diagnostics/p14-errors-20261030/` (`01-preflight` through `06-other-stock`).

The live database has Flyway V16, no I9 atomic-work tables and zero JobRuns.
The V17/V18 v2 operational queries require prerequisites that this database lacks.
The audit therefore queried legacy execution/outbox tables; it does not show that
the source-present I9 route is active or that current migrations were deployed.

## Observed groups

Exact execution counts: 19,450 failed children, 87,814 RUNNING children and 253,478
successful children. Parent counts are kept separate: 108 FAILED, 843 PENDING,
64 RUNNING and 2,119 SUCCESS. RUNNING is stored execution state, not worker liveness.
Historical unfinished rows start in August; they cannot be equated to current lag.

For children created on 2026-10-30, every observed failure falls into these groups:

| Job type                   | Failed children            | Error                                         |
| -------------------------- | -------------------------- | --------------------------------------------- |
| SYNC_SIGNALS               | 3,054 across 1,195 symbols | Input does not cover business date 2026-10-30 |
| SYNC_INDICATORS            | 1,195                      | Same missing input day                        |
| PRECOMPUTE_SYMBOL_FEATURES | 1,195                      | Same missing input day                        |
| PRECOMPUTE_SECTOR_FEATURES | 19                         | Calculation produced no output for 2026-10-30 |
| SECTOR_ROTATION_BACKTEST   | 1                          | Same missing output day                       |

The sum is 5,464 failed children, including 5,444 missing-input-day failures.
UPCOM-CLI is confirmed by the database as the signal error at 22:03:53 Vietnam;
the following CNT log is a different message. No provider call was made to classify it.

The stock batches created on October 28, 29 and 30 each contain 1,195 RUNNING
children and no SUCCESS. The latest successful stock execution was created on
October 27; this does not establish every stored EOD object's maximum date.

Kafka committed-offset lag at inspection:

| Consumer input             | Lag   |
| -------------------------- | ----- |
| Stock prices               | 3,585 |
| Intraday EOD               | 2,858 |
| Signals                    | 530   |
| Sector Transition outcomes | 57    |
| Platform job status        | 0     |

All observed groups had no active members at that instant. This does not establish
why they stopped or whether they were running at an earlier time. The stock lag
matches three 1,195-message batches, but individual Kafka offsets were not joined
to database execution IDs. PUBLISHED outbox and zero status-topic lag do not prove
successful computation or terminal status for historical executions.

Historical error groups include:

| Category                                            | Children  | Last execution created |
| --------------------------------------------------- | --------- | ---------------------- |
| Intraday retry wrapping TypeError                   | 3,965     | September 16           |
| Mixed string/date stock sort                        | 2,292     | September 3            |
| Provider read timeout                               | 2,273     | October 28             |
| Exact canonical EOD missing/ambiguous               | 2,183     | October 28             |
| Provider no terminal trade result                   | 979       | October 28             |
| Missing input columns: symbol features / indicators | 606 / 248 | September 3 / 4        |
| Provider wrong local date                           | 592       | October 28             |
| Closed stock HTTP client                            | 375       | September 10           |
| Missing symbol-feature input objects                | 256       | September 1            |
| Provider HTTP 418 / 503                             | 131 / 11  | October 28 / 23        |
| Indicator NoneType error                            | 60        | October 27             |
| Intraday reconciliation rejection                   | 11        | October 27             |
| Other stock failures                                | 4         | September 7            |

The four remaining stock records are one server disconnect and three blank error
strings. Stored historical errors do not establish that the same defect remains
in the current source. Unit tests cannot prove external provider recovery.

## Source and test reconciliation

| Scenario                                                                      | Source / test                                                                    | What it establishes                                                                              |
| ----------------------------------------------------------------------------- | -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| READY EOD lacks requested day for three strategies                            | SignalJobHandler + SignalKafkaService; new parameterized test in test_signals.py | ERROR, preserved execution/work identity, no signal/notification writes                          |
| Symbol/sector/backtest has no target-day data/output                          | SectorWaveJobHandler and real calculations; new test_sector_wave_atomic.py       | Exact observed error classes, no output replacement                                              |
| Indicator missing day / future cutoff / preserved other days                  | Existing test_atomic_work_handler.py                                             | No output for absent target day, no future input and no unrelated-date loss                      |
| Stock mixed dates and atomic missing-day/provider/write failures              | test_stock_prices.py and test_atomic_stock_work.py                               | Current fake-provider handling is safe; not live provider verification                           |
| Intraday wrong local date, missing/ambiguous EOD and reconciliation rejection | test_intraday_eod.py and test_intraday_eod_publication.py                        | Fail-closed validation and READY-last tests with fixtures                                        |
| PUBLISHED without terminal status is not SUCCESS                              | Existing JobRunPreparationPostgresTest                                           | Transport publication cannot complete execution/parent by itself                                 |
| Legacy READY admission with stale EOD, real worker and preserved output       | Added stale-input phase in JobRunCrossServiceIntegrationTest                     | PASS: old EOD READY is published; worker ERROR/parent FAILED; existing indicator bytes unchanged |

The added integration uses current migrations in a fresh container while explicitly
exercising the legacy producer route with job_run_id NULL. It is not a migration or
compatibility test against the existing live V16 database. Only market providers
are replaced; broker, storage, metadata synchronization and worker status are real.

The final stale-ready artifact records businessDate=2026-10-30 and latestInputDay=
2026-10-29, with legacyPublishedStaleReady/workerRejectedMissingDay/
previousOutputPreserved all true. The success story still exercises Query Service,
partial metadata warnings and preservation of discovery on corrupt Parquet.
An intermediate attempt encountered the independent 60-second Java manifest cache
window: the worker rejected an outdated approved version. The final missing-day
scenario invalidates only the fixture EOD cache through its public API after the
metadata update, isolating date coverage from version mismatch. This does not make
live cache invalidation or cache-freshness guarantees part of the repair.

## Remaining work

1. Restore/inspect consumers and ingestion backlog under a separate operational
   action; this audit did not start or retry them. Recheck committed lag and exact
   input-day coverage before creating replacement work.
2. Exact business-day readiness remains unresolved in the legacy manifest guard.
   READY/version/row count do not imply presence of the accepted business date.
   Fail-closed consumer tests protect data; passing characterization of premature
   admission is not a fix for admission. I10 plans exact work/date registration.
3. Published executions without terminal status remain an operational gap. I10's
   watchdog is planned, not implemented; a timeout will not prove writers stopped.
   Do not mass-complete or clear historical rows based only on broker lag.
4. Approved-input replay is frozen. Replaying an old failed execution must not
   silently substitute a newer input version. Recovery needs a supported new
   attempt/request after readiness, not raw outbox replay that changes identity.
5. Real provider timeout/empty-result/rate-limit verification and the old blank-error
   records remain outside the fixture gate. The 60 historical indicator NoneType
   failures cannot be localized from the stored error string alone; a stack trace
   and versioned input snapshot are still required. No blanket retry/timeout increase is proposed.
6. Whole-handler coverage remains incomplete: SignalJobHandler is 88.8% line /
   58.3% branch, SectorWaveJobHandler 90.9% / 71.4%. The common atomic day guard is
   100% line/branch and the stock handler 100% line/branch in the executed suites.
   This bounded audit does not satisfy the >=80% per-component branch requirement
   for complete I8/I9 acceptance or prove every atomic success/retry/confirmation path.

## Gate and documentation boundary

Main gate: **PASS P14-live-error-audit-20261030 required=4 pass=4** (Analyzer/
py-common coverage, Platform focused regression and cross-service integration).
Supplementary fake-provider Ingestor gate: **PASS P14-live-error-ingestor-20261030
required=1 pass=1**. Read both through tools/check_result.py conclusion.

- Analyzer: 174 pass, including six new parameterized signal/Sector Wave cases.
- py-common: 200 pass / four opt-in Kafka skips.
- Platform focused dependency/outbox/preparation suites: 55 pass.
- Ingestor: 73 pass with fake providers, including mixed dates and exact EOD date.
- Disposable cross-service workflow: one pass with added stale-ready phase.

Total: 503 passing tests and four opt-in skips across the recorded scopes. Final
tested-source hashes, copied reports and workflow artifacts are in
`.agent/check-results/P14-live-error-audit-20261030/`. Initial py-common runs failed
Windows temp permissions/socket length, fixed with a fresh temp root/short socket
directory. Integration fixture setup was corrected to activate the storage registry
and reload a fenced claimed definition. No failed check was waived or business guard
weakened. Build/lint/format/full-Platform coverage, live provider, retained V16
migration compatibility and Console/browser checks were not run for this audit.
This audit does not close I8/I9, implement I10/P12, activate flags, run retained
migrations or claim production recovery.

Blast radius: regression/fixture source in Platform and Analyzer; existing Ingestor,
shared-library and dependency/outbox behavior is exercised without production edits.
Query Service runs in the existing disposable success story; Console has no source,
UI or API changes. Persistence/configuration/transport/storage ownership remain.
Plan 030, release notes and this diagnostic report/index own the evidence updates;
architecture/ADR, Kafka/lake/database, deployment, service/ownership guides and agent
rules need no changes because runtime contracts and activation are unchanged.

Static consistency review preserves current increment IDs/statuses/dependencies,
checks the report/index/Plan 030/release-note links and separates runtime observations
from source-bound local gates. Earlier non-signal selector changes remain in the
pre-existing dirty worktree; this audit adds tests/fixtures and evidence only.
Graph impact/change analysis was refreshed and reconciled with direct callers;
new untracked fixture/test paths are included explicitly in change detection.
No formatter, executable documentation checker, commit or push is part of this task.
