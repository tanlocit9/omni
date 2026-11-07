# P14-I10 dispatch guard follow-up — 2026-11-04

I10 remains **in_progress**. The owner requested completion and a regression audit
focused on downstream work starting before its inputs. This document records the
bounded repair and its verification, without waiving full-increment acceptance.

Delivery follow-up (2026-11-07): the owner reports the contracts toolchain was
repaired after the failed checks recorded below. No generation or verification
rerun is included in this checkpoint delivery; those failed checks remain dated
evidence, not proof of a current compiler failure. See the [current delivery
boundary](011-i10-delivery-checkpoints.md).

## Reproduced admission gaps and repair

`JobDependencyContextFactory` previously expanded enforced input references only
for Indicators. Symbol Features, Sector Features and Backtest could receive an
empty runtime dependency list even though their definition had descriptive input
metadata. The factory now expands exact EOD, symbol-feature and sector-feature
references from the accepted work/configuration. `PlatformDependencyRegistry`
requires the accepted business day in every approved partition's coverage and
returns WAITING for empty partition resolution. Invalid enforced dependency
declarations now reject instead of disappearing during parsing.

`OutputWorkReadiness` also refuses binding if a frozen predecessor of a supported
I10 output type lacks its enrollment declaration. Catalog-only SYNC_SYMBOLS stays
protected by the existing graph SUCCESS barrier; it is not an invented output.

The owner approved adding the existing symbol/sector feature datasets to the
global metadata registry. Exact persisted dates and deterministic versions are
published using existing paths. These files have no authoritative input-version
columns; empty lineage is explicit, not reconstructed. They are not newly enrolled
I10 output producers, and their PostgreSQL reader cutover is not established.

## Worker protection

Signal evaluation reads the readiness document once, validates frozen signal rows
and EOD versions before computation, and evaluates only approved row identities
(symbol/date/model). The repository holds its existing history lock while loading
and updating; unrelated historical rows/models remain intact. EOD loads reuse the
same document, removing the per-symbol full-binding API reload.

Sector Wave workers consume the guard-approved versions and approved symbol-member
set. Changed bytes/version after dispatch reject before output publication. Exact
date checks remain fail closed; missing EOD never becomes inferred no-trade.

## Impact and regression matrix

| Boundary / source | Safety behavior | Executed or required evidence |
| --- | --- | --- |
| Platform context factory, registry, guard, execution context | Exact partitions/day; malformed declarations reject; same waiting execution resumes once | JobDependencyContextFactoryTest, PlatformIdentityAdmissionTest, DefaultJobDependencyGuardTest, JobSchedulerDependencyPostgresTest |
| Platform OutputWorkReadiness | Missing frozen predecessor declaration cannot disappear through an inner join | OutputWorkReadinessTest, GraphWorkRequestsPostgresTest |
| Platform completion declaration | Invalid sizes/nulls/duplicates and late/conflicting enrollment reject | OutputCompletionDeclarationTest |
| Platform watchdog/backfill | Unsafe timing/batch bounds reject; publication/terminal races cannot expire work; baseline requires stable fence, drained writers and known ownership | OutputSafetyBoundaryTest, GraphWorkRequestsPostgresTest |
| Platform registration inbox | Frozen work aliases and event/member IDs cannot conflict; malformed wire/semantic fields never reach persistence | OutputRegistrationEventTest, GraphWorkRequestsPostgresTest |
| Analyzer signal evaluator/history | Validate actual approved bytes, retain unrelated rows/models, load metadata once | test_signal_evaluation_readiness.py, test_signals.py |
| Analyzer Sector Wave | All three routes consume approved versions and reject drift before write | test_sector_wave_handler_paths.py |
| py-common registry/metadata synchronization/work rows | FULL and EXACT metadata preserve date holes; wire and model dates select the same scoped day | tests/storage/test_metadata_sync.py, tests/storage/test_work_rows.py |
| Kafka → worker → Platform → Query | Status without committed registration cannot dispatch downstream or expose READY; scoped query without metadata sync; provider failure/new-attempt recovery | I10WorkReadinessIntegrationTest, JobRunCrossServiceIntegrationTest |
| Existing request sharing/overlap | Cancellation/replay cannot rebind shared attempts | platform:test-overlap, platform:test-atomic-sharing |

Ingestor and Query production code are unchanged; their integration adapters select
the actual PostgreSQL reader/provider fixture, and their existing suites remain
required. Console has no changed UI/API. Shared protobuf schemas, Kafka message
shapes, migrations and storage paths are unchanged by this repair. Persistence
queries and metadata contents change as described above. Only isolated integration
flags enable enrollment/readiness; no operational defaults, deployed flags, live
job definitions, offsets or retained database history are changed.

