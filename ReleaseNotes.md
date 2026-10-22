# Omni Release Notes

## 2026-10-22 - I3.d operator job triggers deferred (local gate passed)

Owner deferred manual job triggering and request audit under TD-017. Source removes
the manual service/entity/repository/config, Console trigger forms/polling and
metadata-sync buttons. Catalog/execution and dataset reads remain. Old trigger/audit
routes return authenticated HTTP 410 `manual_trigger_deferred`. V14 drops only the
old manual audit table after old readers stop; no audit migration or JobRun schema
rewrite. Back up old history if rollback is needed. Internal MANUAL preparation
stays non-exposed; automatic cron/FIFO/guard and false preparation flag remain.
The seven-check format/test/coverage/build/typecheck gate passed: 341 Platform tests
across 55 suites and 27 Console tests across six files. Impacted controller/catalog
methods have 100% line and applicable branch coverage; V14 upgrade and fresh-startup
cases passed on isolated PostgreSQL. Static inventory retains all 32 canonical cron
identities until I4 admission safety; I2/I3 registry statuses remain unchanged.
No migration against retained databases, deployment, activation or commit.

## 2026-10-21 - Rejected-request audit accepted as technical debt

TD-017 records owner acceptance of deferring durable audit/reasons for requests
that never produce a committed JobRun. The earlier proposal to expand JobRun's
request-only lifecycle is no longer a prerequisite for current I3.d. API rejection
reasons, permissions and committed-run correctness remain required. The exact
legacy removal scope is awaiting clarification; no table/API has been removed.
Documentation only, with no verification execution or runtime activation.

## 2026-10-21 - I3.d manual cutover proposal (superseded by TD-017 decision above)

Plan 030 records the request/audit lifecycle gap between the current manual API and
sealed JobRun storage, the proposed additive cutover, recovery/compatibility tests,
cron inventory and deferred manual-table drop. Owner data-model approval is pending.
No schema/source change, verification execution, cron reduction or activation.

## 2026-10-20 - I3.c replay lock order (local gate passed)

Visible-run replay now locks the target definition before the run, matching fresh
preparation and preventing definition/run inversion during overlapping retries.
The new PostgreSQL regression reproduced an actual deadlock on the old Java source;
the repair passes both scheduled/manual variants with unchanged execution identity,
membership, cadence and fenced lease release. The five-check local gate passed:
29 focused PostgreSQL tests, 352 full Platform tests / 56 suites, no failures,
errors or skips. Preparation service coverage is 99.35% line / 91.67% branch.
A second static review found no additional issue within the changed replay path
and its definition/run/claim callers. Docs are synchronized; preparation remains
false and I2/I3 status is unchanged. No migration or activation is included.

## 2026-10-20 - I3.c progress lock repair (local gate passed)

V13 execution/outbox UPDATE guards now bypass membership locks when the complete
protected identity is unchanged. Structural changes retain membership guards and
sorted run locks. Added five PostgreSQL regressions, including real status/upsert
interleaving. The exact old migration reproduced `deadlock detected`; its failing
baseline is retained separately. The renewed five-check format/focused-test/full-test/
coverage/clean-build gate passed: 28 focused PostgreSQL tests and 351 full Platform
tests across 56 suites, zero failures/errors/skips. Directly impacted Java components
exceed 80% line/branch coverage; SQL uses an executed criterion matrix in Plan 030.
No Java service or aggregation lock changed. Preparation remains false, I2/I3 status
is unchanged, and no production migration or activation is claimed.

## 2026-10-19 - I3.c lock-safety repair plan (open finding)

