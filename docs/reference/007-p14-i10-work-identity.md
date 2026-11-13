# I10 canonical work identity — owner revision 2026-11-03

November 11: [terminal VERSIONED completion audit](017-i10-versioned-storage.md#terminal-completion-replay-audit-owner-approved-november-11)
preserves accepted work/attempt identity and READY while recording valid late or
conflicting output before ACK. Local worker-chain regression now passes 14/14;
retained rollout gates remain open. The historical late-registration blocker below
is superseded by this amendment; V26 source is extended while local DB remains V25.

November 10 [completion amendment](018-i10-worker-completion.md) preserves work/attempt
identity while carrying terminal status and registration in one record for new commands.

November 9 physical-safety amendment: [versioned backend coordinates](017-i10-versioned-storage.md)
are owner-approved and opt-in. V26 adds policy, frozen write bases and physical heads;
logical identity/row hashes remain unchanged. Earlier mutable-writer blockers below
describe the legacy route; retained cutover still requires the current runbook/gate.
November 10 shadow comparison validates registered rows and lineage against actual
bytes, while enrolled input reads retain the frozen PostgreSQL route in shadow mode.
The disposable baseline-to-Signals test passes; the late-registration blocker remains.

Current follow-up: [downstream guard, frozen evaluation inputs and verification
matrix](008-p14-i10-dispatch-guard.md). This supplements the identity contract;
it does not close the increment or authorize cutover.

## Migration stages and local V18 reconciliation — 2026-11-04

The owner requested smaller migrations. The current sequence replaces the
historical consolidated V19, preserving its SQL statement order and final schema:

| Version                                                                 | Responsibility                                                                       | New tables |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------ | ---------- |
| [V19](../../database/migrations/V19__output_registration.sql)           | Registration inbox, outputs, coverage, immutable evidence and attempt/execution FK   | 4          |
| [V20](../../database/migrations/V20__output_completion_and_timeout.sql) | Completion contract, successful status, timeout evidence and publication-clock index | 3          |
| [V21](../../database/migrations/V21__output_ready_projection.sql)       | Physical READY pointers and catalog revision                                         | 2          |
| [V22](../../database/migrations/V22__output_ready_baselines.sql)        | Fenced historical baselines and physical catalog view                                | 2          |
| [V23](../../database/migrations/V23__output_work_readiness.sql)         | Work READY, row ownership and scoped catalog view                                    | 2          |

Apply the complete sequence before starting the current application. Intermediate
versions are schema checkpoints, not supported runtime cutovers. Both trigger
functions use CREATE OR REPLACE FUNCTION. Existing tables, indexes and triggers
remain strict CREATE statements so incompatible leftovers fail visibly.

Live local inspection found the old V19-V22 ledger had already been removed by
the owner, but two empty registration tables remained. The authorized cleanup
locked and checked both tables for emptiness, then dropped output_registrations
and output_registration_events with RESTRICT. Their indexes/triggers were removed
with the tables; no CASCADE, ledger repair or changes to V1-V18 were performed.
The existing compatible guard function remains replaceable. This finding corrects
the older assumption below that the old migration sequence was never applied.
Other retained environments require their own history/object reconciliation.

Owner-approved local verification executed V19-V23 through psql in one transaction,
then rolled it back. It checked all 13 tables, empty catalog views, revision zero,
the composite registration/attempt FK, unchanged counts in execution histories,
outbox, definitions, symbols and sectors, and unchanged Flyway history. After
rollback, no output tables remained and Flyway was still at V18. The 19 Stock
Prices, one Symbols and one Metadata definitions remained active and scheduled.
Local SQL and output are in the ignored .agent/reports/p14-v19-v23-transaction-check
artifacts. This proves SQL execution on this local V18 schema, not Flyway
validate/checksum, fresh-install, restart, application, provider or end-to-end
acceptance. The updated migration JUnit fixture was not executed in this task.

Cross-service impact: Platform persistence/install order and its migration test
expectations change. Analyzer, Ingestor, Query Service and Console retain the same
final tables/views and runtime behavior. Shared contracts/libraries, Kafka payloads,
storage paths and configuration defaults are unchanged. Operations must install
through V23 before runtime; no flags or deferred P12 features are activated.
P14-I10 remains in_progress.

Documentation reconciliation: this handoff, database guide, current flow links,
Plan 030, increment registry, release notes and index reflect the new sequence.
Architecture, ADR, Kafka/data-lake, deployment, ownership, service and agent guides
need no behavior/policy changes: final schema contracts, READY authority, paired
deployment, ownership and default flags are preserved. Their current-handoff
pointers replace obsolete single-V19 wording where present. Historical consolidation
results below remain dated evidence and do not verify the split sequence.

Static consistency review of the changed scope found the migration order, new
links, current-version expectations and unchanged I10 status consistent. Graph
impact and post-edit change analysis ran with a stale index, so their zero-hop
results are not no-impact proof; direct SQL/source reconciliation supplies the
cross-service boundaries above. No build, lint, format or JUnit run is claimed.

## Goal

Use the existing graph atomicWorkId consistently for work readiness and causal
input bindings. It hashes JobType, WorkType, workKey, businessDate and canonical
parameters through WorkExpansion.identity / atomic_work_id. No new identity hash
or I11 scope is invented. The registry owner-decision flag is now false because
the owner explicitly resolved this identity direction; acceptance is not waived. WorkIdentity(type,key) remains a legacy routing tuple,
not the identity of a dated graph work.

## Outcome and boundaries

READY means **the registered work's data exists in canonical Parquet**, not that
an entire history file belongs to that work. Identity remains the existing
atomicWorkId (job type, work type/key, business date, parameters). Attempts and
executions distinguish runs; (atomicWorkId,dataVersion) identifies a work output.

The additive `workScope` contains `atomicWorkId` and exact `rowKeys`:

- EOD and indicators: date, inside the logical exchange/code partition.
- Signals: symbol_key + signal_date + model_version when non-null, inside
  strategy/timeframe/exchange. Omitting model_version identifies only a legacy
  null/missing-version row, never all models at that symbol/date.
- EVALUATE_SIGNALS: only rows whose outcomes changed, retaining origin signal
  dates rather than inventing coverage on the evaluation date. An unchanged
  evaluation uses the existing approved OUTPUT_UNCHANGED completion.

WORK_ROWS_V1 fingerprints the logical dataset/partition, work scope, schema,
canonical encoded scoped rows and direct approved input versions. It excludes
other days/symbols, physical path and the container checksum. schemaVersion stays
1; `workScope` distinguishes this fingerprint domain from old artifact versions.
New enrolled outputs require a scope; legacy shadow/baseline evidence can omit it.
The paired Java/Python registration fixture covers both compatibility branches.
The signal-model fixture additionally covers coexisting legacy, V1 and V2 rows.

Appending day D+1 leaves day D's version/READY intact. Overwriting an overlapping
coordinate supersedes the previous proof; deleting/corrupting scoped data causes
reader validation to fail. A retry has the same work identity but can publish a
new version/attempt. Canonical paths remain overwritten; enrolled writers no
longer require or create whole-file snapshots. The optional snapshot feature
remains available to legacy storage/backfill paths, independently of work READY.

READY is availability. Frozen upstream attempts and approvedInputs describe
provenance; they are not a recursive assertion of perpetual upstream freshness.
Completed downstream data does not disappear merely because an upstream work is
rerun. Recalculation/freshness policy is separate. No durable worker recovery,
writer service, tracing, automatic replay or I11 scope is added.

## Source and persistence

The I10 schema is split into ordered V19-V23 migrations; see
[the migration stages and reconciliation boundary](#migration-stages-and-local-v18-reconciliation--2026-11-04).
V1-V18 are unchanged. output_work_ready is keyed by work/dataset/partition.
output_ready_rows records current ownership of exact row coordinates, with
monotonic generation fencing. output_work_ready_catalog requires every coordinate
in the work's scope to retain that ownership; it does not join the latest physical
file version. The partition pointer remains a legacy/discovery projection.
Promoting rows, work pointer and revision is transactional and READY-last, after
registration + SUCCESS, excluding timed-out attempts. Replays cannot regress a
newer generation. Out-of-order promotions of non-overlapping rows still advance
the discovery revision when their work proofs change.

Enrolled admission resolves exact frozen upstream work attempts and their scoped
versions, without consulting the latest file version/date. Multiple upstream
symbols/dates may share a physical partition. Built-in EXISTS/READY/PARTITION_MATCH
policies are adapted; an enforced policy requiring an unadapted range, schema
range, freshness or CURRENT_INPUTS rule fails closed as WORK_SCOPE_POLICY_UNSUPPORTED.
It is never silently evaluated against a different file-wide version. Legacy
unenrolled admission retains its existing manifest policies.

Workers fetch approved tuples through private GET
/api/v1/jobs/output-readiness/work?atomicWorkId=work:... and verify work ID,
attempt, business date, output version and scope. Partial or superseded proofs
fail closed without manifest fallback. Registration inputs must match execution
approvals, independently of the historical lineage columns in a shared file.
Reader/reconciler token scopes are unchanged; Kafka still contains logical
references only, with configured paths derived by storage/API adapters.

Workers decode the canonical bytes once and verify scoped rows/schema/version.
They retain history through the work date for algorithm lookback; a day-scoped
proof does **not** certify that entire lookback range or its freshness. Range
readiness needs an explicit range contract before such a guarantee is claimed.
The private paged catalog lists current work proofs; Python aggregates proofs per
partition with a distinct inventory fingerprint. Query Service exposes only the
union of registered READY rows and executes the validated in-memory data, avoiding
a second mutable-path read. Public SQL/catalog DTO shapes remain unchanged.
Historical NULL-provenance baselines remain artifact evidence, never invented
work identities. They cannot bootstrap an enrolled frozen upstream work.

## Matched deployment boundary

Drain previously enrolled queued commands before installing strict identity readers.
Their first approvedInputs payload is immutable; legacy partition/version approvals
must not be patched into invented graph identities. Deploy matching Platform and
shared Python code, and review the complete V19-V23 sequence before first I10 deployment. Enable
READY promotion with enrollment for new supported chains so upstream identity
proofs exist before dependent work is admitted. A validated historical file/baseline
alone cannot satisfy a missing work identity; prepare a legitimate new work attempt
when needed. Do not repair the ledger, automatically replay failed work, or turn on
reader cutover without the complete separate gate. These are documented steps,
not executed deployment actions.

The 2026-11-04 correction requires paired Platform/Python deployment before new
versioned signal scopes are emitted. A pre-correction two-coordinate signal proof
cannot certify a versioned row; readers fail closed rather than widen its scope.
Drain old commands and obtain new evidence through legitimate work attempts when
needed. Do not rewrite immutable registrations, approved bindings or SQL row keys.
No schema migration or retained-data operation is part of this correction.

## Contract impact and blast radius

| Owner                    | Impact / boundary                                                                                                                                                                                                 |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Platform                 | Scoped registration validation, current row projection, generation/revision fencing, work-based admission and private work/catalog reads.                                                                         |
| Analyzer                 | Indicator/signal/evaluation inputs resolve frozen work proofs; calculations retain historical context. Shared canonical writer captures scoped output after read-back.                                            |
| Ingestor                 | EOD canonical writer captures day-scoped output independently of snapshot flag; provider/algorithm unchanged.                                                                                                     |
| Query Service            | Aggregate work-proof inventory, scoped validation/filtering and execution on the validated buffer. Public DTO shape unchanged.                                                                                    |
| Console                  | Existing SQL/catalog DTOs unchanged; returned availability/count/version now describes READY rows in PostgreSQL mode. No new UI or browser credentials.                                                           |
| Shared contracts/library | Additive proto work_scope=11, Java/Python adapters, generated contracts through Nx; row hashing, validation, scoped publication and read helpers. Old unscoped fixture retained.                                  |
| Persistence              | Row ownership is in V23; V1-V18 are unchanged. Local V18 cleanup and migration evidence are recorded below; other retained environments require their own reconciliation.                                         |
| Config/operations        | Defaults remain manifest/off. Paired deployment/drain required; snapshot flag no longer a prerequisite for new enrollment. Custom enforced policies and historical/range coverage require explicit cutover proof. |

Pre-edit graph impact across metadata/shared readers/Query found 45 changed nodes,
47 impacted nodes and 18 additional files within depth two, with 139 unresolved
call sites and truncated edges. A prior wrong-path query returned an unindexed
zero; it is not used as no-impact evidence. The manual matrix above follows
cross-language Kafka/storage/HTTP boundaries beyond graph reachability.

## Review corrections and writer TODOs — 2026-11-04

Three source corrections are present: model-aware signal row coordinates in the
paired Java/Python adapters; registration scope/identity/input/envelope preflight
before direct writes or staged copies replace canonical bytes; and aggregate
catalog min/max timestamps across all contributing work proofs. Datasets without
timestamp bounds retain nulls. Only successful canonical read-back confirms a
prepared output for publication. The 4096-row, 128-input and 256-KiB event limits
are unchanged; oversized output is rejected before canonical mutation, without
truncating rows or lineage. Kafka ACK remains before worker SUCCESS.

Current declarations contain one logical output per work. Preflight validates the
candidate envelope and reserves the full required-output declaration; it does not
make a future multi-output batch atomic. Copy/read-back/Kafka failures after the
canonical mutation still require the separate recovery and ownership gates.

Fresh owner-approved Nx checks for this revision (2026-11-04):

| Target (all with --skip-nx-cache) | Executed result                                                                                        |
| --------------------------------- | ------------------------------------------------------------------------------------------------------ |
| py-common:coverage                | PASS: 387 passed, 5 opt-in Kafka integration skips.                                                    |
| platform:coverage                 | PASS: 623 test entries, 4 opt-in skips, zero failures/errors; JaCoCo generated.                        |
| analyzer:coverage                 | PASS: 248 passed.                                                                                      |
| ingestor:coverage                 | PASS: 94 passed.                                                                                       |
| query-service:test                | PASS: 47 passed, including model-specific READY filtering.                                             |
| contracts:test                    | BLOCKED: contracts:generate failed with Windows 0xc0000135; buf build/artifact checks did not execute. |

The pinned grpc-tools/protoc.exe imports MSVCP140D.dll, VCRUNTIME140D.dll,
VCRUNTIME140_1D.dll and ucrtbased.dll, absent from the checked System32/tool
locations. No compiler replacement or global PATH modification was performed.
Generation cleans its ignored output directory before running; generated artifacts
are currently absent after that failed attempt. The shared JSON fixture passes
in both Java/Python suites; that is not a protobuf generation pass.

Python tests used workspace-local temporary/cache paths after an initial temp
permission error and a missing basetemp parent. Platform used the existing user
Gradle cache after the sandbox run could not create a C:/.gradle lock. Initial
regressions also exposed an incorrect delete method on the storage test double
and incomplete partition definitions in the new Query fixture; both fixtures
were corrected before their final passing runs. No production behavior was
weakened to obtain a pass.

Attributable coverage (line/branch) from the final py-common XML report:
output_publication 97.37/87.50%; output_registration 96.32/90.28%; work_rows
95.65/92.11%; output_readiness 88.39/80.65%; ParquetStorage 98.54/95.83%.
The changed Java Output record has 100/83.33% in the full JaCoCo report. Older
registration/envelope branches remain below the full-I10 threshold; these
results do not close whole-increment coverage or worker-to-Query E2E acceptance.
Reports remain under coverage/libs/py-common, apps/core/build/reports/jacoco/test
and the project JUnit report directories. These are initial fix results before
the full-workspace formatting follow-up below; lint/format was not run at that
stage. No retained migration, provider call, deployment or flag activation was run.

Prior dated results remain historical; P14-I10 stays in_progress and P12 stays deferred.

| Behavior                                                                                                          | Source and regression mapping                                                                     |
| ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Coexisting legacy/V1/V2 rows, model-specific selection and outcome changes, preserve other models                 | work_rows; test_work_rows; shared signal-model fixture in Python/Java registration tests.         |
| Reject 4097 rows, 129 inputs, invalid work identity and oversized wire payload before either canonical write path | OutputPublication.prepare/preflight, ParquetStorage; test_work_rows preflight preservation cases. |
| Read-back mismatch never confirms prepared evidence                                                               | ParquetStorage.\_record_work; test_work_rows read-back rejection cases.                           |
| Query exposes only the registered model at a shared symbol/date                                                   | DatasetResolver/select_rows; test_storage signal-model regression.                                |
| Aggregate bounds cover every work regardless of order, with null bounds preserved                                 | PostgresMetadataReader.\_document; test_work_rows aggregate timestamp cases.                      |

Cross-service reconciliation for this fix: Platform validates the optional model
coordinate; shared contracts carry it in existing row-key maps/Structs. Analyzer
and Ingestor use shared canonical-write preflight; analytical algorithms/providers
are unchanged. Query uses the corrected shared row selector and aggregate bounds;
Console consumes the same catalog DTO with corrected values and needs no UI edit.
PostgreSQL already compares the complete row_key JSONB, so distinct models need
no DDL change. Existing generation/transaction/timeout rules, configuration and
storage paths remain intact. Operations must honor paired-reader compatibility;
no rollout is inferred from these local tests.

TODO(P12-I4), required writer review before activation:

- Preserve model-aware row coordinates across merge, deduplication, hashing,
  registrations, PostgreSQL ownership and Query reads. Keep legacy null-version
  rows distinct; never replace model identity with work, batch or intent identity.
- Stage and preflight ALL output/member evidence before the first canonical
  mutation of a batch. Exercise row/input/UTF-8 envelope limits at and above their
  boundaries. Splitting/paging/finalization needs an approved bounded contract;
  do not truncate evidence or raise limits silently.
- Bind prepared evidence and immutable IDs to durable intent/result replay;
  inject failures before/after copy, read-back, registration ACK and status ACK.
  Prove offset safety, idempotent replay and terminal-status ordering. Do not
  reclassify an unregistered completed write as OUTPUT_UNCHANGED on retry.
- Prove storage write exclusion/fencing for shared physical paths, including a
  stale worker after timeout. DB generations and in-process locks alone do not
  stop stale MinIO writes. Preserve unrelated rows and model versions.
- Verify batched catalog coverage/bounds, row counts and registered READY union
  end to end through Query; preserve null timestamp semantics for signals.

See [deferred writer plan](../plans/027-concurrent-workers-and-writer-batching.md).
These are review requirements, not implemented recovery, paging or P12 activation.

Documentation review for this correction covers Plan 030/registry, release notes,
Plan 027, data-lake/Kafka contracts, current execution flow and paired deployment.
Architecture/ADR-008, database, ownership, service guides, indexes and roadmap
summary already link this handoff: no new owner, table, migration, path, flag,
public DTO, service or increment is introduced, so those areas need no further
edit. AGENTS/CLAUDE/Roo guidance already enforces shared-contract review and
verification boundaries; no policy change is needed. Graph tools were unavailable
during the initial fix review, so it claims no fresh impact/change analysis.
The formatting/flow follow-up below records graph analysis from the later session.
Earlier graph measurements elsewhere in this handoff remain historical.

Static documentation audit: CONSISTENT in the edited scope. Plan 027/030 retain
all mandatory sections; the registry retains one in_progress P14-I10 and a
pending/approval_required P12-I4. Direct paths and the new handoff/checklist
anchors resolve, contract/flow/deployment wording agrees, and historical passes
are separate from the current blocked generator. This audit is not increment
completion, graph analysis or an executed documentation-checker result.

### Full workspace formatting and end-to-end flow follow-up

The [current end-to-end flow](../flows/007-p14-end-to-end.md) maps the implemented
opt-in scheduler, work preparation, worker publication, Platform READY and
Query/Console path to existing P14 tickets with overview and sequence Mermaid.
It records error/timeout branches, valid-empty behavior, current model-aware
coordinates, preflight and the deferred P12 writer requirements. Flow 001 links
the companion; Stock Sync and Indicator/Signal summaries now describe canonical
row-scoped publication rather than a whole-file snapshot prerequisite. README
and the numbered registry index the new flow; Plan 030 links it.

Formatting uses the declared full-workspace target omni:format --all. Four Python
lint-fix targets passed after manual E501 wrapping, shorter test names, combining
equivalent nested context managers and replacing one assigned lambda with an
equivalent local def. No unsafe Ruff fixes, suppressed rules or weakened limits
were used. The full rerun `npx.cmd nx run omni:format --all` passed, including
the configured Prettier, Java and Python routes. `contracts:format-write` also
passed after adding the existing Git usr/bin diff executable to that command's
PATH; no global PATH change was made. This formatting result does not change the
earlier blocked protobuf generation result. The earlier tests/coverage are
pre-format evidence and are not claimed as fresh post-format behavioral verification.

Graph tools are available in this follow-up. A full rebuild parsed 651 files
with no parse errors. Pre-edit source impact across the seven publication,
storage, completion and Query entrypoints found 86 directly changed nodes, 268
impacted nodes within two hops and 34 additional files, with truncation and 322
unresolved call sites. Flow extraction was not run by the minimal postprocess;
the Mermaid was reconciled against source and canonical cross-service docs.
This does not retroactively supply graph evidence to the initial review or prove
any live runtime path. Formatting is not an activation, E2E or coverage gate.

Post-edit graph change detection completed across 189 changed tracked files,
capped at 500 changed functions/classes. It reported 227 heuristic test gaps;
these are discovery hints, not executed coverage findings. Zero affected flows
does not prove isolation because flow extraction was not built. The workspace
whitespace check git diff --check passed. Formatter logs are retained under
the ignored .agent/i10-flow-format-20261104 directory.

Documentation review: only flow detail, navigation, Plan 030 linkage and release
notes need semantic updates in this follow-up. The same service, data, database,
architecture/ADR and rollout ownership is retained; no new wire fields, paths,
flags, analytical algorithm or migration is introduced. Existing AGENTS/CLAUDE/
Roo guidance remains applicable. P14-I10 remains in_progress and P12 remains deferred.

Static documentation audit is CONSISTENT for the new flow, ticket mapping,
existing flow corrections, indexes and Plan 030 linkage. Registry statuses and
Plan 030 mandatory sections are retained; source entrypoint paths and relevant
anchors resolve. Mermaid was reviewed statically, without a rendered-browser
verification. No executable link checker, test, coverage or deployment was run
for this formatting/documentation follow-up.

## Scoped-data revision verification — 2026-11-03

Gate `P14-I10-rows-20261103`: PASS, eight required checks recorded from executed
commands. This is a source regression gate, not whole-I10 acceptance. Earlier
results below are historical; I10 remains in_progress, without owner attestation.

| Executed check                                        | Result                                                                      |
| ----------------------------------------------------- | --------------------------------------------------------------------------- |
| Platform full coverage suite                          | 619 entries, 4 opt-in skips, zero failures/errors.                          |
| Final focused Platform metadata/graph/migration suite | 95 tests, no skips, zero failures/errors.                                   |
| Final scope/baseline/revision unit suite              | 2 tests, no skips, zero failures/errors.                                    |
| py-common coverage                                    | 370 passed, 5 opt-in Kafka skips.                                           |
| Analyzer coverage                                     | 248 passed.                                                                 |
| Ingestor coverage                                     | 94 passed.                                                                  |
| Query Service tests with resolver/executor coverage   | 46 passed.                                                                  |
| Contracts test/generation                             | 12 generated Java/Python artifacts verified, including RegisteredWorkScope. |

Attributable coverage from the actual reports (line/branch): OutputPublication
100/100%; work_rows 95.38/91.18%; approved_inputs 96.49/92.11%; readiness reader
88.39/80.65%; ParquetStorage 98.03/94.23%; Analyzer indicator/evaluator 100/100%,
signal handler 88.14/80.56%. Query DatasetResolver is 38/41 lines and 18/22
branches (92.68/81.82%); its dataset registration method is 19/19 lines and 8/8
branches. These Query numbers are attributed to source ranges, not the entire
storage module's older catalog code.

The final focused Java report records OutputWorkReadiness 97.37/95%,
OutputReadinessStore 100/80%, PlatformDependencyRegistry 100/96.30% and
OutputReadinessProvider 98.65/85%. CompletionGate (96.49/66.67%) and historical
backfill (97.22/56%) still miss the branch threshold, as do older registration
validation branches. The final two-case report additionally exercises the scoped
baseline rejection; reports are kept separately, without inventing a merged
percentage. Thus suite success does not close whole-I10 coverage acceptance.
Static documentation audit is consistent within this revision: current semantics,
contracts, migration and flow agree; dated earlier evidence remains historical.
Post-edit graph detection found 52 tracked changed files, 79 symbols and heuristic
test gaps; flows were not built, so zero reported flows is not impact evidence.
Full/focused JaCoCo reports and counts are retained under the ignored local gate
artifacts. The initial Query run hit the previously observed asynchronous audit
assertion race; the final isolated full Query run passed. No production audit
behavior was changed.

| Behavior                                                                                        | Source / regression mapping                                                                                          |
| ----------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Append/unrelated row cannot invalidate day proof; owned mutation/missing/duplicate/schema fails | work_rows, ParquetStorage; test_work_rows scoped reader and preservation tests.                                      |
| Canonical READY-last without snapshots, staged/copy path, no historical scope expansion         | OutputPublication/ParquetStorage; test_work_rows writer and output-publication cases.                                |
| Outcome rows retain original dates and only changed symbols                                     | scope_for_work; test_work_rows outcome case, Analyzer outcome/repository suite.                                      |
| Matching Java/Python scope contract and old fixture compatibility                               | OutputRegistrationEvent/RegisteredOutput/proto; shared old/new fixtures and contract generation.                     |
| Exact frozen identity lookup and shared partition proofs                                        | OutputWorkReadiness/PostgresMetadataReader/approved_inputs; work lookup, aggregation, rejection and admission cases. |
| Transactional work/row promotion independent of physical pointer                                | V19/OutputReadinessStore; real PostgreSQL GraphWorkRequests test including unrelated-date and overlapping-row cases. |
| Query reads checked data buffer and filters to READY rows                                       | DatasetResolver/DuckDBExecutor; storage/executor regression cases.                                                   |

Documentation synchronization covers plan/registry/release notes, ADR,
architecture, flow, Kafka/data-lake/database, rollout, ownership, service guides,
indexes and AGENTS. No new service/deployment variable, public UI/SQL DTO shape, storage
layout or scheduled job is introduced; documents describing those unchanged
surfaces need only the linked semantic amendment. No retained deployment,
migration or complete real worker→Kafka→Platform→Query E2E has been executed.
Existing whole-I10 coverage, timeout ownership resolution, custom policy and
historical/range cutover gates remain open. Direct-input/scope/envelope limits
remain bounded and fail closed; this change does not introduce lineage paging.

## Historical migration consolidation verification before scoped data revision

Owner authorized consolidating the unapplied I10 sequence. Registration,
completion/timeout, physical projection, baselines and work READY remain separate
responsibilities inside one V19. Generation and registration-attempt FK are defined
in their final CREATE TABLE statements. The composite attempt/execution prerequisite
on the existing V17 table remains necessary. The empty intermediate work-pointer
backfill is removed. Advisory lock (21410,22) stays unchanged: it is a protocol
coordination key shared with Java, not a Flyway version number.

The older V20 checksum question is resolved by the owner's confirmation that it
was never applied; no ledger repair or retained database changes are performed.
Current narrow consolidation gate is P14-I10-squash-20261103. Fresh checks:

| Check                          | Result                                                                                                                                    |
| ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Platform full coverage target  | PASS: 615 test entries, 4 opt-in workflow skips, zero failures/errors; JaCoCo generated.                                                  |
| Focused migration/graph suites | PASS: 47 tests, no skips; fresh installs, V16/V18 upgrade and existing I10 registration/completion/READY behavior.                        |
| Documentation                  | Static consistency audit plus post-edit graph change analysis; no executable link checker, deployment or migration against retained data. |

The single V18-to-V19 upgrade test validates one migration, all I10 evidence tables
initially empty, both catalog views empty, revision zero, generation identity,
Flyway validate and an idempotent restart. Existing retained-fixture tests preserve
old V1 paths, offsets, execution/outbox delivery states and immutable membership.
The schema is semantically the former final I10 schema; no Java/Python runtime,
Kafka/protobuf fields, storage paths or feature flag defaults changed. Analyzer,
Ingestor, Query Service, Console and shared-library runtime targets were not rerun
for this SQL/docs-only consolidation; previous results above/below are historical.

Static reconciliation covered the plan/registry/release notes, ADR/architecture,
flow/data/database, deployment/ownership, service guides and indexes. V1-V18 and
the canonical atomicWorkId contract are unchanged. Former migration links in the
historical 004/005 handoffs now point to their exact pre-squash Git commit; current
links point to the consolidated V19. No active V20-V23 remains in migrations.
Post-edit graph analysis reports 31 changed files, 22 symbols and heuristic SQL
test-gap candidates; bounded graph reachability/flows skipped is not behavioral
proof. Executed PostgreSQL tests supply the migration evidence. Static audit is
CONSISTENT in the changed scope; the whole I10 E2E/coverage/cutover gate remains
open and no owner attestation/completion is recorded.
The current opt-in execution flow is in
[Job execution flow](../flows/001-job-execution.md#current-graph-and-i10-flow).

## Historical identity revision verification before consolidation

New gate: P14-I10-identity-20261103. No earlier passing result is reused for changed
source. Focused Java/Python cases cover canonical date/key/parameter separation,
legacy boundary, missing/ambiguous identity rejection, exact identity serialization,
private-token scopes and forward-migrated PostgreSQL work lookups. Existing tests
exercise duplicate status/registration, generation guards and retained upgrade.

| Criterion                                              | Source / executed test mapping                                                                                                                          |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Canonical identity and input serialization             | WorkExpansion.identity, DatasetRef; OutputWorkReadinessTest and DatasetRefIdentityTest (date/attempt separation, immutable coordinates, legacy wire).   |
| Frozen upstream binding, missing/ambiguous work        | OutputWorkReadiness.bind; OutputWorkReadinessTest; full Platform suite.                                                                                 |
| Real promotion/work lookup and NULL baseline exclusion | Consolidated V19/OutputReadinessStore/OutputWorkReadiness.read; GraphWorkRequestsPostgresTest.baselineHasNullProvenanceAndCannotReplaceRegisteredReady. |
| Auth scopes                                            | TrustedOperatorHeaderFilter; OutputReadinessTokenTest.                                                                                                  |
| Worker identity/date/attempt/version validation        | approved_inputs; test_approved_inputs plus affected worker suites.                                                                                      |
| Fresh and retained forward schema                      | SchedulerOutboxIdentityMigrationPostgresTest now expects V19, one migration from V18 and three from V16.                                                |

Fresh Python/contract results: py-common:coverage PASS 343 passed / 5 opt-in Kafka
skips; analyzer:coverage PASS 242; ingestor:coverage PASS 94; query-service:test
PASS 40 on isolated rerun; contracts:test PASS canonical generation/build and all
11 expected artifacts. Java full coverage PASS: 599 entries / 4 opt-in skips / zero failures or errors.
A separate focused admission/value-object/token run PASS: 24 entries, no skips.
Do not sum these overlapping runs. The backend identity/upgrade focused run also
passed seven cases; it is not the missing worker-to-HTTP integration.
The five Kafka skips do not establish real-broker identity round-trip evidence.

Fresh results are recorded when available below. The initial focused run failed
when the candidate V23 was edited while its disposable database test was active;
Flyway correctly rejected the checksum on restart. A subsequent full run caught
a retained-upgrade test still expecting six migrations after V16; V23 makes seven.
The expectation is corrected without changing migration checksums/history.
Final V23 is rerun on fresh
disposable databases, never repaired. Query Service initially hit its existing
audit-event timing race under parallel load; isolated rerun passed 40 tests. No
production Query Service change or deterministic lifecycle guarantee is claimed.
Full worker-to-API identity round trip, all-source attributable coverage, artifact
fingerprint compatibility and coordinated cutover remain separate gates. I10 is
in_progress, not owner_verified/completed.

### Attributable identity coverage

The full Java report was preserved before focused runs as
`.agent/check-results/P14-I10-identity-20261103/artifacts/platform-full-jacoco.xml`.
The focused report is `identity-admission-jacoco.xml` in the same ignored directory.
Production source did not change between these runs; only targeted tests were added.
The existing threshold helpers check each component against its executed report;
no merged/aggregate coverage is fabricated. No equivalent increment-specific Nx
threshold target exists, so the repository helpers are invoked directly.

| Source                      |   Line |           Branch | Executed report        |
| --------------------------- | -----: | ---------------: | ---------------------- |
| OutputWorkReadiness         |   100% |           91.67% | Full Java              |
| OutputReadinessStore        |   100% |              80% | Full Java              |
| JobService                  | 95.10% |           87.72% | Full Java              |
| SchedulerOutboxService      | 99.07% |              90% | Full Java              |
| DatasetRef                  |   100% |           93.75% | Focused admission      |
| PlatformDependencyRegistry  |   100% |           97.50% | Focused admission      |
| TrustedOperatorHeaderFilter |   100% |           82.14% | Focused admission      |
| OutputReadinessController   |   100% | N/A, no branches | Full Java              |
| Python approved_inputs      | 93.94% |           87.50% | py-common coverage.xml |

The controller has no BRANCH counter; its line gate is checked separately, not
represented as an 80% branch pass. SQL has no numeric coverage instrumentation;
the PostgreSQL cases prove identity lookup, baseline exclusion, missing upstream
proof invalidation, missing physical projection invalidation/re-promotion and the
V16-to-V23 retained fixture upgrade. Unit registry cases cover ready identity,
missing identity, date holes, unresolved writers, signal component inputs, schema
incompatibility and malformed accepted coordinates.

Compact conclusion: `INCOMPLETE P14-I10-identity-20261103
check=integration/identity-roundtrip source=none`. All six unit/contract targets and
the identity coverage check pass. Full real-worker/HTTP identity integration is
not run; older whole-I10 coverage/cutover gates are not waived. No owner attestation,
production migration, flag activation, deployment or automatic repair was performed.

## Indicators backfill follow-up - 2026-11-05

The owner requested a bounded sector/date Indicators API with automatic EOD
registration/READY. See [the request contract, force policy, cross-service impact
and local verification](009-indicators-backfill.md). V25 extends graph request
mode only; this default-off path scopes enrollment/promotion without global
cutover. Generic manual triggers and the general Plan 028 rollout remain deferred.
I10 remains in_progress; this follow-up does not waive broader acceptance gates.

## Documentation reconciliation

Plan 030, registry, roadmap, indexes, release notes, ADR-008, architecture, execution
flow, Kafka/data-lake/database, deployment, developer ownership, four service guides
and AGENTS point to this owner revision. No phase dependency, schema owner,
analytical algorithm, provider credential, P12 writer, recovery journal, tracing or
I11 registration is added. CLAUDE/Roo/governance already require shared identity,
contract review and verification; no policy change is needed. Historical handoffs
004-006 and their measurements remain history, superseded only for this identity
scope. Static documentation consistency cannot replace missing runtime acceptance.

Static audit: the canonical P14-I10 row remains unique and in_progress after
completed I9; only the resolved owner-decision flag changes. Plan 030 retains all
ten mandatory sections. Reference/007 is indexed as current, 006 remains historical
for this revised identity scope, and directly changed relative links resolve to
existing files. Contract/ownership and executed/not-run wording are consistent in
the touched scope. Post-edit graph analysis is repeated at delivery; flows are
skipped and its heuristic gaps are not executed coverage. This audit does not
convert the incomplete integration gate into increment completion.