Graph impact/change analysis is navigation evidence only: the cached graph is
stale, output is truncated/capped, and unresolved cross-language edges remain.
Direct source and canonical flow/data/ownership reconciliation supplements it.
The six-file Python impact query found 81 changed nodes and 147 impacted nodes
within two hops, with 58 additional files and truncated output. Whole-worktree
change detection also includes pre-existing owner changes; it is capped at 500
symbols and does not establish zero cross-service impact.

## Failure taxonomy and verification boundary

Historical operator cleanup failures are excluded from application-error rates.
Missing exact input dates motivate this guard repair. Provider timeout, empty
response and rate-limit branches remain distinct; the fixture can prove failure
propagation, not live-provider recovery. Historical blank-error and indicator
NoneType records without stack/version snapshots cannot be conclusively localized.
No blanket retry or timeout increase is included.

The concrete check manifest is `P14-I10-guard-20261104` under
`tools/check_result.py`. Analyzer's final run passes 266 tests; py-common passes
398 with five opt-in skips, its separate real-Kafka integration passes five,
Ingestor passes 94 and Query Service passes 47. Both real-worker cross-service
tests pass, including paused registration, failed provider and a new retry attempt.
Overlap and atomic-sharing integration both pass. The final Platform coverage
run completes 713 cases with no failures and five opt-in skips (708 passing).
Contracts generation fails with exit 0xc0000135 in the
pinned Windows protoc executable; this is not a contracts pass. Full attributable
I10 coverage and coordinated cutover remain required. Durable worker recovery,
tracing, P12 and automatic activation stay deferred.
Lint/format were not run under this test-command approval. No owner attestation,
commit, deployment, live consumer action or retained migration is included.

Final Python coverage reports are preserved in the gate's ignored `artifacts/`
directory. These are attributable per-source results, not project aggregate rates:

| Changed production source | Line | Branch |
| --- | ---: | ---: |
| Analyzer signals/evaluator.py | 97.78% | 91.67% |
| Analyzer signals/storage.py | 92.20% | 81.63% |
| Analyzer sector_wave/handler.py | 96.77% | 89.29% |
| py-common storage/dataset_registry.py | 100% | 100% |
| py-common storage/metadata_sync.py | 93.37% | 81.48% |
| py-common storage/work_rows.py | 95.65% | 92.11% |

The final Platform JaCoCo report is preserved as `platform-full-jacoco.xml` in
the same artifact directory, with `production-source-hashes.json` for this repair:

| Changed or directly exercised safety component | Line | Branch |
| --- | ---: | ---: |
| JobDependencyContextFactory | 95.38% | 87.50% |
| JobExecutionContext | 94.44% | 100% |
| PlatformDependencyRegistry | 100% | 98.21% |
| DefaultJobDependencyGuard | 92.22% | 90.20% |
| OutputWorkReadiness | 100% | 95.45% |
| OutputCompletionGate | 89.42% | 87.88% |
| OutputRegistrationStore | 100% | 88.24% |
| OutputBackfillService | 100% | 100% |
| ExecutionTimeoutWatchdog | 95.83% | 96.15% |
| OutputRegistrationEvent | 100% | 85.45% |

This repair's source matrix clears the default thresholds. It does not establish
coverage for every older I10 DTO, diagnostics/admin route, configuration class or
acceptance branch. Integration consumer/configuration execution is recorded in the
separate real-worker XML reports; the full unit report alone reports those opt-in
classes as uncovered. No fabricated merged coverage or full-I10 numeric pass is claimed.

Compact conclusion: **FAIL P14-I10-guard-20261104 check=test/contracts**.
Nine required checks pass; the contracts check remains failed. Its captured log
contains plugin exits `0xc0000135`; the command wrapper records exit 130. Required
checks are retained, and no owner attestation or increment completion is recorded.

Malformed/corrupt/empty feature objects and missing dates cannot replace an
existing metadata document in FULL or EXACT mode. Duplicate registry definitions
and unknown dataset names reject. These exercised branches protect the additive
discovery path as well as its success case.

## Documentation review

Plan 030, the increment registry, release notes, current handoff and documentation
index link this follow-up. Data-lake documentation records the additive registry
and its lineage limit. Architecture/ADR, Kafka, database, deployment, ownership and
service documentation retain their contract/activation rules: no transport,
migration, public API, path, service owner or deployment change is introduced.
Existing flow guard rules remain authoritative; this change enforces them.
Static consistency review and post-edit graph analysis cannot replace missing
executed checks or release acceptance.

Static audit of the touched scope: the P14-I10 registry row is unique and remains
in_progress after completed I9; Plan 030 retains all ten mandatory sections;
reference/008 is uniquely registered and linked from the current handoff, roadmap,
index and release notes. Directly added relative paths resolve to existing files.
Contract/ownership, local-versus-runtime and executed-versus-blocked statements
agree. No executable documentation checker was run. Historical diagnostics remain
dated evidence and now link to this repair instead of being rewritten as recovery.