Static review identifies a possible parent/run lock cycle between symbol-upsert
aggregation and child status handling. Plan 030 now scopes execution and outbox
progress fast paths, retained membership guards, deterministic PostgreSQL lock
tests and a renewed verification gate. Previous 346-test evidence does not cover
this interleaving. Planning only: no SQL/Java change, test/build/lint/format run,
activation or increment-status promotion.
See [repair plan](docs/plans/030-static-graph-dispatch-planner.md#i3c-lock-safety-repair-plan--2026-10-19).

## 2026-10-19 - Windows tooling launch review (local checks passed)

Contract launchers and formatter-test Git helpers now request hidden Windows
children. Root tools-test and verify use a direct Python launcher, preferring the
workspace interpreter and bypassing pyenv batch shims on Windows. The inspected
third-party graph runtime still starts Git without Windows creation flags; exact
ownership of the reported CMD tab is unconfirmed because process-tree access is
denied. No global configuration or installed package changed. The owner-approved
four-check gate passed (`WINDOWS-TOOLING-20261019`): changed-file formatting, 14
tooling tests through the new Python launcher, 7 CI-policy tests and 9 formatter
tests. Contract generation/breaking commands and full verify orchestration were
not executed; their launch flags remain statically inspected. Controlled owner
observation found no flash when Nx Cloud was disabled for the lightweight target.
Recorder Windows child defaults now disable Cloud/native runner/TUI, preserving
explicit overrides and leaving repo configuration/non-Windows CI unchanged.
Three new tests cover environment defaults, overrides and non-Windows behavior.
This does not claim every third-party tool or direct launch path is flash-free.

## 2026-10-19 - I3.c symbol-upsert membership review fix (local gate passed)

V13 now validates prepared parent ownership before the legacy NULL-run exemption,
rejecting unlinked child inserts and legacy row rebinding. Symbol-upsert keeps
writing symbol data but creates no new SYMBOL executions under prepared parents;
the frozen EXCHANGE children retain worker-status ownership. Legacy/untracked
upserts keep their existing behavior. Added SQL bypass, consumer retry, legacy,
empty-batch and failure regression cases. Renewed local format/test/coverage/clean
build passed: 346 tests across 56 suites, zero failures/errors/skips. Upsert behavior
has 100% line / 95% branch coverage; the preparation lookup has 100% / 100%.
No I3.d/I4 activation or status promotion. The preparation flag remains false.

## 2026-10-18 - I3.c bounded JobRun preparation (local gate passed)

Added gated target-path preparation, bounded producer fan-out and additive V13
execution/message closure. Dependencies are enqueued before the target in one
bounded transaction; retries reuse closed membership. The preparation flag remains
false; current scheduler/manual/FIFO/guard behavior stays active. No cron reduction,
manual-table removal, transport change or production activation is included.
The four-check local format/test/coverage/clean-build gate passed: 341 tests across
55 suites, zero failures/errors/skips, including 20 new PostgreSQL preparation
cases. Every critical changed component exceeds 80% line/branch coverage; SQL uses
an executed case matrix. I2/I3 statuses are unchanged. No CI/deployment or owner
attestation is claimed.
See [I3.c scope and gates](docs/plans/030-static-graph-dispatch-planner.md#i3c-bounded-preparation--2026-10-18).

## 2026-10-18 - I3.b JobRun persistence foundation (local gate passed)

Added forward-only V12 for JobRun requests, frozen scoped steps/predecessors and
nullable execution links, plus an inactive transactional snapshot repository.
Creation is bounded and atomic, request retries are idempotent, and sealed path
membership/request identity is protected from mutation. Snapshot reads use stored
identity/scope/membership rather than current static declarations. Legacy writers
retain NULL links; scheduled/manual, outbox, guard, Kafka and workers are unchanged.
Manual-table removal and target-driven preparation remain I3.c/d work. New pure
unit, PostgreSQL Testcontainers and real-application JPA mapping tests passed:
310 tests across 52 suites, zero failures/errors/skips, including 17 new cases.
Changed-file formatting and the dedicated Platform test, coverage and clean build
targets passed (`P14-I3b`: four required checks). The JaCoCo report shows critical
new Java components above 80% line/branch; SQL safety has explicit executed-case
mapping rather than numeric coverage. Clean build reran all 310 tests and produced
the application JAR. This is local evidence; no CI, deployment or owner attestation
is claimed. I2/I3 statuses are unchanged. See
[I3.b scope and gates](docs/plans/030-static-graph-dispatch-planner.md#i3b-persistence-foundation--2026-10-18).

## 2026-10-18 - Align tooling tests in CI and local verification

Added uncached `omni:tools-test` for Python tooling regression tests. CI sets up
Python 3.14.5 and runs this target with the existing CI-policy and formatter tests
before stack selection. Local `omni:verify` records all three tooling targets in
its required-check manifest, preserving the existing service/integration checks.
No Java format enforcement or production runtime behavior is introduced. Local
Nx execution passed 11 tooling tests, 7 CI-policy tests and 9 formatter tests;
format evidence is recorded under `TOOLING-WORKFLOW-20261018`. GitHub Actions
execution and a full `omni:verify` run are not claimed. This workflow/tooling
change is committed separately from I3.a.

## 2026-10-18 - Hide Windows verification child consoles

The check-result runner now requests `CREATE_NO_WINDOW` on Windows; the Gradle
and changed-file formatter launchers set Node's `windowsHide` option. Commands,
exit codes and log streaming remain unchanged. The runner regression expectation
includes the platform-specific creation flag. This targets console flashes from
these repository launchers; external launchers and CPU/RAM usage are unaffected.
The owner-requested sequential verification passed under
`WINDOWS-HIDDEN-CONSOLES-20261018` (required=4/pass=4): 11 Python tooling tests,
9 formatter routing tests, changed-file formatting including 13 Java files, and
293 Platform tests across 50 suites with zero failures/errors/skips. This includes
the PostgreSQL startup Testcontainers test. Platform execution took about 2m50s;
three cached-context shutdowns each waited for a 30-second PostgreSQL connection
timeout after the database closed. That teardown issue remains unresolved.
The owner subsequently reported no visible CMD flashes during this run. UI lag
has not been measured independently. No runtime service, contract, persistence,
deployment or roadmap status changes are introduced.

## 2026-10-18 - Changed-file Java formatting support

Root `omni:format` now routes selected Platform Java source/test files through the
new uncached `platform:format` target. An isolated Gradle configuration pins
google-java-format 1.29.0 with JDK 21 and AOSP 4-space formatting; it does not extend
runtime/test classpaths or require a root Prettier upgrade. Import order, unused
imports, string literals and Javadoc are preserved. Selection retains staged,
unstaged/untracked, ignore/path boundaries and bounded JSON transport. New routing
tests cover unknown-parser retention, ownership, ignores, batching and failures.
The owner-requested root format run passed, including all 13 selected Java files
through `platform:format`; no compile/test task was invoked by formatting. An initial
run exposed a CLI flag spelling mismatch; it failed visibly and passed after using
the pinned formatter's supported `--skip-removing-unused-imports` flag. Evidence is
recorded under `OMNI-JAVA-FORMAT-20261018`. The subsequent owner-requested
`WINDOWS-HIDDEN-CONSOLES-20261018` run passed all 9 routing regression tests and
repeated changed-file formatting successfully. The earlier Prettier-only PASS
covered text files only. No full increment verification, Java format CI gate or
roadmap status promotion is claimed.
Developer/Platform/CI docs are synchronized. Existing architecture, ADR, flow,
Kafka/data-lake/database/deployment/index/agent guidance remain valid because no
runtime contract or documentation file inventory changes. See
[formatting scope](docs/development/001-where-to-change.md#formatting-scope).

## 2026-10-18 - Unused topology and Telegram compatibility adapters removed

Removed four topology JSON/list adapters and the old Telegram properties constructor
after inventory found only test callers for those entry points. Topology tests now
use typed declarations with JSON baseline parsing restricted to test source; the
three affected Telegram tests pass rateLimit explicitly with unchanged null/default
semantics. Active topology startup, canonical properties binding, transport, schemas,
seed config and dispatch/notification behavior remain unchanged in the intended scope.
The approved focused startup rerun passed (1 test, zero failures/errors/skips;
`P14-I3a-cleanup-20261018` required=1/pass=1). It compiles all migrated test callers
but does not itself execute those unit tests or close the full coverage/regression/build gate.
The subsequent owner-requested affected unit run passed **109 tests in 13 classes**,
zero failures/errors/skips, through `P14-I3a-unit-20261018` required=1/pass=1. It covers
topology/resolver, planner/backlog, seeds and related Telegram/notification tests;
no source repair was needed. Full regression/attributable coverage/build remain pending.
I2 remains `verification_pending`, I3 `pending`. See
[cleanup evidence](docs/plans/030-static-graph-dispatch-planner.md#unused-compatibility-adapter-cleanup--2026-10-18).

## 2026-10-18 - I3.a PostgreSQL Testcontainers startup test locally passed

Added `PlatformStartupPostgresTest` using the actual Platform application, real
Flyway migrations, ephemeral PostgreSQL and two successive application contexts.
It checks HTTP readiness, seeded identity/config/typed-edge parity, UUID stability,
and preservation of scheduling/activity/claim fields during reseeding after restart.
Scheduled jobs, Kafka listeners and outbound storage/notification work are isolated
by test-only wiring. Existing Testcontainers dependencies and Platform Nx targets
are reused; production code/configuration and Docker/Compose are unchanged.
The owner-approved focused `platform:test` run passed: 1 test, zero failures/errors/
skips, including both application boots. The separate narrow `check_result` gate
`P14-I3a-startup-20261018` reports required=1/pass=1. Initial cache/Cloud failures were
resolved by using the existing Gradle user cache and disabling Nx Cloud for the run;
no source repair was needed. This is isolated local startup evidence, not full
I3.a coverage/regression/build, CI, Docker-image or live-provider verification.
I2 stays `verification_pending`, I3 stays `pending`. See
[Plan 030](docs/plans/030-static-graph-dispatch-planner.md#i3a-testcontainers-startup-regression--2026-10-18).

## 2026-10-17 - P14-I3.a typed static dependencies, pre-cutover source

Owner requested documentation corrections and the bounded I3.a checkpoint.
`StaticDefinitionDependencies` now owns immutable definition-level predecessors;
`StaticJobTopologyProvider` constructs its existing topology API from this typed
source and `RequiredPathResolver` has a typed input with unchanged v1 output.
Legacy seed JSON and deprecated custom-catalog adapters remain for compatibility
and parity until the documented caller/removal gates pass. Added whole-catalog
edge/path parity, scope, strategy-isolation and invalid-declaration tests.
Test/coverage/build have NOT RUN; earlier I1/I2 PASS and attestation do not verify
these edits. I2 stays `verification_pending`, I3 stays `pending`; no I3.b schema,
JobRun activation, scheduler/outbox behavior, transport or migration change.
Corrected the current snapshot to I4 dispatcher safety / P13 dependency ownership
and the backfill index to the current Plan 028 hold/reuse boundary.
See [Plan 030](docs/plans/030-static-graph-dispatch-planner.md#i3a-pre-cutover-source--2026-10-17).

## 2026-10-17 - Changed-file workspace formatting (verification pending)

`nx run omni:format` now selects staged, unstaged and untracked files by default;
`--all` selects the Git workspace inventory. Both modes honor `.prettierignore`
and skip deleted/non-regular files. Prettier handles supported files; Python files
use their owner's Nx lint-fix configuration with an explicit file list. No automatic
staging. Added selection regression tests and their CI invocation; tests and the
new formatter have not yet been executed. Existing CI verification evidence remains
historical for its original scope. See [formatting scope](docs/development/001-where-to-change.md#formatting-scope).

Python full-project and changed-file fix pipelines now apply `check --fix-only`,
then format, then strict lint, so an unfixable E501 does not skip the formatter.
Remaining violations still fail the final check. The 88-column policy is preserved;
long comments/strings may require manual wrapping. This adjustment is not yet
verified by an executed lint/format command.

## 2026-10-17 - CI quality gate maintenance (verification pending)

CI source now passes immutable event-specific contract baselines and fails when
compatibility cannot be checked. Conservative cross-service selection supplements
Nx for backend/shared/configuration/persistence/tooling changes. Added a separate
Ingestor publication integration job, locked Python sync targets, an always-evaluated
`CI Quality Gate`, report uploads, timeouts and cancellation of superseded runs.
With explicit owner approval on 2026-10-17, the local `omni:ci-policy-test` target
passed 7 tests and `ingestor:test-integration` passed 2 tests, with zero failures/skips.
The policy rerun disabled unavailable Nx Cloud connectivity; integration used a
workspace-local uv cache and `UV_LOCKED=true`, reporting a pytest cache-write warning
and an Nx Cloud connectivity warning while exiting zero. No full verification or
Actions run is claimed; branch protection has not been inspected or changed.
Attributable coverage gating remains follow-up work. No roadmap increment status,
runtime contract or deployment changes. See [CI policy](docs/development/004-ci-quality-gate.md).

## 2026-10-17 — P14-I2 evidence and documentation reconciliation

Renewed local verification was executed on 2026-10-16 with explicit owner approval:
Platform test, coverage and clean build all passed through the existing check_result
gate (required=3, pass=3, fail=0, unknown=0, missing=0; exit-code evidence).
HEAD: `b50a1063d5aa13ff9eeb976a0dd122d7bc008603`, branch `WIP`.
Clean build executed 287 tests with zero failures/errors/skips, including 11 resolver
tests. Resolver coverage is 100% line / 95.83% branch; every relevant critical component
exceeds 80% line/branch. [Plan 030](docs/plans/030-static-graph-dispatch-planner.md#p14-i2-implementation-handoff--2026-10-15-locally-verified-verification-pending)
records the feature/source/test matrix, per-class coverage and artifact location.

This documentation-only update reconciles stale I2/I3/I4 descriptions in the plan,
roadmap, indexes and release summary: I2 static handoff, I3 JobRun preparation,
I4 guarded admission, I5 diagnostics, I6 graph-specific operations handoff. Earlier
backlog/cohort verification and owner attestation remain historical. No source,
contract, migration, runtime behavior or product decision changes; no new tests run
on 2026-10-17, owner attestation, CI, deployment or live-runtime pass is claimed.
I2 remains `verification_pending` and I3–I6 remain `pending`; this synchronization
does not itself promote completion or dependent readiness.

## 2026-10-15 - P14-I2 static definition-path handoff locally verified

Added a pure, inactive version-1 `RequiredPathResolver` and focused tests. It follows
individual seed definition dependencies using the verified I1 topology for validation
and deterministic ordering; ambiguous upstream nodes require explicit caller bindings,
including multiple selected definitions. Exact scoped capabilities and edge mappings
are mandatory, with unsupported modes rejected rather than silently expanding fan-out.
Bounded closure returns dependency-first scoped definition/node/predecessor paths only.
No default runtime capability, Spring bean, JobRun/schema, FIFO outbox, guard, claim,
Kafka, worker, manifest or storage change. Earlier backlog/planner scaffolding is retained
as a documented DEPRECATION CANDIDATE with callers/removal gates in Plan 030. Revised
P14-I2 remains `verification_pending`; I3 remains `pending`. Test, coverage and build are
PASSED through the approved `npx nx run platform:test`, `npx nx run platform:coverage`
and `npx nx run platform:build` gate. Compact conclusion: `PASS P14-I2 required=3
pass=3 fail=0 unknown=0 missing=0 sources=exit_code`. All 11 resolver tests passed;
attributable resolver coverage is 100% line / 95.83% branch, with coverage/test XML
preserved before the clean build. Plan 030 records the focused matrix and static audit.
No new owner attestation, CI, runtime or deployment is claimed. Historical backlog-only
PASS/attestation does not attest this revision. Existing owner changes are preserved.

The dated P14-I2/I3 scheduling and ownership statements in entries below are historical:
the canonical 2026-10-15 sequence is I1 topology → I2 static handoff → I3 JobRun
preparation → I4 guarded admission → I5 diagnostics → I6 operations handoff. Historical
PASS results and attestations are preserved without being promoted to current evidence.

## 2026-10-13 — Parent-based dependency discovery and deferred extraction

Clarified Plan 030 discovery: PENDING executions -> persisted parent groups -> parent execution's job definition -> dependency declarations -> existing static graph mapping. Completion still requires related upstream/child execution evidence beyond the pending discovery set. TD-012 now records deferred extraction into a generic immutable static dependency snapshot, with parity tests and one-authority cutover required; it is not a P14 prerequisite. No source, transport, schema, outbox status or runtime behavior is changed by this documentation update.

## 2026-10-13 — Execution-centered cohort planning revision

Owner clarified Plan 030: the static job-type DAG is a map populated from execution histories and existing parent–child membership by business group, business date and correlated run. Send remaining upstream work while earlier batches execute, then wait for all required upstream executions to succeed before advancing; BANKS and REAL_ESTATE remain independent inside one node. Existing dispatcher dependency guard retains dataset READY and exact-version checks. No new outbox processing status, execution DAG, or planner metadata evaluator is approved. Symbol-level grouping is a future TODO. Current outbox-only summary/planner must be reimplemented; P14-I2 remains verification_pending and P14-I3 pending. Existing checks/coverage/attestation are historical and do not verify the revised scope. This change updates planning/flow documentation only; no source implementation or executable verification is claimed.

## 2026-10-12 — P14-I2 scope correction awaiting renewed verification

Owner retained the original plan: added first-dispatchable selection on every root path, join deduplication, and explicit caller-supplied snapshot-age policy. Incomplete, too-old and future-dated snapshots yield no selections; the age limit is inclusive and must be positive. Focused branch/join and freshness-boundary tests were added but NOT RUN. P14-I2 is verification_pending; P14-I3 is pending. Earlier passing checks, coverage and owner attestation below apply only to the prior source and do not verify these corrections. Planner remains inactive; no contract, database, deployment or worker behavior changes.

## 2026-10-12 — P14-I2 snapshot planner verified

P14-I2 completed its approved local Platform test, coverage, and build gate: all three required checks passed. Attributable JaCoCo coverage for the new critical components was 95–100% line and 83.3–100% branch; the PostgreSQL backlog projection method reached 100% line and branch coverage. Owner attestation `OWNER_VERIFIED P14-I2 verified_by=tanlocit9`, recorded at `2026-10-12T00:07:37+07:00`, is bound to summary SHA-256 `afacc8b02a7216de87631fbab178207dbe860bb869c3e560858a0478a2902255`. P14-I2 is `completed`; its sole prerequisite is completed, so autonomous P14-I3 is `ready` with no active module conflict after the P8-I4/P9-I4 pause. The planner remains inactive. No CI, merge, deployment, production, query-load, stale-age rejection, or live-runtime proof is claimed; P14-I3 owns scoped candidate integration, freshness/fallback policy, and final guard/claim/fencing safety evidence. Shutdown-hook Hikari warnings remain observed, not resolved.

## 2026-10-11 — Java static-analysis and Sonar debt classification

Added TD-015 to record confirmed Java code-quality/tooling findings: one native JPA raw-list to `List<UUID>` warning, a broader native-query mapping inventory, three explicit unchecked-suppression boundaries, and Java 21/Lombok IDE constructor diagnostics that were narrowly replaced with explicit constructors where reproduced. Repository inspection found no SonarQube/SonarCloud plugin, configuration, declared Nx target, quality profile, baseline, credentials contract, or CI gate. Sonar status is therefore `NOT RUN / TOOLING UNAVAILABLE`, not passing or failing. TD-015 defines bounded adoption, remediation and closure criteria without blocking P14 unless a concrete correctness/security defect is established.

## 2026-10-11 — P14-I2 snapshot planner source implementation

Implemented the inactive P14-I2 Platform backlog snapshot and pure planner source. A bounded read-only PostgreSQL projection groups PENDING scheduler-outbox rows by persisted job-definition scope without loading payloads or changing claims. Immutable snapshots record capture time, completeness, and mutually exclusive dispatchable/retry-delayed/in-flight counts; truncated snapshots fail closed. The deterministic planner selects the first dispatchable node per root path, preserves multiple definition scopes per node, reconciles shared descendants once, allocates bounded round-robin quotas, and returns a caller-owned non-persisted root cursor for baseline no-starvation. Focused unit and PostgreSQL tests were added but not executed. Dispatcher, dependency readiness, claim/fencing, Kafka, manifests, storage, schemas, configuration, and worker behavior remain unchanged. P14-I2 moved from `ready` to `verification_pending`; P14-I3 remains pending.

## 2026-10-11 — P14-I1 static topology verified

P14-I1 completed its owner-approved local verification gate: `platform:test`, `platform:coverage`, and `platform:build` passed. Attributable JaCoCo evidence met the 80% line/branch threshold for every changed critical class; topology classes ranged from 93.3–100% line and 83.3–100% branch coverage, while seed/config synchronization classes reached 93.5–99.4% line and 91.3–100% branch coverage. Owner attestation `OWNER_VERIFIED P14-I1 verified_by=tanlocit9` is bound to summary SHA-256 `77c59cef1535dfda4cf3c84d581e6ca3a715ede604b2228e32b20ef6309a2ad4`. P14-I1 is `completed` and P14-I2 is `ready`. This is local verification only; no CI, commit, merge, deployment, provider, production, migration, load, or live-runtime evidence is claimed.

## 2026-10-10 — P14-I1 static topology source implementation

Implemented the Platform-local immutable static topology boundary from existing job-definition seed declarations. Stable kebab-case logical node keys map every `(source, jobType, cronExpr)` definition identity to its `JobType` node, including many definitions sharing one type. The topology validates unknown/unmapped/ambiguous definitions, duplicate/self edges, cycles and graph bounds; exposes deterministic roots, topological order, bounded ancestors/descendants in both directions, and Mermaid diagnostics. Focused unit tests were added but not executed. No Kafka/protobuf, manifest/readiness, dataset path, database, configuration, public API, planner, dispatcher, claim/fencing or worker behavior changed. P14-I1 moved from `ready` to `verification_pending`; P14-I2 remained pending until the approved verification gate completed.

## 2026-10-08 — Static Graph & DispatchPlanner epic planning

Owner promoted the bounded static-graph dispatch design into active Milestone 1 Plan 030 with pending stories P14-I1 topology/validation, P14-I2 snapshot-based pure planning, and P14-I3 dispatcher integration. Source and code-graph review confirmed reusable Platform dependency-registry/guard, candidate-service/repository, atomic claim/fencing, and seed-declaration boundaries; no P14 runtime implementation is claimed. Plan 028 may reuse P14-I1 topology without waiting for P14-I2/P14-I3. Plan 029 basic operations remain independently deliverable; only graph-specific presentation waits for P14-I2. Persisted graph, expanded provider policy, and advanced fairness remain deferred. Existing statuses and historical evidence were preserved; no build, test, lint, format, runtime verification, or commit was performed.

Owner then prioritized Static Graph as the active delivery path: P14-I1 is `ready`, followed by P14-I2 and P14-I3. P4-I3 is `superseded` rather than completed; TD-014 retains its unclosed evidence, and all dependency-aware READY/WAITING/BLOCKED, no-starvation, claim/fencing, retry, compatibility, migration, fallback, and runtime invariants transfer to mandatory P14-I3 acceptance. TD-013 retains unused blocked-job tracking and optional absent evaluators; TD-007 continues to own executor/admission/load risk. On 2026-10-10, P8-I4 and P9-I4 were paused from `in_progress` to `pending`, preserving source/evidence/acceptance while handing off active `apps/core` ownership to P14-I1. No source implementation, executable verification, or completion claim is added by these scheduling decisions.

## 2026-10-07 — Field/DTO impact review and bounded tasks

Reviewed delivery-bearing plans 011/012/013/014/021/022/023/024/027/028/029 against declared contracts and canonical scheduling. Added cross-plan field/DTO inventory, change-kind/impact/behavior tables and bounded task boundaries; candidate types/names/transport remain unresolved until source/contract reconciliation. Phase 13 task splitting preserves all IDs/dependencies/acceptance criteria and isolates startedAt semantic cutover from additive timing and read UI. Idle observed-duration remains a proposal, not an authoritative field contract. Corrected navigation classifications and stale reactivation wording. No runtime implementation, verification, status promotion or schema approval is claimed.

This root-level record replaces the former roadmap execution ledger and the dated
status-reconciliation note. It records durable delivery and verification milestones;
current scheduling metadata remains canonical in
[`docs/plans/roadmap/implementation-increments.md`](docs/plans/roadmap/implementation-increments.md).

## Evidence semantics

- `completed` means the increment met the evidence rules applicable when it was
  accepted. Current verification policy additionally requires cross-service blast-radius
  reconciliation and attributable coverage for changed and directly impacted behavior.
- `source present` and `locally verified` do not imply CI, deployment, provider, or
  production verification.
- Pull requests, commits, and CI are traceability evidence unless an increment declares
  them as explicit delivery gates.
- Historical entries are not current scheduling metadata and do not override the
  canonical increment registry.

## Planning revision — 2026-10-07

Owner requested P4-I3 verification then P13-I1/P13-I2/P13-I4 before the remaining MVP evidence queue. P13-I4 no longer waits for Data Health. P13-I3 caches bounded scan runs/results in Query Service memory only, with TTL, size limits, exact-version keys, refresh, and explicit cache-loss behavior. Calendar/lifecycle classifications retain an owner-decision gate. No runtime implementation, test pass, or completion-status promotion is claimed.

## Debt review and follow-up deferral — 2026-10-07

Owner moved P10/P11/P12 follow-ups into owner-gated technical debt; existing evidence statuses remain unchanged. Reviewed TD-001 through TD-010 against targeted source, kept TD-006 historical/closed as written, recorded partial resolution in TD-008, and added TD-011 for feature deferral. Added source-local TODO references only at confirmed current gaps and a priority index/Mermaid source. Startup Ingestor getmany() drops returned batches in source; potential runtime loss remains unverified. No executable checks or runtime behavior changes are claimed.

## Current delivery snapshot

| Area                                     | Current state                                                 | Evidence boundary                                                                                                                                     |
| ---------------------------------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Scheduler claim and transactional outbox | Completed through P1-I2                                       | PostgreSQL concurrency and publish-recovery evidence recorded                                                                                         |
| Execution identity hard cutover          | P1-I4 completed                                               | Exact-head CI and migration harness were recorded; no production cutover claimed                                                                      |
| Canonical sector writer                  | P1-I3 `verification_pending`                                  | Source and earlier local/CI evidence exist; current impact-to-test and attributable coverage evidence is incomplete                                   |
| Proto contract foundation                | P2-I1 completed; later migration superseded                   | Initial schemas and Buf checks recorded; superseded work requires owner reactivation                                                                  |
| Dataset date normalization               | P3-I4 completed                                               | Local project checks and exact-head CI recorded                                                                                                       |
| Dependency guard                         | P4-I1/P4-I2 completed; P4-I3 `superseded`                     | Source baseline retained; TD-014 preserves unclosed evidence and P14-I4 owns final planner-integrated safety proof                                    |
| Console job operations                   | P7-I1–P7-I3 completed                                         | Platform/Console checks and CI recorded                                                                                                               |
| Notifications                            | P8-I1/P8-I2/P8-I5 `verification_pending`; P8-I4 `pending`     | P8-I4 source/local evidence retained; paused to release active apps/core ownership for P14                                                            |
| Intraday EOD                             | P9-I1 `verification_pending`; P9-I4 `pending`                 | P9-I4 source/local evidence retained; paused until dependencies and post-P14 ownership permit resumption                                              |
| Realtime foundation                      | P10-I1/P10-I2 `verification_pending`; P10-I0/P10-I3 blocked   | Provider-independent local checks exist; live VCI capability/runtime evidence is absent                                                               |
| Cross-service observability              | P11-I1–P11-I5 pending                                         | Deferred, owner-gated under TD-011; historical dependencies retained                                                                                  |
| Worker throughput and writer batching    | P12-I1–P12-I4 pending                                         | Deferred under TD-009/TD-011; preserve evidence and proposed dependencies                                                                             |
| Operator trust Console                   | P13-I1–P13-I4 pending                                         | P13-I1 follows completed P14-I4/P7-I3 for truthful final dispatcher stages; calendar/lifecycle evidence or narrowed classifications remain unresolved |
| Static Graph & DispatchPlanner           | P14-I1 completed; P14-I2 verification_pending; P14-I3 pending | Renewed static-handoff test/coverage/build PASS on 2026-10-16; docs reconciled 2026-10-17; no runtime activation/status promotion                     |

## Release history

| Date          | Scope                       | Result                                      | Durable evidence and limitations                                                                                                                                                                                                                                   |
| ------------- | --------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 2026-08-12    | P0-I1, P0-I2, P1-I0, P1-I1  | Completed                                   | PR #7 and successful CI established due-query, workspace, ADR, and claim foundations.                                                                                                                                                                              |
| 2026-08-13    | P1-I2                       | Completed                                   | PR #8; PostgreSQL scheduler/outbox concurrency and publish-recovery tests passed.                                                                                                                                                                                  |
| 2026-08-14    | P2-I1                       | Completed                                   | PR #9; Buf format, lint, build, breaking, generation, and artifact checks passed.                                                                                                                                                                                  |
| 2026-08-20–23 | P3-I1/P3-I2                 | Historical local evidence; later superseded | Manifest and MinIO checks were recorded, but failed external CI and incomplete gates prevented completion before the MVP deferral.                                                                                                                                 |
| 2026-08-25    | P4-I1/P4-I2 and P7-I1–P7-I3 | Completed                                   | Platform/Console verification and CI run #149 passed.                                                                                                                                                                                                              |
| 2026-08-25    | P3-I4                       | Completed                                   | Date normalization and READY-last rewrite checks passed locally and in exact-head CI run #154.                                                                                                                                                                     |
| 2026-08-29    | P1-I4                       | Completed                                   | Schema-only migration harness, owning-project checks, and exact-head CI run #33095851356 passed; deployment/production cutover was not claimed.                                                                                                                    |
| 2026-09-05–09 | P8-I1/P8-I2                 | Locally verified; `verification_pending`    | Platform test/build evidence and focused regressions were recorded; live Telegram and current attributable coverage evidence remain incomplete.                                                                                                                    |
| 2026-09-06–22 | P8-I4                       | Source present; `in_progress`               | Analyzer, Platform, Query Service, and Console local checks passed; dependency and current coverage gates remain.                                                                                                                                                  |
| 2026-09-10–12 | P9-I1/P9-I4                 | Source present; evidence incomplete         | Owning-project local checks passed; provider, MinIO/runtime, deployment, and production evidence were not established.                                                                                                                                             |
| 2026-09-12–13 | P10-I1/P10-I2               | Locally verified; `verification_pending`    | Strict tick, replay, archive, rebuild, bars, and reconciliation checks passed in py-common; configured publication and runtime evidence remain absent.                                                                                                             |
| 2026-09-19–27 | P8-I5                       | Source present; `verification_pending`      | Durable notification outbox, retry, fencing, and manual handoff source passed shared Platform test/build; migration and live-provider evidence remain incomplete.                                                                                                  |
| 2026-09-26–27 | P4-I3                       | Source present; `verification_pending`      | Dependency-aware outbox source and focused tests passed shared Platform test/build after repairs; attributable coverage and migration/runtime evidence remain incomplete.                                                                                          |
| 2026-09-21    | Active MVP ordering         | Owner-approved ordering                     | P8-I1 → P8-I2 → P8-I4 → P8-I5 → P9-I1 → P9-I4 → P4-I3 → P1-I3. No status was promoted by this ordering decision.                                                                                                                                                   |
| 2026-10-06    | Phase 13 operator trust     | Owner-approved planning scope               | Added pending P13-I1–P13-I4 for truthful stages/daily throughput/outbox publish ETA, manual EOD Data Health, and fixed Console order. P13-I1 now gates P12-I1; no implementation or verification is claimed.                                                       |
| 2026-10-08    | Plan 030 / P14-I1-P14-I3    | Owner-approved active epic                  | Promoted static topology, snapshot planning, and dispatcher integration into separate pending stories. Plan 028 waits only for P14-I1 when using topology; Plan 029 graph presentation waits for P14-I2. No status promotion or runtime implementation is claimed. |
| 2026-10-10    | P14-I1 static topology      | Source present; `verification_pending`      | Added immutable topology, seed-derived mappings/edges, validation, traversal, diagnostics and focused tests. Executable checks and attributable coverage were not run; P14-I2 remained pending.                                                                    |
| 2026-10-11    | P14-I1 verification         | Completed; P14-I2 promoted to `ready`       | Local Platform test/coverage/build passed; per-class coverage exceeded 80% line/branch; owner attestation recorded. No CI/deployment/runtime claim.                                                                                                                |
| 2026-10-11    | P14-I2 snapshot planner     | Source present; `verification_pending`      | Added inactive bounded projection, completeness-aware snapshot, pure planner, root cursor and focused tests. Executable checks and attributable coverage were not run.                                                                                             |

## Reconciled architecture decisions

### Execution identity

The P1-I4 cutover uses required generic scheduler persistence fields `workType` and
`workKey` without replacing semantic domain fields in commands. Domain fields such as
`symbolKey`, `sectorKey`, and `exchangeKey` remain domain inputs. The cutover requires
drain/snapshot/manual-history handling and does not authorize a compatibility bypass.

### Dataset publication

Immutable version artifacts are published and validated before replacing `READY.json`.
Failures preserve the previous READY pointer, and dependency approval uses exact
`dataVersion` lineage where required.

### Dependency enforcement

P4-I1/P4-I2 established dependency policies and scheduler enforcement. P4-I3 moves the
final READY/WAITING/BLOCKED decision to claimable outbox dispatch while preserving
fencing, no-attempt WAITING behavior, terminal BLOCKED behavior, and approved input
lineage.

### Deferred and superseded work

The MVP audit superseded unfinished Proto migration, expanded manifest/metadata work,
portable deployment hardening, broad Console/query expansion, P8-I3, P9-I2/P9-I3,
and P9-I5. P10 live provider discovery/runtime remains blocked. These items require an
explicit owner decision before reactivation.

## Where to look next

- Current phase/increment status: [`docs/plans/roadmap/implementation-increments.md`](docs/plans/roadmap/implementation-increments.md)
- Compact roadmap: [`docs/plans/roadmap/README.md`](docs/plans/roadmap/README.md)
- Verification and automation policy: [`docs/plans/roadmap/automation-rules.md`](docs/plans/roadmap/automation-rules.md)
- Cross-phase definition of done: [`docs/plans/roadmap/cross-phase-rules.md`](docs/plans/roadmap/cross-phase-rules.md)
- Supporting plan index: [`docs/README.md`](docs/README.md)

## Maintenance rule

Append only meaningful implementation, verification, release, deferral, and owner
policy milestones. Do not duplicate the full increment registry here, and do not use
this file to change status, dependency, or execution order. Update the canonical
registry first, then add a concise historical release note when durable evidence or a
material decision changes.
