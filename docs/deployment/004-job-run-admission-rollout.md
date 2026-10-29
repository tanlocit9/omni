# JobRun Admission Rollout and Rollback

2026-10-30 input-binding repair: deploy matching Platform, py-common and Analyzer
code before relying on atomic indicator input-version validation. Replay tooling
must use `SchedulerOutboxService.boundPayload`, preserving the first stored
approval; raw intent JSON is not the complete publish envelope. Missing/stale
atomic bindings fail before output. No retained migration, flag activation or
deployment is recorded by the local repair tests.

Current P14 status (2026-10-30): **I8 verification_pending; I9 in_progress**.
I9.a adds read-only graph/atomic planning; it does not acquire or dispatch work.
Common preparation now persists atomic dated work and sends the additive
`jobType`/`businessDate`/`parameters`/`atomicWorkId` envelope. V16 protects accepted
snapshots; matching Java/Python rollout is required even on the legacy scheduler.
Consumers preserve other dates and retain lookback; catalog/metadata are current
snapshots only. Writer and shared ancestors remain deferred. EOD-to-indicator
execution binding is implemented; broader version/concurrency guarantees remain
deferred.
The original I8 delivery ran no checks; later verification and repair evidence
is recorded in Plan 030. This supersedes earlier design-only I8/I9 statements.
See [contract, impact matrix and local handoff](../plans/030-static-graph-dispatch-planner.md#i8-source-delivery-and-local-verification-handoff--2026-10-29).

This runbook covers Plan 030 P14-I4's Platform-local planner/admission path.
It is operational guidance, not evidence that a retained environment was deployed.
See [Plan 030](../plans/030-static-graph-dispatch-planner.md) for executed local
checks and [ADR-007](../adr/007-scheduler-claim-and-outbox-boundary.md) for ownership.

## Configuration boundary

- `app.scheduler.job-run.planning-enabled` defaults to `true`, through
  `APP_SCHEDULER_JOB_RUN_PLANNING_ENABLED`. This selects bounded JobRun-aware
  planning and guarded legacy FIFO; it does not create new runs.
- `app.scheduler.job-run.preparation-enabled` remains `false`. When enabled, the
  owner-approved policy claims only terminal definition entries (no successors),
  including standalone metadata. All 32 identities/producers remain available to
  prepare ancestor paths. Cross-run overlapping/shared writers still need rollout
  evidence; this policy does not activate preparation or remove either flag.
- Enabling preparation while planning is disabled fails startup. An outbox claim
  lease must exceed the 15-second publish timeout; the existing two-minute default
  is retained. Review custom lease overrides before upgrading.
- No new table/index/migration, Kafka/worker payload, manifest or dataset-path
  change is introduced by I4. The I3 V12/V13 membership schema is required; V14
  manual-audit retirement retains its separate stop-readers/backup boundary.

## Shared-ancestor integration — 2026-10-27

**NOT READY: the dedicated safety integration test reproduces successful execution
using an EOD version different from the version approved at admission.** The owner
reports V15 already applied to the retained database; this experiment neither
queries that database nor reapplies a migration. V15 is not the cause of this
failure and is no longer treated as an unconfirmed application step on that basis.

```bash
nx run platform:test-overlap
```

The target creates its own PostgreSQL 16, Kafka 7.6.0 and MinIO containers and uses
the actual Platform preparation/admission/status path, Ingestor stock handler,
Analyzer metadata/indicator workers and Query Service S3/DuckDB reader. It reuses
the isolated harness behind `platform:test-integration`; root Python environments
and image prerequisites are the same. Production services/configuration are not
started, migrated or reconfigured, and no retained offsets are reset.

The test prepares two separate runs of the same bounded indicator target, sharing
the actual symbols/stock definition ancestors and the HOSE/HPG canonical EOD path.
These are explicit internal test targets, not a change to terminal scheduled-entry
policy. A test-only worker-boundary pause gives a deterministic adverse interleaving:

1. Run A's actual ancestors write 180 rows. Real metadata sync publishes V1.
2. Platform approves V1 and publishes A's indicator command. The fixture pauses
   immediately before the real indicator handler reads its input.
3. While A remains unfinished, run B's actual ancestors append a new daily row to
   the same EOD object. Real metadata sync publishes V2 with 181 rows. No production
   guard cache/retry is invalidated or bypassed.
4. The test releases the indicator worker. Both executions report SUCCESS while
   their recorded approvals are V1 and their actual Parquet output lineage is V2.
5. Replaying A's original published command, through the actual Kafka publisher
   port with the same execution identity, acknowledges a second attempt using V2.
   Persisted terminal state remains SUCCESS. Query Service reads 181 READY rows
   whose current lineage matches V2, without reconciling either execution's V1
   approval. The final safety assertion fails; this is not an infrastructure error.

`P14-shared-ancestor` records this as a **failed integration gate**, not a passing
readiness check. The source-bound `overlap-result.json`/`overlap-evidence.json`
under the ignored local check-results directory archive versions, per-command
output/ack observations, replay count and the failed JUnit assertion. The original
one-run evidence remains historical; it did not cover this interleaving. Passing
fixture lint or a baseline run does not turn this negative safety result into PASS.

The original `platform:test-integration` was rerun with overlap controls disabled
and passed its single full-chain scenario. All three helper-project lint checks
and the static documentation audit pass. The complete eight-check gate remains
FAIL because the additional safety test fails. No production code was changed.

Root cause reconciliation: `SchedulerOutboxService.persistApprovedInputs` stores
approved versions on the actual work execution; admission does not bind those
versions into the worker's input-read contract. `IndicatorJobHandler.handle` reads
the current global metadata and current canonical Parquet path. EOD/indicator
`ParquetStorage.write_dataframe` overwrites canonical objects. Row locks, claim
fencing and closed-run membership protect scheduler state, not a dataset snapshot
across independent runs. The existing manifest cache can also approve an older
version while newer discovery exists; the test keeps that behavior unchanged.

Required resolution before default activation: preserve an approved input identity
through worker execution and verify the bytes/version actually consumed, or reject
changed inputs without publishing false successful lineage. Providing historical
snapshot reads or preventing conflicting writers requires an explicit coordinated
design; this test-only change selects neither architecture and introduces no
production workaround. The regression intentionally remains red until safety is
implemented; it accepts terminal failure instead of successful mismatched approval.
This proves the absence of admission-version pinning; it does not demonstrate lost
rows or corrupt Parquet, and the current V2 output lineage is internally consistent.
Accepting "latest at processing time" instead would require an explicit policy and
truthful consumed-input evidence rather than silently treating approval as pinning.

Blast radius: Platform owns the opt-in test/target and claim/status evidence;
Ingestor's provider fixture supplies 180 then 181 rows to its unchanged real handler;
Analyzer's fixture pauses the unchanged real handler and observes its output/status
ack; Query Service's fixture accepts the scenario's expected row count and still
uses the unchanged resolver/executor. Console and shared/generated contracts have
no source change. No production dataset path/owner, persistence model, runtime
flag or migration changes. Test coverage is behavioral reproduction across processes,
without a numeric production coverage or safety pass. This does not prove true
simultaneous multipart writes, every terminal workload, provider corrections or load.

Plan 030, release notes, service test guidance, Kafka/data-lake limitations and
ADR-007 record the result. Registry/status/dependencies, database schema, other
architecture/flow ownership, deployment defaults, indexes and agent guidance need
no new authority or contract update. Graph impact/change tools are unavailable;
targeted source and canonical-document reconciliation are the stated limitation.

## Default-flow readiness review — 2026-10-25

**NOT READY to remove the flags or enable preparation by default.** This review
prepares the removal scope; it does not change configuration, the scheduler entry
catalog, runtime code or a retained database. Local increment completion in
[Plan 030](../plans/030-static-graph-dispatch-planner.md) does not establish
cross-service delivery/completion safety.

This initial assessment predates the
[offset safety follow-up](#offset-safety-follow-up--2026-10-25) below. The reproduced
offset/startup defects are now fixed and locally verified. The
[October 26 warning policy](#temporary-metadata-warning-policy--2026-10-26) now
tolerates returned partial results with SUCCESS and warning diagnostics;
object classification, entry-target policy,
recovery supervision and whole-chain activation remain open.

The [worker/entry follow-up](#worker-recovery-and-terminal-entries--2026-10-26)
subsequently resolves warning status, lifecycle health/recovery and entry selection.
The blocker list below records the initial review; overlap/full-catalog and retained
rollout evidence remain open, rather than those repaired prerequisites.

### Confirmed blockers and remaining evidence

1. **Offset ownership (TD-009):** the shared Python Kafka factory leaves
   `enable_auto_commit` implicit. A disposable Kafka 7.6.0 probe using the actual
   factory reproduced committed offset `1` for fetched record `0` before any
   handler completion, output or terminal-status publication. After abrupt process
   exit, a replacement consumer in the same group started at `1` and replayed zero
   records. This proves the offset boundary can strand unfinished work; it is not
   a reproduction of a complete worker or a claim of loss in a retained environment.
   Ingestor startup additionally discards the result of its initial `getmany()`.
   Define commit ownership after durable output and acknowledged terminal status,
   retain startup batches, and exercise failure/restart and publish-failure paths.
   Poison handling needs an explicit durable disposition, not an unconditional
   commit in `finally`. See [TD-009](../technical-debt/009-python-kafka-worker-throughput-and-offset-safety.md);
   this narrow correctness prerequisite does not authorize its concurrency rollout.
2. **Terminal status contract (TD-001):** Analyzer metadata sync emits
   `PARTIAL_SUCCESS`; Platform's persisted status enum has no corresponding value.
   `JobService.applyStatus()` resolves it to null and returns without an execution
   update. A run containing such a step cannot close from that message alone.
   Approve a coordinated partial-result policy, then test the actual Analyzer
   payload through Platform persistence, parent aggregation and the JobRun barrier.
   Do not silently map partial data to SUCCESS. See
   [TD-001](../technical-debt/001-p3-i5-metadata-reconciliation.md).
3. **Entry targets and shared ancestors:** all 32 cron identities remain.
   `JobRunPreparationService.supports()` checks enablement, cron and topology
   membership; it does not select only terminal/standalone entry targets. Existing
   PostgreSQL tests exercise separate runs for independent targets sharing
   ancestors, not safe concurrent publication of those ancestors. Select entries
   with the owner and verify overlap, duplicate delivery and exact-version lineage
   before replacing scheduled legacy preparation. Do not delete ancestor
   definitions/producers or assume shared-work coalescing exists.
4. **Full integration:** execute Platform -> real Kafka -> real worker -> disposable
   object storage -> terminal status -> `applyStatus()` -> next JobRun step, with
   Query Service reading the resulting READY dataset. Cover success, failure,
   restart before/after output, duplicate/retry, status publication failure,
   WAITING/BLOCKED, overlapping scopes, READY-last and exact input lineage.
   Existing fake-provider/publication tests and the offset probe do not satisfy
   this gate. No retained migration, provider, hosted CI or deployment was verified.

### Cross-service impact and executed evidence

| Boundary                   | Impact / evidence and limit                                                                                                                                                                                                                                                                                                                             |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Platform                   | Preparation flags select legacy intent versus JobRun creation; planner flag can bypass the cohort barrier. Existing P14-I7 gate is reused: 444 tests / 67 suites; SHA-256 bindings for 32 source/test files still match. The 31 critical Java components meet that gate's >=80% line/applicable branch requirement. Java was not rerun for this review. |
| Ingestor                   | Command consumption, startup batches, immutable publication and status acknowledgement are directly relevant. `nx run ingestor:test` passed 33 tests; `nx run ingestor:test-integration` passed 2 tests using MemoryStorage, StubAdapter and a recording status publisher, without a broker or MinIO.                                                   |
| Analyzer                   | Command processing, metadata partial status, input-version selection, publication and status acknowledgement are directly relevant. `nx run analyzer:test` passed 142 tests; mocks do not establish the cross-language completion contract.                                                                                                             |
| Shared libraries/contracts | `nx run py-common:test` passed 134 tests. Actual factory offset behavior was reproduced against a disposable broker. Production transport remains JSON; no proto/generated schema, topic, storage path or ownership change is made here. Numeric attributable Python coverage for these integration seams is not established by suite success.          |
| Query Service              | Downstream READY/lineage reader, not a scheduler/status consumer. `nx run query-service:test` passed 40 tests; no live read of a dataset produced by this workflow was exercised.                                                                                                                                                                       |
| Console                    | No scheduler authority, worker or storage writer changes. No UI changes/tests in this review; operational presentation continues to belong to Plan 029.                                                                                                                                                                                                 |
| Persistence                | Existing V15 normalization/guard and frozen membership remain prerequisites; the earlier PostgreSQL gate covers migration and admission. This review applies no retained migration or cleanup.                                                                                                                                                          |
| Configuration/operations   | Preparation remains false, planning true. No entry/crons or deployment overrides were changed. Test broker used a unique container, loopback-only port and no retained volumes; it was removed after the probe.                                                                                                                                         |

Python checks used the declared Nx targets with cache bypass. The specialized
broker probe and source-evidence hash check have no matching Nx target and were
recorded through `tools/check_result.py` as explicit exceptions. Local evidence is
under ignored `.agent/check-results/P14-integration-readiness/`, including
`offset-risk.json`; a passing reproduction means the unsafe behavior was observed,
not that readiness passed. The source-bound Platform evidence remains under
`P14-I7`. No new owner attestation or numeric cross-service coverage pass is claimed.

### Prepared removal sequence

1. Resolve the offset/startup correctness slice and approved terminal-status
   mapping in their existing owners; add focused executable regressions for each
   failure before testing the whole chain. Define a reproducible disposable broker/
   storage integration target and an impact/source/test matrix for the scenarios
   above. Obtain attributable coverage for changed critical code.
2. Record the approved scheduled entry set and overlap policy. Replace the
   `JobScheduler.scan()` legacy preparation branch only for supported entries;
   prove unsupported definitions are rejected or deliberately routed according to
   that policy. Preserve claim transactions and supported independent triggers.
3. In `JobRunPreparationService`, remove the enablement constructor/property and
   `isEnabled()`/`supports()` flag conditions after reconciling their callers. In
   `JobRunDispatchCoordinator`, remove the two flag injections, startup combination
   check, disabled `load()`/`claim()` branches and `Mode.DISABLED` callers. Keep
   barrier-preserving fallback, fencing and the canonical NULL-JobRun lane: these
   are not the retired parent-FK alias reader, and flag removal does not authorize
   dropping them.
4. Remove both properties from `application.yaml` and reconcile external deployment
   overrides, startup tests, coordinator/preparation tests and examples. Replace
   flag-combination tests with unconditional guarded-startup/admission tests and
   verify no alternate unguarded scheduled dispatch remains. Update the rollback
   procedure to compatible artifacts and quiescence instead of flag toggles.
5. Rerun the complete scoped gate and whole-chain failure/restart scenarios. Only
   then review retained inventory/backup/V15 rollout and activate approved entries.
   A safe rollback must preserve persisted runs, output versions and delivery state.

Documentation reconciliation: this runbook owns readiness; Plan 030 and release
notes link the result. Architecture/ADR/flow/data/service documents, roadmap
statuses/dependencies, indexes and repository guidance require no behavioral or
ownership update because this review changes no implementation, schema, contract,
entry selection or authority. TD-001/TD-009 remain open; their runtime-safe fixes
and owner decisions are not marked completed by this evidence.

## Offset safety follow-up — 2026-10-25

The narrow TD-009 offset/startup fix uses the
[canonical manual-commit contract](../data/001-kafka-contracts.md#python-command-offset-ownership).
Shared factory consumers disable auto-commit. Both worker paths commit exactly one
processed partition offset after handler output and acknowledged terminal status;
Ingestor's discarded startup poll is removed. Missing/invalid execution UUIDs,
handler/status failures, cancellation and failed commits leave work uncommitted
and stop consumption before later offsets can cross it. Started Ingestor clients
are cleaned up even when storage initialization fails; retry cleanup no longer
calls nonexistent client `started()` methods.

Executed local tests: py-common 155 passed (three Docker cases skipped in the unit
target), Ingestor 46 passed, Analyzer 142 passed, plus all three real Kafka cases
through `nx run py-common:test-integration`. Abrupt crash before status and after
status ack/before commit both replay; output/status ack followed by successful
commit does not replay on restart. The test broker is isolated and removed after
the suite. Four critical production files meet >=80% line/branch coverage; the
[TD-009 evidence](../technical-debt/009-python-kafka-worker-throughput-and-offset-safety.md#offset-correctness-update--2026-10-25)
provides the file/source/test matrix location and exact rates. Lint and formatting
are scoped to py-common/Ingestor; no Java, provider, CI or retained migration is
claimed by this follow-up.

This fix preserves sequential at-least-once processing. An acknowledged status may
be duplicated if the process exits before command commit. It does not repair
previously advanced retained offsets, prove idempotency of every dataset writer,
add durable quarantine or automatically restart a failed background consumer.
Existing HTTP health does not establish consumer liveness. Observe the logged
topic/partition/offset, inspect the failure and perform explicit recovery without
resetting offsets or fabricating execution success. Poison commands remain
fail-closed until an authorized disposition is available.

For flag removal, the offset implementation prerequisite is locally satisfied;
operational recovery, the TD-001 coordinated status policy, approved entry targets
and the full workflow gate above remain required. P12 capacity/concurrency and
roadmap statuses are unchanged.

Follow-up blast radius: Ingestor's three command routes and all Analyzer subclasses
of `JobStatusKafkaService` use the shared loop; py-common owns the factory/loop.
Platform receives the same JSON statuses and may see duplicates after replay;
its source and schema are unchanged by this fix and Java was not rerun. Query
Service and Console have no command-consumer or commit ownership; no tests were
rerun for those unchanged readers. Topic/proto fields, dataset writers, physical
paths, manifests, database/migrations and deployment flags are unchanged. Operations
gain the explicit disposable-broker Nx target and failure/recovery guidance.
Canonical Kafka docs, service/library guides, TD-009, TD-001, Plan 030 and release
notes are synchronized. Architecture/ADR/flow/data-lake/database, roadmap registry/
dependencies, indexes and agent guidance retain their existing ownership/contracts;
no new document, increment, runtime configuration or authority requires an update.

## Metadata status follow-up — 2026-10-26

This records the initial policy, superseded by the temporary warning policy below.

The owner approved mapping partial metadata results to existing ERROR, normalized
by Platform to FAILED. Analyzer preserves publication/scan counters and supplies
an explicit skipped/failed-object error; complete results remain SUCCESS. This
closes the unsupported-label mismatch for new metadata messages. A failed status
does not roll back discovery that was already published, and auxiliary skipped
objects remain conservatively failed until expected-object classification exists.

Analyzer regressions compare actual serialized status against the same JSON
fixture consumed by a Java `applyStatus()` regression. These tests use a mocked
repository and do not establish retained persistence, broker E2E or live recovery.
Old ignored PARTIAL_SUCCESS messages/executions are not automatically repaired.
Full workflow evidence, entry/overlap policy and recovery gates remain required
before flag removal. No runtime flag, migration, enum or topic is changed.

Blast radius: Analyzer metadata producer behavior changes; shared Python status
DTO and JSON shape remain compatible. Platform uses its existing ERROR/FAILED
handler, persistence/notification/parent and planner logic, with focused regression
tests. Ingestor does not emit this metadata status; Query Service/Console are
readers and gain no new status value. No shared storage path, dataset owner,
manifest writer, database model or deployment setting changes. Kafka docs, service
guide, TD-001/index, Plan 030 and release notes are synchronized; architecture,
ADR/flow/data-lake/database, roadmap registry/dependencies, indexes and agent
guidance need no authority/schema update. Graph tools are unavailable in this
session; source/test reconciliation supplies the bounded impact assessment.

## Worker recovery and terminal entries — 2026-10-26

The owner approved fixing Ingestor warning completion, truthful worker health and
automatic recovery, and selecting the furthest downstream scheduled entries.

- Ingestor symbols now reports SUCCESS on classification warnings, preserving
  `metaJson.result=PARTIAL_SUCCESS`, warnings, classification source and counters.
  Existing top-level diagnostic extras remain for compatibility. A warning is
  logged with execution identity; provider/data/publication exceptions still emit
  ERROR. This changes no shared enum or Platform status parser.
- The shared supervisor retries a cleaned-up failed worker session with exponential
  delay from 5 to 60 seconds. Analyzer services recreate Kafka clients in the same
  group; Ingestor restarts its complete routing/storage session. Neither resets nor
  skips offsets. Cancellation stops recovery, including cleanup-error paths.
  Poison records recur with backoff and still need an authorized disposition.
- Ingestor health returns 503 before readiness or during recovery. Analyzer checks
  every enabled Kafka service and returns 503 if any is not running/recovering;
  disabled consumers are not required. These are task/client lifecycle checks,
  not a guarantee of broker responsiveness, provider availability or forward progress.
- When preparation is enabled, scheduled claims are limited to definition-level
  sinks in the canonical static graph. Current entries are `SYNC_METADATA`,
  `EVALUATE_SIGNALS`, `SECTOR_ROTATION_BACKTEST`, `SECTOR_TRANSITION_ANALYZE` and
  `SECTOR_TRANSITION_EVALUATE_OUTCOMES`. Bounded identity lookup resolves those
  entries to persisted IDs; SQL filters them before ordering/batch limits and
  `FOR UPDATE SKIP LOCKED`. Missing/inactive entries produce no legacy fallback.
  Non-entry definitions retain their identities/configuration and remain available
  as run ancestors; their cron is not independently claimed in enabled mode.
  Disabled preparation retains historical claims; explicit internal target
  preparation remains available and does not restore retired public triggers.

Current evidence is recorded under `P14-default-readiness`. Python tests cover
warning/healthy/error status, failed/healthy consumer health, client recreation,
shutdown cancellation and cleanup failure. Actual PostgreSQL tests cover terminal
identity selection before the claim limit, unchanged claims/transactions and run
preparation. The real cross-service test additionally checks a symbols fallback
warning through Kafka and PostgreSQL. Its bounded indicator target now uses explicit
internal preparation; standalone metadata still goes through scheduler scan.
Full-catalog terminal selection is separately exercised in PostgreSQL without
claiming that every terminal analytics workload has run across all services.
Real broker outage tests verify 503 during recovery, an uncommitted failed command
and automatic same-group replay/commit after broker restoration, with no explicit
replacement worker. Original crash-window cases remain part of the four-case suite.

Executed local gate: **424 passing tests** (162 shared, 53 Ingestor, 149 Analyzer,
56 Platform and four real Kafka cases). The four opt-in broker tests are skipped
only in the normal shared unit target and executed separately with no skips.
`P14-default-readiness` records 11 required checks; project lint, scoped formatting,
coverage/evidence and documentation audit are included. Root `main.py` files are
outside project lint targets and were checked directly with Ruff as an explicit
target-scope exception. Known cached-context PostgreSQL shutdown warnings remain;
they did not cause test failures and are not claimed fixed here.

| Changed production boundary              | Executed source/test mapping                                                                       | Attributable line / branch coverage                                                                                                                |
| ---------------------------------------- | -------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Ingestor `process_sync_symbols_message`  | `test_symbols_status.py`; real cross-service fallback warning persisted by Platform                | 100% / 93.75% for the changed function; unchanged helper coverage is not inferred.                                                                 |
| Ingestor routing lifecycle               | `test_kafka_consumer.py`, including cancellation during initial connection and provider cleanup    | 96.3% / 100% for `messaging/consumer.py`.                                                                                                          |
| Shared supervisor and Kafka lifecycle    | `tests/runtime/test_runtime.py`, `tests/kafka/test_job_status_service.py`, real outage/replay test | Supervisor 100% / 100%; Kafka service 100% / 91.67%.                                                                                               |
| Shared HTTP/CLI entry functions          | Runtime health/recovery/cancellation tests                                                         | Changed health and CLI functions 100% line/applicable branch; untouched logging is outside this claim.                                             |
| Service health wiring                    | Ingestor and Analyzer `test_worker_health.py`                                                      | Executed focused callback/all-enabled-consumer tests; app-only instrumentation excludes root `main.py`, so no numeric wrapper coverage is claimed. |
| Platform terminal identity/claim service | `StaticDefinitionDependenciesTest`, `SchedulerEntryClaimTest`, `JobRunPreparationPostgresTest`     | Topology declaration and claim service 100% / 100%.                                                                                                |
| Platform filtered atomic claim query     | Claim repository PostgreSQL race/lease tests plus full-catalog terminal selection                  | Changed `claimDueJobs` overloads 100% / 90%; unchanged repository methods are outside this claim.                                                  |

The final Ingestor startup-cancellation cleanup refinement was verified by the
latest focused unit suite; the full chain ran before that cleanup-only refinement.
The source-bound local evidence artifact records the report hashes, matrix and
instrumentation/graph/runtime limits instead of claiming a cross-service percentage.

These three local prerequisites are addressed. **Default activation remains NOT
READY** pending overlapping/shared-writer and remaining full-catalog integration
evidence, retained V15/backup/quiescence review and actual rollout authorization.
No flag/default, migration, retained offset, live provider or roadmap status changes.
Choosing terminal entries does not coalesce ancestors shared by distinct runs.

Blast radius: Platform claim service/repository/topology and tests own entry
selection; Ingestor owns symbols status and routing readiness; Analyzer owns health
of enabled workers. py-common owns shared supervision and the Kafka lifecycle used
by all Analyzer subclasses. Query Service is an unchanged READY/lineage reader,
exercised in the bounded cross-service test; Console remains a status/metadata
reader and gains no API or persisted enum. JSON diagnostic metadata is additive;
generated contracts/topics, dataset writers/paths/owners, READY-last and migration
semantics remain unchanged. Replays remain at-least-once, with duplicates possible.

Documentation synchronization includes Plan 030, release notes, job flow, ADR-007,
system overview, Kafka docs, service/library guides and TD-001/TD-009/index. Data-lake
and database docs need no storage/schema update; deployment manifests/defaults,
registry/status/dependencies, other ADRs, indexes and agent guidance need no new
configuration, schedule or authority. Graph impact/change tools are unavailable;
source plus canonical-document reconciliation is the explicit analysis limitation.

## Temporary metadata warning policy — 2026-10-26

Owner approved tolerating returned metadata partial results without failing the
execution. Analyzer now emits SUCCESS, logs the count-bearing warning with execution
identity, and retains `metaJson.result=PARTIAL_SUCCESS`, warning text and counters.
Platform persists these diagnostics with its existing SUCCESS handler. Healthy
results have no warning fields. This supersedes partial-to-ERROR above without
adding an unsupported top-level status or bypassing dataset readiness.

The policy applies only to returned partial results. Invalid Parquet, empty rebuild,
storage and publication exceptions remain ERROR/FAILED; previous discovery is
preserved on validation failure. Dataset EXISTS/READY can still produce WAITING.
Historical ignored executions and Ingestor's separate PARTIAL_SUCCESS are unchanged.
The `P14-metadata-warning` gate reruns Analyzer tests/coverage, the Java shared-wire
regression and the real cross-service test extended with an auxiliary-object partial
scan that completes SUCCESS while preserving warning/counts in PostgreSQL.

Impact: Analyzer status behavior and the existing extensible metadata fields change;
Platform needs no production code change and gains warning-persistence regression
coverage. Shared DTO/proto, Ingestor, Query Service, Console, storage ownership,
migrations and runtime flags are unchanged. Kafka docs, Analyzer guide, TD-001/index,
Plan 030 and release notes are synchronized. Architecture/ADR/flow/data-lake/database,
deployment configuration, registry/dependencies, other service guides, indexes and
agent instructions need no schema, ownership or authority update. Graph tools remain
unavailable; source/canonical-document reconciliation is the analysis limitation.

## Disposable cross-service integration — 2026-10-26

This records the initial `P14-cross-service-integration` harness and evidence.
The [worker/entry follow-up](#worker-recovery-and-terminal-entries--2026-10-26)
supersedes its manual-restart/health defect and scheduled-indicator assumptions.

The opt-in targets now execute real infrastructure and production components:

```bash
nx run platform:test-integration
nx run py-common:test-integration
```

Platform creates disposable PostgreSQL 16, Kafka 7.6.0 and MinIO containers,
runs Flyway/seeding, and starts actual Kafka listeners and Python workers. Only
the external market provider is replaced with deterministic data: one HOSE/HPG
symbol and 180 daily bars. Sector filters are cleared and symbol classification
is disabled in the test database; production definitions and flags are unchanged.
The test explicitly invokes the actual scheduler scan/dispatcher rather than
enabling background schedules. Separate metadata entries publish discovery between
the stock and indicator phases, as required by the existing dataset guard.

Prerequisites: working Docker, JDK 21, the declared container images, and the root
Python environment synchronized for Ingestor, Analyzer and Query Service. The
platform target starts subprocesses through `.venv/Scripts/python.exe` on Windows
or `.venv/bin/python` elsewhere. Flat connection environment variables override
shared YAML before settings import; fixtures verify disposable endpoints/bucket
before creating clients. Logs and temporary connection settings remain in an OS
temporary directory. Never copy those settings into committed evidence.

Executed behavioral matrix (`P14-cross-service-integration`, local evidence):

| Scenario                           | Production components exercised                                                                                       | Executed assertion                                                                                                                                              |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Scheduled target and closed run    | `JobScheduler`, preparation, outbox coordinator/store/dispatcher, PostgreSQL                                          | A due indicator target creates a run with its ancestor steps; actual worker statuses complete all steps.                                                        |
| Dataset WAITING                    | DependencyRegistry/guard, metadata reader, retry scheduling                                                           | Stock execution SUCCESS does not publish indicator work before discovery exists; normal cache/retry expiry subsequently permits it.                             |
| Python work and status persistence | Ingestor routing/handlers, Analyzer indicator/metadata workers, shared Kafka loop, Platform listeners/`applyStatus()` | Actual commands and terminal messages cross Kafka; children and parents finish in PostgreSQL.                                                                   |
| READY and exact lineage            | MinIO Parquet/global metadata, Query Service resolver/read-only SQL/DuckDB executor                                   | Query Service reads 180 indicator rows over S3; manifest inputs, Parquet input version and approved execution metadata agree with the EOD version.              |
| Metadata scan failure              | Real metadata synchronizer, status publisher and Platform persistence                                                 | Corrupt Parquet produces ERROR/FAILED; the previous discovery document remains byte-for-byte identical.                                                         |
| Outage and explicit recovery       | Actual `JobStatusKafkaService`, Kafka factory/publisher/commit loop                                                   | Pausing the owned broker causes a terminal-status timeout, leaving the command offset uncommitted; unpause plus explicit worker restart replays and commits it. |
| Crash windows                      | Shared Kafka integration subprocesses                                                                                 | Crashes before status and after status acknowledgement/before commit replay; committed output/status does not replay.                                           |
| Failed-worker health observation   | Shared ASGI worker wrapper                                                                                            | The consumer task fails while `/health` still returns HTTP 200. The test records this defect; passing the probe does not establish readiness.                   |

The focused Platform scenario and four Kafka cases pass with no failures/skips.
Python fixture lint is checked in all four affected projects. This is executed
cross-process behavioral coverage, without a numeric cross-service coverage claim;
JaCoCo cannot measure the Python subprocesses. It supplements the earlier unit and
PostgreSQL matrices rather than replacing their attributable coverage gates.

**Default activation remains NOT READY.** The subsequent worker/entry follow-up
fixes automatic recovery, truthful lifecycle health, symbol warning completion and
entry selection. Overlapping scopes/writers, provider behavior, rebalance recovery,
poison disposition and full-catalog workload integration still exceed this scenario.
No retained V15 execution, offset reset, deployment, CI or flag removal is claimed.

Impact reconciliation: Platform gains an opt-in Nx target/test; Ingestor, Analyzer
and Query Service gain test-only fixture entrypoints that call their existing
production paths. py-common gains an outage/recovery test and broker-fixture
ownership tracking. Console is not started: its presentation is outside this
scheduler/storage contract. Shared messages, generated code, production persistence,
storage ownership, paths and deployment settings are unchanged. Test containers
exercise migrations without authorizing retained migration execution.

Documentation synchronization: this runbook, Plan 030, release notes, Platform and
py-common test guides, TD-001 and TD-009 record the new evidence. Architecture, ADR, flows,
Kafka/data-lake/database and other service guides need no contract change because
only test wiring changes. Roadmap registry/dependencies, indexes, deployment
defaults and repository guidance retain their current authority; no new increment,
document or production configuration is introduced. Graph impact/change tools are
unavailable; source and canonical-document reconciliation are the stated limitation.

## Upgrade checks

1. Record the current Platform artifact/configuration and normal queue/worker
   state. Follow the existing database backup and deployment process.
2. Upgrade Platform with planning enabled and preparation still disabled. Existing
   unlinked rows use the legacy lane with identity revalidation; frozen JobRun
   rows require closed membership and all predecessor executions SUCCESS.
3. Observe bounded `PLANNED`, `FALLBACK` or `FAILED` selection modes. A fallback
   is paginated FIFO, but every linked message still undergoes admission and
   DependencyRegistry checks. Database failure dispatches nothing.
4. Confirm WAITING does not consume attempts, BLOCKED remains terminal, and
   retries preserve identity. Before each broker send, the exact live token/owner
   is renewed; superseded/expired claims are not sent. Acknowledgement/retry
   updates remain fenced. Delivery is at-least-once, not exactly-once.
5. Treat PUBLISHED as broker acknowledgement, not worker completion or dataset
   READY. No worker start/completion timestamps or successful parents are inferred
   from planning. Only actual worker evidence closes execution barriers; the
   existing registry separately approves READY and exact data versions.

Advisory run/FIFO cursors are process-local and may reset on restart. Membership,
status and claims remain PostgreSQL-authoritative. Poison payloads are skipped
with bounded diagnostics, not rewritten or treated as successful; repair/replay
and generalized DLQ tooling remain separate work.

## Read-only diagnostics

Use the [v1 SQL/operator pack](../../database/diagnostics/p14/v1/README.md) with an
explicit read-only transaction, bounded run IDs/pages and timeouts. The
[I5 measurement report](../../database/diagnostics/p14/v1/benchmark-results.md) is
synthetic local evidence, not a production latency budget or actual worker rate.
Never run the test benchmark against retained data; its claim EXPLAIN/rollback and
fake status transitions are intentionally confined to disposable PostgreSQL.

The additive [I5 operator read contract](../../database/diagnostics/p14/v1/read-model.md)
uses the same SELECT-only invocation. Begin a fresh transaction per observation;
long-lived repeatable-read snapshots cannot establish current freshness. Consumers
must enforce their declared age/clock policy and retain unknown/unavailable states.
The contract introduces no endpoint, polling service, deployment flag or migration.

## Explicit rollback

### V15 legacy outbox identity cutover (P14-I7)

The new Platform artifact requires V15 before its strict candidate reader runs.
Flyway applies it at startup only after the operator completes these prerequisites.
Local test success does not authorize applying it to a retained database.

1. Inventory every page of the [legacy outbox query](../../database/diagnostics/p14/v1/legacy_outbox_inventory.sql)
   across PENDING/PUBLISHED/BLOCKED and active/expired leases. Reconcile the whole
   population under a stable writer boundary, not merely one zero-alias page.
   Inventory CURRENT_IDENTITY proves the FK matches the payload ID only; V15 also
   validates parent/definition identity. Resolve malformed, missing, ambiguous or
   conflicting identities through a separately reviewed data repair before cutover.
2. Drain claims and stop affected Platform instances, schedulers and status writers.
   Confirm no incompatible old binary or external execution/outbox writer remains.
   Kafka status delivery may wait durably while Platform is stopped; do not reset
   offsets or replay published work. Validate a database backup and restore procedure
   covering schema history, outbox, executions and frozen JobRun membership.
3. Apply the reviewed artifact/migration only with retained-environment authorization.
   V15 takes execution/outbox table locks with a five-second lock timeout. Locks are
   a second line of defense, not a replacement for writer quiescence. The migration
   validates all rows and projected `(execution_id,message_index)` keys before
   changing only alias execution_id values. It preserves payload/key/topic, IDs,
   status, attempts, availability, leases/tokens/owners, audit timestamps, metadata
   and all execution/JobRun relationships. Existing membership guards stay enabled.
4. On error, stop deployment. PostgreSQL/Flyway roll back the V15 transaction,
   including its new guard. Errors name the first affected message/reason or
   projected collision key without logging raw payloads. Do not delete/quarantine
   rows, disable guards, run Flyway repair, rewrite V1–V14 or reset delivery state
   to force progress. Fix the stated precondition under a reviewed recovery action
   and retry the same forward migration after quiescence.
5. Before resuming, reconcile every message against its payload execution ID and
   verify zero remaining aliases, unchanged row/ID counts and preserved delivery/
   lease/history fields against the backup/inventory. The new trigger rejects
   INSERT or payload/FK updates with missing, poison or mismatching execution IDs.
   Ordinary lease/retry/ack/status updates still work. Verify canonical NULL-JobRun
   and JobRun admission, exact approved-input metadata on the work child, fenced
   retry/ack and child status/parent aggregation; do not infer dataset READY.
6. Resume only the reviewed compatible writers. Binary rollback must keep canonical
   child FKs and the V15 guard; validate the selected artifact's reader/writer contract
   first. A full pre-V15 restore requires separate approval, stopped writers and a
   verified compatible artifact. Never restore parent aliases into a running V15
   deployment, fabricate SUCCESS, reset attempts or republish historical messages.

Physical history purging, retained migration execution and preparation activation
remain separate owner-authorized operations. This procedure adds no new runtime flag.

Keep preparation disabled first. Prefer retaining guarded planning while work
drains; a planner/snapshot exception already has a barrier-preserving fallback.
Do not disable planning automatically in response to those exceptions.

Setting `APP_SCHEDULER_JOB_RUN_PLANNING_ENABLED=false` is a conscious rollback to
historical guarded FIFO. It removes the JobRun completion barrier; review/quiesce
queued JobRun work and its upstream state before accepting that behavior. A binary
rollback to an older dispatcher has the same concern. Do not delete run/outbox/
execution state, fabricate SUCCESS or edit applied Flyway migrations to roll back.

The retired manual-trigger API remains authenticated HTTP 410 under TD-017.
Disabling the planner does not restore manual triggering or dropped audit data.
Future symbol producers, shared-work coalescing and date-range backfill require
their own supported-scope/rollout decision. I5's local diagnostic/benchmark
artifacts and consolidated I5 read contract are available above; presentation remains
Plan 029 work. They do not authorize retained-data migration or activation.

### V15 legacy outbox identity cutover (P14-I7)

The new Platform artifact requires V15 before its strict candidate reader runs.
Flyway applies it at startup only after the operator completes these prerequisites.
Local test success does not authorize applying it to a retained database.

1. Inventory every page of the [legacy outbox query](../../database/diagnostics/p14/v1/legacy_outbox_inventory.sql)
   across PENDING/PUBLISHED/BLOCKED and active/expired leases. Reconcile the whole
   population under a stable writer boundary, not merely one zero-alias page.
   Inventory CURRENT_IDENTITY proves the FK matches the payload ID only; V15 also
   validates parent/definition identity. Resolve malformed, missing, ambiguous or
   conflicting identities through a separately reviewed data repair before cutover.
2. Drain claims and stop affected Platform instances, schedulers and status writers.
   Confirm no incompatible old binary or external execution/outbox writer remains.
   Kafka status delivery may wait durably while Platform is stopped; do not reset
   offsets or replay published work. Validate a database backup and restore procedure
   covering schema history, outbox, executions and frozen JobRun membership.
3. Apply the reviewed artifact/migration only with retained-environment authorization.
   V15 takes execution/outbox table locks with a five-second lock timeout. Locks are
   a second line of defense, not a replacement for writer quiescence. The migration
   validates all rows and projected `(execution_id,message_index)` keys before
   changing only alias execution_id values. It preserves payload/key/topic, IDs,
   status, attempts, availability, leases/tokens/owners, audit timestamps, metadata
   and all execution/JobRun relationships. Existing membership guards stay enabled.
4. On error, stop deployment. PostgreSQL/Flyway roll back the V15 transaction,
   including its new guard. Errors name the first affected message/reason or
   projected collision key without logging raw payloads. Do not delete/quarantine
   rows, disable guards, run Flyway repair, rewrite V1–V14 or reset delivery state
   to force progress. Fix the stated precondition under a reviewed recovery action
   and retry the same forward migration after quiescence.
5. Before resuming, reconcile every message against its payload execution ID and
   verify zero remaining aliases, unchanged row/ID counts and preserved delivery/
   lease/history fields against the backup/inventory. The new trigger rejects
   INSERT or payload/FK updates with missing, poison or mismatching execution IDs.
   Ordinary lease/retry/ack/status updates still work. Verify canonical NULL-JobRun
   and JobRun admission, exact approved-input metadata on the work child, fenced
   retry/ack and child status/parent aggregation; do not infer dataset READY.
6. Resume only the reviewed compatible writers. Binary rollback must keep canonical
   child FKs and the V15 guard; validate the selected artifact's reader/writer contract
   first. A full pre-V15 restore requires separate approval, stopped writers and a
   verified compatible artifact. Never restore parent aliases into a running V15
   deployment, fabricate SUCCESS, reset attempts or republish historical messages.

Physical history purging, retained migration execution and preparation activation
remain separate owner-authorized operations. This procedure adds no new runtime flag.
