# I10 PostgreSQL-only readiness and JPA schema

## Owner scope amendment - November 18 session-based operation

Omni currently runs by session, not continuously. The owner defers fleet-wide
rollout/recovery qualification and activation of the 12-hour reconciliation
schedule to [TD-020](../technical-debt/020-session-runtime-operations.md).
Those two operational slices no longer block current session-based I10 acceptance;
they are deferred, not passed. Earlier dated claims that both remain completion
blockers are superseded by this amendment.

Per-backfill affected-scope proof/byte validation, pinning where needed, matched
reader/writer configuration, ownership, READY-last and bounded recovery remain
required. The [56-partition checklist](#remaining-56-partitions--bounded-work-checklist)
describes the remaining physical work; it need not precede already verified MBB
backfill. No schedule, startup catch-up, broader pin or runtime change is made.
I10 remains in_progress; this amendment does not certify the remaining inventory
or satisfy source/coverage, local acceptance or explicit owner-review requirements.

## Owner decision - November 16

The owner requested the missing JPA schema and retired the legacy JSON manifest
as an active readiness authority. PostgreSQL is the default for Platform admission
and Python workers/Query. Legacy manifest/shadow comparison is no longer the
acceptance reference for this cutover. Replace it with attributable PostgreSQL
work-proof, exact-version/byte and no-fallback evidence; do not label the earlier
failed comparison a pass.

This does not waive frozen inputs, exact dates, row ownership, READY-last,
transactional completion, immutable physical versions, rollout/recovery or owner
review. Missing/unregistered data remains unavailable or waits. Retiring discovery
JSON does not enroll historical files or manufacture READY for their other rows.
I10 remains in_progress.

Latest retained checkpoint: [November 18 execution and reader/repair evidence](#november-18-retained-signals-and-readerrepair-checkpoint).
Earlier dated no-dispatch and pending-approval statements describe their original
checkpoints; the additional HOSE metadata prerequisite was subsequently approved.

## Source and configuration

- V27 creates event_publication and event_publication_archive for the existing
  Spring Modulith 2.0.1 JPA entities, including status, retry fields, timestamps and
  TEXT serialized payloads. V1-V26 are unchanged. Hibernate must not create/update
  these tables outside Flyway. The startup regression now uses ddl-auto=validate.
- Platform defaults APP_OUTPUT_REGISTRATION_READER_ENABLED to true and rejects
  false. Python settings and the reader factory accept only OUTPUT_READINESS_MODE
  postgres; manifest/shadow overrides fail startup. This applies to all runtime
  services, per the owner's explicit confirmation, without JSON fallback.
  Low-level legacy classes remain only for historical direct fixtures; active
  factories cannot select them. Analyzer's local manifest override was changed
  to postgres without changing its other settings or credentials.
- Python services require OUTPUT_READINESS_API_URL and a private reader token.
  Missing credentials fail reader construction; API errors fail closed. The
  reconcile token has separate write-route scope. Examples name these variables
  without supplying credentials. Configure them per process/container; changing
  examples or defaults does not restart a deployed service.
- Analyzer startup always wires SYNC_METADATA to OutputReconciler to validate committed
  registered outputs. It does not republish the retired JSON document. The
  12-hour schedule remains separately gated; unknown historical data must obtain
  legitimate producer/registration evidence rather than inferred coverage.
- No Kafka/protobuf schema, logical dataset/path or writer ownership changes.
  November 17 adds an optional symbolKey to the existing operator backfill JSON;
  Query APIs and existing Console payloads remain compatible. Console discovery reflects registered PostgreSQL
  evidence through Query; empty inventory is not proof that physical files vanished.
- Signals intraday confirmation has no supported PostgreSQL registration contract.
  Active startup uses PostgresIntradayDatasetResolver and reports
  UNAVAILABLE/MISSING_POSTGRES_INTRADAY_PROOF without reading JSON READY pointers.
  The old resolver and its P9 fixtures remain historical direct tests only. No
  intraday proof, reconciliation status or coverage is inferred from their files.

## Executed retained change

The controlled full-Platform host retained normal JPA/Modulith/runtime dependencies
and used strict schema validation. Schedulers, Kafka listeners, seeders,
notifications and business dispatch were disabled. Flyway validated existing
history and applied only V27. Both active/archive JPA entities validated; the
actual Platform ManifestReader resolved EOD HOSE/MBB through PostgreSQL.

A new 91,961,754-byte PostgreSQL backup contains the three previously pinned
baselines. The scoped probe covers EOD HOSE/MBB, Indicators AD_CLOSE/1D/HOSE/MBB
and Signals ICHIMOKU_V1/1D/HOSE. It performed these actual retained checks:

| Criterion                                  | Observed evidence                                                                                                                       |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| PostgreSQL catalog before/after retirement | All three pinned partitions readable with identical logical versions                                                                    |
| Exact immutable Parquet bytes              | Existing scoped validators pass before and after removal of JSON discovery                                                              |
| Actual Query resolver/DuckDB               | 27 EOD, 27 Indicators and 216 Signals registered rows read and counted                                                                  |
| Frozen worker-input route                  | Actual retained execution metadata resolves one Indicators upstream partition and two Signals upstream partitions, with byte validation |
| No JSON fallback                           | Guarded adapter records zero legacy JSON reads; API shutdown raises ManifestInvalidError                                                |
| Preserved logical state                    | Contracts 2,598, work proofs 1,598, heads three, revision 2188, READY history zero and unfinished executions zero                       |

Only the exact key \_metadata/metadata.json was retired. Its bytes were backed up
and hashed first. With bucket versioning Enabled, the operation added a delete
marker; the old metadata versions remain recoverable. No Parquet object, immutable
data version, per-output snapshot, registration or READY proof was deleted. Do not
turn this into a recursive metadata/object cleanup. Legacy object visibility is
removed, not permanent physical version garbage collection.

The host was stopped after the probe. No retained Signals request, provider call,
watchdog activation, global worker rollout or retained rollback was executed.
A fresh read-only inventory finds 59 PostgreSQL READY partitions: 28 EOD,
28 Indicators and three Signals. Only one partition in each dataset is physically
pinned (three total); the other 56 are outside this approved pilot. Full affected-
inventory immutable pinning and coverage are not implied by this three-partition result. Physical versioning/promotion/enrollment flags were not
globally enabled by changing reader defaults.

## Verification and source/test attribution

Recorder: P14-I10-pg-only-20261116, final conclusion PASS 9/9. This bounded
follow-up is not the complete I10 operational/owner gate. The focused JPA/PG
configuration suite also passes six tests. Artifacts and helper scripts are ignored local files in
.agent/operations/i10-pg-only-20261116 and .agent/reports/i10-pg-only-20261116.
No credential values are persisted in reports; unused development-password log
entries are redacted. Stored work/version identifiers are operational evidence,
not owner attestation.

Executed suite results: Platform coverage 917 tests (904 passed, 13 skipped),
py-common 558 (553 passed, five skipped), Analyzer 273 passed, Query 59 passed and
Ingestor 118 passed. Platform build also passes. The retained strict-source replay
at V27 passes without applying a migration or deleting another object; it verifies
the existing delete marker and repeats Query/frozen-input/API-outage checks.

| Changed behavior/source              | Executed attribution                                                                | Lines / branches                            |
| ------------------------------------ | ----------------------------------------------------------------------------------- | ------------------------------------------- |
| SchedulerConfig and PG adapter       | SchedulerConfigPostgresTest, PlatformStartupPostgresTest                            | config 14/14, 2/2; adapter 3/3, no branches |
| OutputCompletionDiagnostics          | full Platform coverage suite                                                        | 27/27, 16/16                                |
| OutputReconciliationPolicy           | full Platform coverage suite                                                        | 8/8, 8/8                                    |
| BaseAppSettings.load_shared_config   | py-common settings tests, including retired modes and concurrent backfill rejection | 13/13, 4/4                                  |
| metadata_reader factory              | py-common authority-selection and no-fallback tests                                 | 4/4, 2/2                                    |
| Analyzer startup_event               | test_worker_health, enabled/disabled consumer lifecycle                             | 64/64, 18/18                                |
| PostgresIntradayDatasetResolver      | active startup resolver call, unavailable reason and zero JSON reads                | 3/3, no branches                            |
| V27 SQL and active/archive JPA model | fresh startup, upgrade/restart and real persistence/rollback tests                  | SQL not numerically instrumented            |

Python coverage artifacts and full JaCoCo/JUnit snapshots are retained alongside
the recorder. No aggregate percentage substitutes for these changed-symbol counts.
Graph change detection is bounded (84 files, 44 flows, 47 candidate test gaps);
dynamic Python/Spring edges remain incomplete. Candidate graph gaps are reconciled
against the actual test/source matrix rather than treated as proof of no coverage.
The overlapping-Gradle failure and the probe's MinIO string-boolean assertion were
discarded as acceptance evidence; the sequential Java and corrected read-only
probe reruns are the evidence above.

- PlatformStartupPostgresTest executes fresh Flyway startup/restart with strict
  JPA validation, active/archive entity persistence, a >255-character JSON payload,
  timestamp/Class/enum mappings and rollback of a flushed event. It also exercises
  default PostgreSQL admission with a missing registration and no object-store
  fallback. A test-scoped dependency exposes the existing runtime JPA model.
- SchedulerOutboxIdentityMigrationPostgresTest retains upgrade, preservation,
  restart, retry/ACK and guard checks while expecting latest schema V27. The first
  full run's five failures were stale V26 assertions; no safety predicate was removed.
- SchedulerConfigPostgresTest covers rejection of the legacy override, missing
  PostgreSQL proof, exact-date delegation and database failure without MinIO reads.
- py-common config tests assert the PostgreSQL default and reject manifest/shadow.
  Factory tests reject retired modes even without constructing validated settings.
  Four reader cases combine
  missing/corrupt JSON with API success/failure and assert zero legacy reads.
- Analyzer, Ingestor and Query suites exercise the shared-default blast radius.
  Console behavior/protobuf contracts are unchanged; their earlier evidence is
  historical, not a newly executed suite for this follow-up.
- Old opt-in cross-service helpers that explicitly select manifest/shadow are
  historical fixtures and must be migrated before reuse. Their previous passing
  evidence does not certify this PostgreSQL-only cutover; the retained probe
  exercises the current PostgreSQL reader, Query and frozen input routes directly.

## Blast radius and documentation disposition

Platform changes the default admission reader and the default authority reported
by completion/reconciliation diagnostics; new persistence belongs to its existing
Modulith event registry. Analyzer/Ingestor/Query consume the shared Python default,
using existing private API and Parquet adapters. Console consumes existing Query
and Platform read contracts. Shared protobuf/Kafka messages are unchanged; no
generated output, new dataset owner, physical routing path or worker concurrency
is introduced. Historical CANONICAL contracts are not rewritten as VERSIONED.

Plan 030, registry/roadmap, ADR-008, architecture, data/Kafka/database, job flow,
deployment, service/library guides, docs indexes, ownership and AGENTS link this
decision. CLAUDE/editor guidance already delegates these policies to AGENTS and
needs no duplicate rule. Older dated manifest-default/opt-in statements are
historical and are superseded for active reader selection by this amendment.

## November 17 PostgreSQL-only E2E and retained scope

P14-I10-pg-e2e-20261117 concludes PASS 2/2 (changed-file formatting and one
multi-stage I10 integration scenario). It executes the actual Platform private API,
Kafka completion/worker path, disposable PostgreSQL/MinIO and Query helpers; only
external market data is a fixture. The scenario now starts Platform and enrolled
workers in postgres mode rather than shadow. The metadata worker uses
OutputReconciler and does not seed global JSON.

| Source/test path                 | Executed evidence                                                                                                                                                                          |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| I10WorkReadinessIntegrationTest  | Committed registration before downstream dispatch, provider-failure closure/retry, authenticated/idempotent backfill, versioned Signals and drained repair all pass                        |
| Analyzer p14_worker.py           | Enrolled inputs and metadata reconciliation use the PostgreSQL factory; current intraday no-JSON resolver is wired                                                                         |
| Analyzer p14_storage_baseline.py | Two pinned datasets retain exact logical versions/bytes; two registered proof rows validated independently of 181 preserved container rows; zero legacy reads and global JSON absent       |
| Query p14_canonical_repair.py    | Three accepted partitions restored after a deliberately stale canonical latest; three proofs remain readable, global JSON stays absent and registration/READY/head snapshots are unchanged |

The earlier row-count fixture failures are archived separately. Whole-container
row counts are not scoped READY evidence; the corrected fixture selects registered
row coordinates using the same projection as Query and also compares the full
container before/after pinning. Existing safety assertions were retained. Helpers
that still select retired modes are not certified by this focused I10 run.

Fresh retained read-only observations on November 17 find V27, three heads and
revision 2188. DB at 22:11:10 and Kafka at 22:11:14 Asia/Ho_Chi_Minh report zero
unfinished executions, zero known lag, zero unknown-lag partitions and no active
consumer partitions. Historical BLOCKED outbox records remain; no cleanup occurred.
These observations do not prove READY or provider health.

The owner approved the optional symbolKey extension to retain the MBB-only pilot.
IndicatorBackfillController normalizes the optional logical symbol and freezes the
existing SYMBOL scope. GraphWorkRequestService validates active sector membership
under its existing symbol lock, then reconciles complete target membership against
that narrowed universe. Absent symbolKey retains full sector behavior. Request
idempotency includes the normalized scope; analytical parameters, enrollment,
frozen input proofs, locks and dispatch/READY guards are unchanged.

P14-I10-symbol-scope-20261117 passes 4/4: formatting, full Platform coverage,
narrowed actual HTTP/Kafka Signals/repair E2E and build. Platform has 929 tests
(916 passed, 13 skipped). IndicatorBackfillController covers 89/89 lines and 52/52
branches; GraphWorkRequestService covers 433/439 lines and 199/220 branches.
Controller tests cover normalization and malformed symbols; PostgreSQL tests cover
single-symbol and sector-wide compatibility, inactive/outside/missing symbols,
complete target membership, no durable work on rejection and scope-bound replay/
conflict. E2E adds another active symbol in the same sector and proves it receives
no request work while the selected symbol completes versioned Signals and repair.

The retained read-only graph preview selects only HOSE-MBB data work and exactly
the three pinned analytical outputs. Using sectorLevel=2 preserves all three
retained atomic work identities; level 1 would change Signals identity. However,
the graph also requires a current SYNC_SYMBOLS HOSE prerequisite. That handler
writes symbols/hose.parquet and upserts exchange-wide metadata, outside the three
approved outputs. Its existing 79,130-byte object was read/backed up without
mutation; Symbols has no enrolled PostgreSQL READY proof or physical head.
No retained request, provider refresh, extra output write or metadata upsert was
submitted. The additional prerequisite scope requires owner approval; no dependency
was removed, current proof fabricated or JSON fallback introduced to avoid it.

This E2E is disposable evidence, not retained Signals, retained rollback, deployed
12-hour reconciliation or full-inventory acceptance. The earlier 9/9 source gate
and its coverage matrix remain separate from this focused 2/2 continuation.

## November 18 retained Signals and reader/repair checkpoint

The owner approved the additional exchange-wide SYNC_SYMBOLS HOSE prerequisite.
The finite November 17 run used the normal Platform dispatcher, Ingestor,
Analyzer and completion listeners. Request `e5514975-1102-4501-9fb0-bce9dcf8f386`
and root execution `262a5f11-2c1d-48bf-91fb-5a0e29ea7406` reached SUCCESS with
four terminal members. The analytical scope remained HOSE-MBB, BANKS level 2,
2026-10-16, force=false/rerun=true. No second business request was submitted
when verification resumed after interruption.

SYNC_SYMBOLS wrote 309 unique HOSE symbols and all 309 are active in Platform;
no snapshot code is missing and MBB remains active. The provider expected-count
warning (309 versus the prior 336) remains unresolved; this proves application
of the returned snapshot, not completeness of the exchange universe. The normal
pipeline retained 89 dated/delisted rows to avoid false deactivation and handled
117 sector records. Symbols still has no enrolled analytical READY proof/head.
Stock prices reused existing EOD (zero records refreshed); Indicators and Signals
each completed one selected work. No other analytical symbol was selected.

The November 18 retained verification exercised the actual PostgreSQL API,
Query/DuckDB and frozen worker-input readers, with zero discovery JSON reads:

| Criterion                           | Executed evidence                                                                                                                |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Exact registered rows and ownership | EOD 27/3,448, Indicators 27/3,448 and Signals 216/3,242 registered/container rows; all unowned rows preserved                    |
| Frozen upstreams                    | One Indicators and two Signals inputs resolve accepted immutable bytes                                                           |
| Quiesced reconciliation             | Three scoped objects unchanged; full read-only inventory 59/59 unchanged, zero failures, no publication                          |
| Canonical repair rehearsal          | Each of three latest objects already matched accepted bytes; appending those same bytes preserves accepted and baseline versions |
| Database preservation               | All 37 public-table row digests unchanged across repair; no head, registration, history or logical version rewrite               |
| No fallback                         | Global discovery JSON absent; API outage raises ManifestInvalidError; reader host stopped                                        |

Retained counters are V27, 2,601 completion contracts, 1,598 current work proofs,
three physical heads, three READY history rows and revision 2,191, with zero
unfinished executions. The November 17 business run changed history/revision;
the November 18 repair changed neither. Backups and failed/interrupted journals
remain available. Verification-01 was interrupted after Query checks and has no
complete repair result; verification-02 is the successful separate journal.

Recorder P14-I10-retained-signals-20261117 concludes PASS 3/3: actual retained
Signals, readers/repair and post-run ownership. Fresh final audit observes zero
unfinished DB work, zero known/unknown Kafka lag and zero active consumer
partitions. No matching maintenance process remains; ports 18080-18083 are closed.
Historical BLOCKED outbox entries are preserved. This gate is separate from the
9/9 source, 2/2 disposable E2E and 4/4 symbol-scope gates.

Evidence: ignored `.agent/reports/i10-pg-only-20261117/approved-run-04/result.json`
and `verification-02/result.json`; finite helpers and exact-object backups reside
under `.agent/operations/i10-retained-signals-20261117/`. Their direct Python
invocation is a bounded operations exception because no matching Nx target exists;
the actual Java hosts use platform:serve and backlog uses omni:jobs-backlog.

This closes bounded retained Signals and accepted-byte repair evidence only.
Read-only parity of 59 partitions does not establish immutable write safety for
the other 56 unpinned partitions. The reader probe deliberately leaves strict
versioned-storage enforcement off for that full catalog read; the finite writer
host used it on for the approved pilot. Global writer/worker rollout, safe
12-hour scheduled reconciliation, full affected-inventory acceptance and explicit
owner review remain open. Schedules, watchdog and notifications stayed off;
durable worker recovery, tracing and P12 remain deferred. I10 stays in_progress.

## Remaining 56 partitions - bounded work checklist

The November 18 snapshot has 28 EOD, 28 Indicators and three Signals catalog
partitions. One of each is pinned, leaving 27 EOD, 27 Indicators and two Signals.
These are physical containers, not 56 jobs or 56 business dates. The saved 59/59
read-only comparison passed; this is not a fresh inventory or immutable write-safety
qualification. No additional pin operation is authorized or executed by this list.

Work can proceed in small approved symbol/strategy batches as backfill needs them;
pinning all 56 is not a prerequisite for the already verified MBB scope.

1. **Freeze an exact batch inventory.** Enumerate dataset/partition keys, current
   registered rows/versions, upstream dependencies, existing heads, backend versions
   and all possible writers. Identify which files the requested backfill actually
   reads or merges. Do not infer symbol membership or dates from file counts.
2. **Prepare a drained maintenance window.** Check unfinished executions, pending
   mutations, writer ownership and committed Kafka backlog separately. Stop/drain
   affected writers; lag zero alone proves neither ownership nor safe quiescence.
   Back up PostgreSQL plus exact canonical objects and their version inventory;
   validate the database archive and preserve prior physical versions.
3. **Validate current accepted evidence.** Check PostgreSQL proof identity, scope,
   exact dataVersion, row content, schema/date coverage and lineage against the
   canonical bytes. Keep unregistered history unproven. Missing/conflicting proof,
   unavailable bytes or uncertain writers stops that partition; it is not repaired
   by inferring READY or importing JSON.
4. **Pin through the existing coordinator.** For approved existing historical
   containers use OutputStorageBaselineCoordinator.pin with explicit
   allow_unproven_history=True, private reader/reconcile credentials and the
   Platform fence/revision API. This copies exact canonical bytes to a backend
   version, validates exact-version read-back and records the physical baseline.
   The initial historical scan uses strict version enforcement off; this is not
   permission to disable PostgreSQL authority or use CANONICAL writes on pinned files.
   See the [existing baseline protocol](017-i10-versioned-storage.md#paired-rollout-and-rollback).
5. **Check preservation and retry semantics.** Verify only expected heads/revision/
   fences changed, logical versions/proofs/registrations/history are preserved,
   and previous bytes remain readable. On lost commit response inspect the stored
   baseline before retrying; never blindly create another pin or replace a head.
6. **Qualify that batch for actual use.** With matched VERSIONED workers/readers,
   verify exact frozen inputs, Query results, preserved out-of-scope rows and
   API/missing-proof failure closed. Where a new backfill is approved, run only its
   declared scope and confirm completion/READY and accepted immutable versions.
   Use bounded accepted-byte recovery checks, then drain/stop owned services.
7. **Record per-partition outcomes.** Keep pinned/validated/blocked/not-attempted
   identities, source/config binding, backups and actual executed gate evidence.
   A physical baseline is not permission to expose all historical rows as READY.

## Recovery and remaining gates

Keep database backups, prior metadata versions, physical baselines, registration
history and post-cutover outputs. Recovery must keep PostgreSQL authority, exact
work/attempt lineage and accepted immutable bytes; restoring stale discovery or
selecting manifest mode is not an approved rollback. Do not remove a stored PID
guard or blindly replay the migration/deletion helper without checking actual
Flyway history, delete-marker identity, heads and process ownership.

The owner decision replaces the legacy shadow comparison requirement only.
Full production/source coverage, applicable reader inventory, coordinated worker
and writer settings, safe 12-hour reconciliation, full-inventory recovery and
explicit owner review remain independently required. The bounded retained
Signals/accepted-byte repair checkpoint above does not satisfy those broader gates.
Watchdog, durable worker recovery, tracing and P12 stay deferred. No increment
completion or deployment/production acceptance is claimed by this checkpoint.
