# P14-I10 enrollment, READY integration and exact-date admission

## Current state

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
