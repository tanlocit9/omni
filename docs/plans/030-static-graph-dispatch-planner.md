# Plan 030 — Static Graph & DispatchPlanner

Status: Owner-approved active epic in Milestone 1. Canonical story status, dependencies, readiness, and execution order belong to the [increment registry](roadmap/implementation-increments.md). This planning document does not claim that the runtime implementation exists.

Owner decisions recorded: 2026-10-08; scope clarified on 2026-10-13.

## Historical execution-cohort revision — 2026-10-13 (superseded by 2026-10-15 canonical sequence)

This section supersedes the earlier backlog-only selection design, not the historical verification evidence below. The static DAG remains a map of job types; executions populate each node with independent business-group/run/business-date scopes. BANKS and REAL_ESTATE can progress independently inside the same logical node. No outbox processing-success status, execution dependency DAG, new manifest evaluator, or workflow engine is introduced.

Completion ordering is based on persisted execution parent–child evidence for the matching cohort. Dataset readiness and exact input versions remain exclusively evaluated by the existing DependencyRegistry/DependencyGuard before each candidate claim. Execution SUCCESS is not dataset READY. JobScheduler prepares due execution/outbox intent; SchedulerOutboxDispatcher is the existing guard/claim/publish boundary.

Current source is not yet compliant: DispatchScope groups by definition UUID; the outbox-only summary loses published-but-running work; the planner can descend past an upstream node with no dispatchable work even when its executions are unfinished. P14-I2 remains verification_pending with reimplementation required; P14-I3 remains pending. Earlier PASS/owner attestation is historical, not evidence for this revision. P14-I1 remains completed: reuse its verified static graph and validate the new runtime population mapping in P14-I2 rather than reopening historical topology verification.

## Goal

### Current architecture — 2026-10-15

Provide one validated static DAG that accepts a **target job definition** and resolves all required upstream jobs. Persist each requested workflow as a single **JobRun** with immutable planned dependency path. Prepare/enqueue dependency messages before downstream/target messages; the existing FIFO outbox/DependencyRegistry pipeline remains unchanged in I3. I4 enables **JobRun-aware** selection based on persisted step completion, not merely enqueue order, while preserving guard and fenced claims. I5 measures throughput/query behavior; I6 supplies the read-model handoff to Plan 029.

**The target/entry job is often a DAG sink, not a root node in graph theory.** “Reduce job definitions” means reduce separately cron-scheduled **entry targets**; ancestor definitions, producers and configurations remain. Existing jobs may remain independently triggerable where operationally required.

Current stories and statuses are defined solely by the [canonical I1–I6 story sequence](#canonical-revised-delivery-sequence--2026-10-15). Requirements elsewhere labeled historical must not override it. No runtime implementation, migration, benchmark or test execution is claimed by this documentation revision.

## Outcome

A reusable static definition-path handoff in I2, followed by durable JobRun preparation
in I3 and guarded runtime admission in I4. Current I2 has approved local test, coverage
and build evidence; no new owner attestation, deployment or increment completion is asserted.

## Historical Source Baseline and Gaps (pre-2026-10-15)

The following baseline/gap inventory records the initial 2026-10-08 design. Current source and story boundaries are described by the canonical 2026-10-15 sequence and I2 handoff below; the 2026-10-13 cohort revision is historical. Do not interpret initial missing-topology claims as current implementation absence.

Source inspection and code-review-graph analysis establish the following planning baseline. Source presence is not implementation or verification evidence for P14.

| Area                       | Reusable source capability                                                                                                                                                                                                                                                                                                                              | Gap owned by this epic                                                                                                                                        |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Dependency declarations    | [`JobDefinitionConfig`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/constants/JobDefinitionConfig.java) already seeds `dependsOnJobs`, `dependsOnDatasets`, and produced datasets.                                                                                                                                                | No shared typed topology boundary, stable logical node mapping, validation report, deterministic traversal, or topology export exists.                        |
| Job readiness              | [`PlatformDependencyRegistry`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/PlatformDependencyRegistry.java) adapts [`JobDependencyGuard`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/dependencies/JobDependencyGuard.java) into READY/WAITING/BLOCKED decisions with approved input versions. | Planner integration must preserve this boundary and must not reproduce manifest, exact-version, or dependency-mode evaluation.                                |
| Candidate loading          | [`SchedulerOutboxService.findCandidates()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/SchedulerOutboxService.java) loads bounded candidate IDs and materializes execution/work identity.                                                                                                                               | There is no node/scope backlog projection or selected-scope candidate query. Current loading parses payloads before planning.                                 |
| Candidate order            | [`SchedulerOutboxRepositoryImpl.findPendingCandidateIds()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/SchedulerOutboxRepositoryImpl.java) selects eligible unclaimed PENDING rows FIFO by availability/creation/ID.                                                                                                | No pending/dispatchable/in-flight snapshot grouped by topology node and scope, no top-down selection, and no quota result.                                    |
| Dispatch safety            | [`SchedulerOutboxDispatcher.dispatchBatch()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/SchedulerOutboxDispatcher.java) over-fetches, invokes the registry, claims READY rows atomically, and preserves publish retry fencing.                                                                                                  | The planner is not integrated before candidate fetch. Integration must retain all guard and claim semantics and provide a safe fallback to current selection. |
| Stable definition identity | [`JobDefinition`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/JobDefinition.java) has a UUID plus source/type/cron uniqueness, while several definitions can share one `JobType`.                                                                                                                                        | P14-I1 must freeze a stable static logical node identity and deterministic definition mapping without introducing the deferred persisted graph migration.     |

The graph impact radius is Platform-centered: dispatcher, outbox service/repository, dependency registry/guard, job-definition seeding, producers, job service, blocked-job compatibility, PostgreSQL scheduler tests, configuration, and operations are directly or transitively relevant. Analyzer, Ingestor, Query Service, Console, shared transport contracts, dataset writers, manifests, and storage paths have no runtime change in this epic unless a later story explicitly changes a contract; their no-impact boundaries must still be reconciled during verification.

## Historical Legacy Readiness and Disposition Review (pre-2026-10-15)

Reviewed on 2026-10-08 against Plans 001, 005, 006, and 023; TD-005, TD-007, and TD-012; current Platform scheduler/dependency source; database migrations; and code-review-graph relationships. This is a planning classification, not executed verification and not a status promotion.

Classification meanings:

- **MUST COMPLETE** — a canonical dependency or safety contract that must close before the named P14 story can activate;
- **PENDING EVIDENCE** — source exists, but required impact/coverage/runtime evidence is incomplete;
- **MISSING IN P14 SCOPE** — behavior does not exist and is intentionally delivered by a P14 story rather than treated as prior debt;
- **TECHNICAL DEBT / MOVE ON** — useful or corrective follow-up that is not required for the bounded P14 path when the stated guardrail is preserved.

| Legacy plan or source concern                                                             | Current evidence                                                                                                                                                                                                | Classification                                                                     | P14 gate                                                            | Disposition                                                                                                                                                 |
| ----------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P4-I2 dependency policy/enforcement foundation                                            | Canonically `completed`; static dependency declarations and manifest guard exist.                                                                                                                               | MUST COMPLETE — already satisfied                                                  | P14-I1                                                              | No further Phase 4 implementation prerequisite. Preserve P4-I2 semantics.                                                                                   |
| P4-I3 dependency-aware outbox dispatch                                                    | Source and focused tests are present, but the old increment is superseded because P14-I3 will materially change the dispatcher path. Its unclosed evidence is retained in TD-014.                               | TECHNICAL DEBT / SAFETY TRANSFER                                                   | No separate gate                                                    | Reuse the source as baseline; prove all transferred safety invariants against final P14-I3 behavior rather than completing the old path first.              |
| Stable logical node identity and unambiguous definition mapping                           | `JobDefinition` has UUID plus source/type/cron uniqueness; multiple definitions can share one `JobType`; no static node-key abstraction exists.                                                                 | MISSING IN P14 SCOPE                                                               | P14-I1 acceptance                                                   | Implement in P14-I1. Stop if mapping requires persisted identity or a product decision.                                                                     |
| One shared topology declaration, validation, traversal, and export                        | `dependsOnJobs` is seeded as untyped config/traceability metadata; no typed topology owner or validation exists.                                                                                                | MISSING IN P14 SCOPE                                                               | P14-I1 acceptance                                                   | Implement in P14-I1, preserving dataset readiness as a separate guard concern.                                                                              |
| Backlog snapshot and node/scope/quota planner                                             | Current repository performs bounded FIFO candidate selection and payload materialization; no grouped snapshot or planner exists.                                                                                | MISSING IN P14 SCOPE                                                               | P14-I2 after P14-I1                                                 | Implement in P14-I2 without activating runtime dispatch.                                                                                                    |
| Scoped candidate query and planner fallback                                               | Current dispatcher over-fetches FIFO candidates, evaluates the registry, and atomically claims READY rows.                                                                                                      | MISSING IN P14 SCOPE                                                               | P14-I3                                                              | Implement after P14-I2 and prove the transferred TD-014 safety contract on the final path. Preserve FIFO fallback.                                          |
| Legacy `blocked_jobs` table and `BlockedJobTracker`                                       | V7 table/entity/service exist, but current `JobScheduler` no longer calls the tracker and graph analysis found no caller of the tracker class; scheduler-outbox WAITING/BLOCKED now owns active dispatch state. | TECHNICAL DEBT / MOVE ON                                                           | None for P14-I1/P14-I2; no runtime dependency for P14-I3            | Do not integrate it into topology/planning. Track non-destructive decommission/schema-retention review separately; never delete audit state as part of P14. |
| Missing `PARTITION_MATCH`, `SUPPORTED_SCHEMA_VERSION`, and `MAX_FRESHNESS_LAG` evaluators | Enum/config parameter support exists, but no evaluator is registered; unsupported configured conditions fail closed as dependency errors. Current explicit P4-I2 enforced path uses EXISTS/READY.               | TECHNICAL DEBT / MOVE ON unless a selected active job requires one                 | None for current P14 scope                                          | Keep out of P14. Promote a bounded guard-contract story only before enabling a declaration that uses one of these conditions.                               |
| Async dependency evaluation lifecycle/admission                                           | Virtual-thread parallel evaluation source exists; executor shutdown, downstream admission bounds, and representative load evidence remain open in TD-007.                                                       | TECHNICAL DEBT / MOVE ON for P14-I1/P14-I2; operational activation risk for P14-I3 | Reassess before P14-I3 load/activation evidence                     | Keep TD-007 separate. Planner must not increase guard concurrency or claim a capacity improvement.                                                          |
| Empty-output versus execution-success semantics                                           | TD-005 confirms execution SUCCESS does not prove dataset completeness; guard readiness remains manifest/version based.                                                                                          | TECHNICAL DEBT / MOVE ON                                                           | None if planner never interprets SUCCESS/backlog emptiness as READY | Preserve the explicit rule that topology position, empty backlog, and execution SUCCESS are not data-readiness evidence.                                    |
| P1-I3 canonical sector writer evidence                                                    | `verification_pending`, but it does not provide scheduler topology identity, candidate selection, or claim safety and is not a P14 dependency.                                                                  | TECHNICAL DEBT / MOVE ON relative to P14                                           | None                                                                | Retain its independent evidence queue; do not couple P14 delivery to sector-writer completion.                                                              |
| Persisted topology and Plan 028 graph migration                                           | Proposed and unscheduled; P14-I1 intentionally owns static topology only.                                                                                                                                       | TECHNICAL DEBT / MOVE ON                                                           | None                                                                | Keep deferred. If promoted later, require parity and one-authority cutover behind the P14 topology boundary.                                                |
| Provider-aware policy, adaptive quotas, and advanced fairness                             | No approved requirement or measured need for P14 baseline.                                                                                                                                                      | TECHNICAL DEBT / MOVE ON                                                           | None                                                                | Keep deferred under TD-012/Plan 030 deferred scope. Baseline deterministic bounded allocation remains sufficient.                                           |

### Historical move-on decision — 2026-10-13 (superseded)

- **P14-I1 is completed** after its approved local Platform test/coverage/build gate, attributable coverage reconciliation, and owner attestation. P4-I2 is completed and no unresolved old-plan item is a canonical dependency.
- **P14-I2 is verification_pending** with execution-centered cohort reimplementation required by the 2026-10-13 owner decision; earlier checks and attestation apply only to historical source. Freeze authoritative group/date/run correlation and membership closure before implementing the revised projection and planner. The planner remains inactive.
- **P14-I3 is pending** until corrected P14-I2 passes renewed verification. It owns the final integrated dependency-aware dispatcher proof. TD-014 safety invariants are mandatory acceptance, while a separate P4-I3 completion is not required. Re-evaluate TD-007 only if measured integration/load evidence makes it relevant.
- Moving residual work to technical debt means retaining explicit activation triggers and safety boundaries; it does not mean marking the work complete, deleting legacy state, or weakening P14/P4 acceptance criteria.

## Historical Architecture and Responsibility Boundaries (pre-2026-10-15)

```mermaid
flowchart TD
    Definition[Static dependency declarations] --> Topology[Shared static topology]
    Topology --> Backfill[Plan 028 dependency and backfill trace]
    Topology --> Planner[DispatchPlanner]
    Executions[Executions and persisted parent membership] --> Snapshot[Execution cohort summary and outbox eligibility]
    Snapshot --> Planner
    Planner --> Selection[Node scope quota selections]
    Selection --> Query[Scoped candidate query]
    Query --> Registry[DependencyRegistry]
    Inputs[Manifest and exact version evidence] --> Guard[DependencyGuard]
    Registry --> Guard
    Guard --> Decision[READY WAITING BLOCKED]
    Decision --> Dispatcher[SchedulerOutboxDispatcher]
    Dispatcher --> Claim[Atomic claim and fencing]
    Claim --> Publish[Kafka publish and fenced outcome]
```

| Component                            | Owns                                                                                                                      | Must not own                                                                                       |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Static topology                      | Stable node identity, directed edges, definition mapping, roots, deterministic traversal, validation, diagnostics/export. | Runtime backlog, manifest readiness, claim state, provider quotas, persisted dependency authority. |
| DispatchPlanner                      | Selection of node/scope/quota from one bounded snapshot using top-down traversal and baseline bounded allocation.         | Dependency readiness, upstream enqueue, payload parsing, SQL construction, claims, publication.    |
| DependencyRegistry / DependencyGuard | Required input scope, dependency mode, manifest readiness, exact `dataVersion`, READY/WAITING/BLOCKED decision.           | Cross-node priority, dispatch quota, backlog fairness.                                             |
| SchedulerOutboxDispatcher            | Orchestration of plan, scoped candidate fetch, guard decision, atomic claim, publish, and fenced outcome.                 | Duplicate topology or manifest policy.                                                             |
| Repository/service                   | Parameterized snapshot and candidate projections plus existing atomic state transitions.                                  | Scheduling policy or raw-SQL output from the planner.                                              |

An empty upstream outbox backlog is neither execution-completion nor readiness evidence. A selected candidate can still be WAITING or BLOCKED. Snapshot values are advisory; the atomic claim must revalidate status, availability, lease, and fence identity. Parent relationships identify fan-out membership within a job step, not dependency edges across job steps. Match upstream and downstream parents through explicit cohort identity and the static topology, never by assuming their parent IDs are equal.

## Canonical revised delivery sequence — 2026-10-15

**This section supersedes the earlier 2026-10-13 and prior P14-I2/P14-I3 target scopes and acceptance language elsewhere in this file. Historical verification remains historical evidence, not the current implementation target.** The new architecture is **target-driven JobRun → static-DAG preparation → existing durable outbox → JobRun-aware publish admission**. The goal is not to implement a general workflow engine.

| Story                                                   | Status                 | Independently deployable output                                                                                                          | Runtime boundary                                                                          |
| ------------------------------------------------------- | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| **P14-I1** — Static topology                            | `completed`            | Existing validated immutable static DAG                                                                                                  | No dispatcher activation                                                                  |
| **P14-I2** — Static DAG dispatcher contract and handoff | `verification_pending` | Definitive topology-to-dispatch contracts, traversal/ancestor closure, mapping, validation, tests and handoff for I3                     | **No JobRun schema, outbox changes, runtime activation or dashboard SQL requirement**     |
| **P14-I3** — Unified JobRun + target DAG preparation    | `pending`              | Durable `job_runs` and DAG path/membership; prepare dependencies then target, enqueue messages in stable dependency-first order          | Existing FIFO outbox + existing DependencyRegistry guard/claims/publish behavior retained |
| **P14-I4** — JobRun-aware outbox planner and admission  | `pending`              | Bounded execution-centered JobRun snapshot; cohort barriers, quota/fairness, scoped candidate reads and atomic admission revalidation    | Planner activated with safe FIFO fallback, preserve fencing/retry and full TD-014 proof   |
| **P14-I5** — SQL diagnostics and performance evidence   | `pending`              | Versioned SQL diagnostics, `EXPLAIN (ANALYZE, BUFFERS)`, measured snapshot/claim/dispatch latency and metrics read-projection dictionary | No dashboard UI or new statistics store                                                   |
| **P14-I6** — Operations/read-model handoff              | `pending`              | Stable bounded JobRun/topology read contract and documentation/fixtures for Plan 029 graph-specific view                                 | API/read model only if actually required and approved; Plan 029 still owns dashboard UI   |

Dependencies are **I1 → I2 → I3 → I4 → I5 → I6** for new graph-specific capabilities. Basic Plan 029 operations/stage work remains separately scheduled. No current status is promoted by this documentation decision.

### P14-I2 — Finalize static DAG for dispatch use

Outcome: finish _existing_ P14-I2 (currently `verification_pending`) by narrowing it to a validated, reusable **static topology contract** for consumers including I3 and backfill. Reconcile existing P14-I1 graph source and WIP inactive planner scaffolding. Provide deterministic ancestor closure of a target, topological ordering, multiple roots/joins, stable definition mapping, and diagnostics. Define a versioned internal handoff interface such as `resolveRequiredPath(targetDefinition, scope)` returning **definition/node path only**, not runtime execution or message claims.

- No new execution-centered cohort completion logic, `job_runs` persistence, Kafka publication, SQL performance baseline or dashboard metrics under I2. Existing unfinished snapshot/planner classes may remain unused, be adapted atomically, or be marked explicitly as **DEPRECATION CANDIDATE** with caller inventory; never break builds or the current dispatcher.
- **Acceptance:** graph traversal/ordering tests, definition mapping with duplicate/missing/cycle detection, independent root paths, startup/build/regression verification, and an I3 integration contract; scheduler/outbox behavior unchanged. P14-I1 historical PASS remains valid, I2's earlier backlog-only PASS does not close this revised story.

#### P14-I2 implementation handoff — 2026-10-15 (locally verified, verification pending)

Owner approved definition-specific seed dependencies, I1 topology validation/order,
explicit caller bindings for multi-definition upstream nodes (one or many selected
definitions), and explicit scope capabilities/mappings. No runtime defaults are approved.

[`RequiredPathResolver`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/topology/RequiredPathResolver.java)
is a pure, non-bean internal API. Contract version 1 returns a target plus an immutable
ordered list of definition identities, node keys, effective scopes and direct scoped
predecessors. It reuses I1 validation and deterministic node order, but follows each
selected definition's own seed dependencies, not the union of all definitions at a node.
This prevents a non-confirmed signal target from inheriting confirmed-trend intraday work.
Within a node, definition identity then scope type/key/date determines order.

- Catalog and path are bounded at 1024 definitions/steps, graph at I1's 256 nodes/1024
  edges, depth at 64. Exceeding requested depth/size fails rather than returning a partial
  closure. Shared scoped predecessors deduplicate; longer join paths still enforce depth.
- Missing multi-definition bindings fail `AMBIGUOUS_DEFINITION`; unknown, empty, duplicate
  or wrong-node selections fail `INVALID_BINDING`. Single-definition nodes can resolve
  without a binding. Target identity remains explicit, never expanded to sibling targets.
- Version-1 scope is type (GLOBAL/EXCHANGE/SECTOR/SYMBOL), opaque exact key and businessDate.
  Key syntax is only nonblank/trimmed/length-bounded; the caller owns canonical source
  qualification and producer support evidence. These are not I3 durable RunScope fields.
  Every scoped definition needs an exact capability, and every traversed dependent→upstream
  pair needs an explicit effective-scope mapping, including identity mappings. Missing
  evidence fails `UNSUPPORTED_SCOPE`. No automatic narrowing, widening or producer fan-out
  changes; cross-date mappings are unsupported in v1. Capabilities are trusted approved
  caller declarations, not producer capability discovery or an authorization boundary.
- I3 must resolve the returned stable seed identities to current persisted UUIDs, validate
  approved bindings/capabilities against producers, deduplicate/reuse shared work safely,
  and persist the complete scoped membership with contract version. No execution, SQL,
  READY verdict, claim, transport payload, JobRun persistence or runtime bean is returned.
  No new target-driven route is activated; I4 admission remains separate.

Renewed local verification — 2026-10-16, documentation reconciled 2026-10-17:
HEAD `b50a1063d5aa13ff9eeb976a0dd122d7bc008603` on branch `WIP`, clean worktree
at execution baseline. The owner explicitly approved rerunning all three targets.
The agent executed them through the existing `tools/check_result.py` required manifest;
all returned exit code 0 and the compact conclusion remained PASS (3/3). Clean build
executed 287 tests with zero failures/errors/skips, including all 11 resolver tests.
The matrix below was rechecked against executed XML and per-class JaCoCo counters:
resolver 100% line / 95.83% branch; topology 99.23% / 94.83%; provider 100% / 100%;
definition identity 93.33% / 83.33%; node key 100% / 100%. Nested resolver records
have 100% line coverage and, where applicable, 100% branch coverage. All critical
components exceed the 80% line/branch gate. Compatibility rows are regression evidence,
not implementation of future JobRun, concurrency or runtime admission behavior.

Pre-clean and clean-build reports are preserved under ignored
`.agent/check-results/P14-I2/verification-mHSZ/` and its `clean-build/` subdirectory
(`jacoco/jacocoTestReport.xml`, `test-results/`); `source-binding.json` binds HEAD and
resolver source/test SHA-256 hashes. The directory name is an artifact identifier,
not a timestamp. Known shutdown Hikari/JPA and compiler warnings did not fail the gate.
No new owner attestation, CI, deployment, provider or live-runtime claim is made.
These results supersede the earlier resolver run as the latest local evidence;
backlog-only historical checks/attestation remain historical.

Earlier 2026-10-15 local evidence: `npx nx run platform:test`, `npx nx run platform:coverage`
and `npx nx run platform:build` all PASSED. Test and coverage were rerun after the
focused test repair. The compact conclusion read through `tools/check_result.py` is
`PASS P14-I2 required=3 pass=3 fail=0 unknown=0 missing=0 sources=exit_code`.
Coverage and executed test XML were preserved before the clean build under ignored
`.agent/check-results/P14-I2/coverage-preserved/` (JaCoCo report:
`jacoco/jacocoTestReport.xml`). The resolver suite has 11 tests, zero skips/failures/errors.

| Source / acceptance area                                                                                | Executed focused methods in RequiredPathResolverTest                                                                                                                          | Attributable evidence                                                                                                                     |
| ------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Definition-specific closure and immutable deterministic diamond order, unrelated exclusion              | `usesDefinitionSpecificDependenciesNotNodeUnion`, `resolvesDiamondDependencyFirstWithoutUnrelatedRootAndIsDeterministic`                                                      | PASS; resolver 83 covered / 0 missed lines, 46 covered / 2 missed branches (100% / 95.83%)                                                |
| Multiple roots and join closure                                                                         | `supportsMultipleRootsAtJoin`                                                                                                                                                 | PASS; same resolver counters                                                                                                              |
| Definition selection: ambiguous, empty, duplicate, unknown/wrong bindings and many selected definitions | `requiresExplicitMultipleDefinitionBindingAndAcceptsMany`                                                                                                                     | PASS; same resolver counters                                                                                                              |
| Reused validation: duplicate/missing definitions and cycles                                             | `reusesTopologyValidationForDuplicatesMissingDefinitionsAndCycles`; existing `StaticJobTopologyTest`                                                                          | PASS; StaticJobTopology 129/1 lines, 55/3 branches (99.23% / 94.83%)                                                                      |
| Explicit capabilities, mapped scopes, missing mappings and cross-date rejection                         | `supportsExplicitScopeMappingWithoutImplicitIdentityOrFanout`, `failsClosedOnMissingCapabilityMappingOrDateExpansion`                                                         | PASS; same resolver counters                                                                                                              |
| Catalog, nested record/scope validation and traversal/closure boundaries, including longer join depth   | `rejectsInvalidContractScopeCatalogAndTraversalBoundaries`, `enforcesClosureBoundsRatherThanReturningTruncatedPath`, `longerJoinPathCannotHideDepthOverflowAfterShorterVisit` | PASS; RequiredPath 6/0 lines, 6/0 branches; Scope 7/0 lines, 6/0 branches (all 100%)                                                      |
| Canonical seed strategy isolation                                                                       | `canonicalSignalStrategiesDoNotInheritConfirmedIntradayDependency`                                                                                                            | PASS; provider 43/0 lines, 12/0 branches (100%); JobDefinitionIdentity 14/1 lines, 5/1 branches; TopologyNodeKey 10/0 lines, 2/0 branches |
| Immutable handoff value records                                                                         | Closure/scope/validation methods above                                                                                                                                        | Handoff 9/0, StepKey 4/0, ScopeEdge 4/0, Step 5/0, ScopeType 1/0 lines (100%, no branches)                                                |
| Unchanged startup and scheduler/outbox FIFO/guard/claim/publish compatibility                           | Existing Platform startup/outbox suite in the approved test and clean-build gates                                                                                             | PASS as regression evidence, not new runtime activation or I3/I4 implementation proof                                                     |

All changed/directly impacted critical classes meet the default 80% line/branch gate.
Known Hikari shutdown and unchecked/deprecated compiler warnings were nonfailing.
No CI, runtime, deployment, provider or new owner attestation is claimed. The earlier
backlog-only owner attestation/hash remains historical and cannot attest this resolver.

**Historical scaffolding disposition: DEPRECATION CANDIDATE**, not annotated or deleted.
Static source caller inventory: `DispatchPlanner` has constructor/bean wiring and focused
`DispatchPlannerTest` callers, but no scheduler/dispatcher invocation. `BacklogSnapshotService`
has constructor/bean wiring and `BacklogSnapshotServiceTest` callers; its `capture` path
alone calls `SchedulerOutboxRepositoryCustom.findBacklogGroups`, implemented by
`SchedulerOutboxRepositoryImpl` and directly exercised by
`JobDefinitionClaimRepositoryPostgresTest`. `DispatchScope`, `BacklogCounts`,
`BacklogSnapshot`, `DispatchPlan` and `SchedulerBacklogGroup` support these historical
classes and `PlanningContractsTest`; they are not replacements for the new path contract.
Removal/adaptation gate: I4 defines the JobRun-backed replacement, re-inventory callers
including Spring wiring and repository interface/implementation, change tests atomically,
and obtain approved startup/build/regression evidence. No compatibility adapter or
`@Deprecated` migration is needed now because no existing API is being replaced/removed.

I2-specific cross-service reconciliation against the documentation index, system overview,
execution flow, ADR 007, Kafka/data-lake/database, deployment and ownership references:

| Surface                      | Revised I2 impact / explicit no-impact reason                                                                                                                                                                                                                                                                                                                        |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Platform                     | Additive pure topology resolver and focused tests only; I1 source and enabled scheduler, manual, producer, guard and outbox paths untouched. Graph pre-analysis of topology/provider/historical planner found BacklogSnapshotService within two hops, with truncation and 47 unresolved sites; static caller inspection supplements it, not proof from graph bounds. |
| Analyzer / Ingestor          | No worker, producer payload, Kafka handler, dataset writer or concurrency change; no runtime scope capability is asserted.                                                                                                                                                                                                                                           |
| Query Service / Console      | No endpoint, SQL read model, UI or dataset discovery change.                                                                                                                                                                                                                                                                                                         |
| Shared contracts / libraries | No protobuf/generated artifacts, Kafka schemas/topics, py-common or cross-service API change; new Java API is scheduler-local and has no runtime callers.                                                                                                                                                                                                            |
| Persistence / storage        | No migration, JobRun, query, index, outbox/history mutation, path ownership, Parquet/manifest, READY-last or exact-lineage change. Existing guards retain all readiness authority.                                                                                                                                                                                   |
| Configuration                | No environment/property/deployment setting or default bindings/capabilities added.                                                                                                                                                                                                                                                                                   |
| Tests                        | New focused resolver tests and previously existing Platform topology/startup/outbox regression were included in the **locally reported PASS** test/coverage/build gates documented above; this supports unchanged source compatibility but does not demonstrate runtime activation, deployment or I3/I4 behavior. Worker contract tests unaffected because transport is unchanged.                                                                                                                                                                                     |
| Operations                   | No activation, watcher, diagnostics SQL, benchmark or capacity claim. Roll back additive resolver without touching runtime or data.                                                                                                                                                                                                                                  |

Documentation disposition: Plan 030, increment registry and root release notes record this
source-present evidence. Existing architecture/flow/ownership and index documents already
describe inactive I2 handoff and I3/I4 ownership; no new endpoint, document, ADR decision,
data/deployment/service workflow or repository coding rule requires changes there.
AGENTS.md, CLAUDE.md and .roo guidance retain the existing verification/contract boundaries.
Plans 023/028/029 and TD-012 retain their established ownership; no schedule is promoted.
There is no execution-log or separate P14 phase file in the current roadmap directory;
this supporting plan and registry are the evidence surfaces, not a newly invented log.

P14-I2 remains `verification_pending`; I3 remains `pending`. Approved local checks and
attributable coverage are now recorded above. No new owner attestation is recorded;
local PASS alone does not promote completion or dependent readiness.

Documentation synchronization — 2026-10-17: the prior INCOMPLETE review is historical.
This revision reconciles current I2/I3/I4 ownership and renewed evidence in this plan,
the increment registry, roadmap summaries/diagram, documentation indexes and release
notes. Historical cohort/backlog sections retain their dated evidence and are explicitly
superseded; they are not new I2 acceptance requirements. Static audit outcome is recorded
below after post-edit graph analysis. No executable documentation checker is authorized
or run, and no new implementation check is claimed by this documentation edit.

Graph freshness limitation from the earlier untracked-file review is historical:
on 2026-10-16 the graph was updated to the HEAD above and indexed the resolver/tests.
Resolver impact analysis found eight changed nodes, no additional nodes within two hops,
and six unresolved call sites. Static caller inspection found only test callers;
reflection/AOP and bounded hops remain limitations, not proof of no cross-service impact.
The cross-service matrix above remains applicable: no worker transport, dataset/storage,
query/UI, persistence, shared configuration or operations activation change.

Documentation-area disposition: architecture, job flow and developer ownership retain
correct inactive-I2 / future-I3/I4 boundaries; no change needed. ADR 007 retains guarded
outbox authority. Kafka/protobuf, data-lake and database references need no update because
I2 changes no messages, manifests, paths, schema, queries or writes. Deployment docs and
service READMEs need no update because no runtime command, endpoint, environment or rollout
changes. AGENTS.md, CLAUDE.md and .roo rules retain the existing verification and contract
policies; no rule change needed. Plans 023/028/029 and TD-012 retain canonical ownership;
this synchronization changes no scheduling decision. No separate P14 phase or execution-log
file exists; the plan, registry and root release notes remain the evidence surfaces.
Status promotion and owner attestation are outside this documentation-only update;
I2 stays `verification_pending` and I3 stays `pending`.

Static document-consistency audit — 2026-10-17: **CONSISTENT for the bounded I2
synchronization scope**, not a repository-wide audit or increment-completion verdict.
Reviewed the seven edited documents against canonical governance, the P14-I1–I6
registry chain and P13-I1's I4/P7-I3 dependency, current architecture/job flow/ownership,
and the recorded 2026-10-16 evidence. IDs, statuses and dependencies are unchanged;
current summaries now match the canonical story boundaries. All ten mandatory plan
sections are present, with explicit no-output statements and contract/no-impact
boundaries. The directly changed Plan 030 links, handoff anchor, evidence paths and
canonical documentation references resolve by static path/heading inspection.
Historical test/attestation records remain dated and superseded, not current runtime
or CI proof. No unresolved finding remains in these changed claims; unrelated legacy
plan content and repository-wide links were not exhaustively audited. No executable
checker, test, lint, format or build was run for this documentation-only update.
Post-edit graph change detection covers the seven edited Markdown files; zero changed
code symbols/affected flows describes this documentation diff only, not behavioral
coverage or proof of global safety. Completion/readiness requires a separate status
reconciliation against the recorded acceptance evidence, not a new CI/runtime gate
or mandatory owner attestation inferred from this audit.

### Static dependency ownership cutover — P14-I3 first checkpoint (owner decision 2026-10-16)

**Plan 030 is the canonical owner** of migrating job-to-job dependency declarations out of `JobDefinitionConfig` seed `configJson.dataDependencies.dependsOnJobs` into one source-controlled, typed, **static definition-level dependency declaration**. This is **not** a PostgreSQL persisted edge graph and does **not** create a new generic workflow engine. The existing I1 static topology/provider is reused as the only graph API/authority; do not create a second topology registry competing with it.

- Declare edges against stable `JobDefinitionIdentity` (including distinguishing multiple `SYNC_SIGNALS` strategies), **not merely `JobType`**, nor any particular SYMBOL/SECTOR/GLOBAL scope. Explicit `RunScope` capability/edge mappings remain a separate resolver/run-preparation concern. Do not clone a definition per symbol.
- Retain seed identity, cron, producer parameters, dataset `dependsOnDatasets` / `producesDatasets` metadata, guard inputs, and operational fields; migrate **only job-to-job topology**. A job with no predecessors has an explicit empty dependency set. Keep seed-to-persisted UUID binding stable and deterministic.
- Adapt `StaticJobTopologyProvider` and `RequiredPathResolver` to read **the same single static declaration boundary**, preserving definition-specific ancestor closure, ordering, ambiguity checks, validation, fail-closed scope mapping and I2 contract v1 unless an independently approved contract version bump is necessary. Preserve the verified I2 tests and historical evidence; no current resolver owner attestation has been recorded.
- **Cutover sequence:** (1) inventory every seed and existing runtime/config reader of `dependsOnJobs`; (2) derive a read-only parity table against the current seed graph, including multiple definitions per JobType and confirmed-vs-nonconfirmed signal paths; (3) introduce typed static definition-level edges, change all applicable topology readers, and run startup/test parity with old and new graphs; (4) remove job-edge declarations from the seed config **only after no active runtime consumer depends on them**. Do not silently delete any still-used `dependsOnJobs` dataset/readiness guard metadata; use compatible adapters or an explicit deprecation/removal gate if required.
- The **pre-cutover implementation can be merged as an independently passing checkpoint of I3**, with legacy FIFO scheduler/outbox and dependency guard unchanged. A single activated owner must be established at cutover; parity-only legacy reads are not a second runtime authority. Rollback must restore source declarations and seed config safely, considering `JobDefinitionSeeder` startup upsert behavior.
- **Acceptance:** complete seed/definition/edge parity (including zero-edge nodes, multi-definition nodes, branch joins, sector configuration, symbol scope independence), startup seeding and resolver regression tests, no new persistence graph or schema, no change to runtime jobs/outbox/producer behavior, and documented caller/contract impact. If scope migration cannot pass unchanged runtime safety, keep the old path enabled and do not proceed to JobRun schema/activation.
- **Story order within P14-I3:** checkpoint **I3.a static definition-edge extraction** → **I3.b JobRun schema + frozen scoped path** → **I3.c target-root schedule/manual preparation + dependency-first enqueue** → **I3.d compatible cutover/decommission manual triggers**. These are checkpoints under one registered I3, **not additional roadmap increments**. I2 remains `verification_pending` and its current locally reported PASS evidence is not reclassified.

### P14-I3 — Unified JobRun and DAG preparation without outbox behavior change

Start with **I3.a static definition-edge extraction** above before adding JobRun persistence or enabling target-driven dispatch. All I3 acceptance gates also apply to the static declaration cutover.

One **`job_runs`** row is the durable logical request; `job_runs.id` is the authoritative UUID `jobRunId`. Modes: `SCHEDULED`, `MANUAL`, reserved `BACKFILL`. Record target job definition, a typed request scope (GLOBAL/EXCHANGE/SECTOR/SYMBOL-capable with explicit support gating), businessDate and optional businessGroup, request metadata/idempotency, path topology version/fingerprint if needed, lifecycle and failure reason. **Persist complete resolved ancestor + target path/membership** in an explicit stable bounded representation (e.g. run-step rows), linking `job_execution_histories` root/child executions to their run. Persist enough to reconstruct completed/unfinished predecessors after restart even if static declarations change. Do not infer from `triggeredAt` or legacy `runKey`. Use an approved additive schema/migration, but **no persisted second topology authority or arbitrary execution DAG**: run steps are an immutable instance of the static graph at run creation.

- **Definition reduction means reduce independently scheduled entry targets, not delete dependency definitions required by producers.** Keep executable definitions/configuration for all path steps. Mark only user/scheduler-selected **target roots** as independently triggerable; their ancestors may become dependency-only with no independent cron. Audit existing cron definitions before disabling any, prevent duplicate target/ancestor work, and preserve explicit standalone/manual scheduling where still required.
- Scheduled/manual requests both enter the JobRun creation boundary. Manual uses `job_runs` for request identity/audit/idempotency. **Drop `manual_job_triggers` and its V8-era runtime entity/repository usage after manual controllers/services have atomically cut over and integration tests pass; no historical-data migration is required or claimed by owner decision.** An already-applied Flyway V8 is never deleted/rewritten; add a forward migration that safely drops the obsolete table only when deployed app no longer reads it. Existing endpoint semantics/permissions remain compatible or receive explicit reviewed deprecation, not accidental 500s. Archive/drop audit data is an explicit intentional consequence.
- Materialize all approved required dependency + target executions and outbox messages **in DAG topological order** (dependencies before dependents), preserving per-parent one-time fan-out membership closure and zero-child behavior. The **full planned path** must remain visible in `JobRun` even if outbox preparation is chunked/retired; do not claim closure merely from a partial batch. Use transactional idempotency keys such as (run, step, work identity, business date) and bounded transactions; no N+1 fan-out expansion or unbounded single transaction. Retry/restart must never duplicate a logical step. Existing dataset readiness and exact-version rules apply before work can be considered successful.
- **I3 leaves `SchedulerOutboxDispatcher.findCandidates()`, FIFO selection, `DependencyRegistry`, WAITING/BLOCKED, atomic claim/fencing, retries and Kafka publish intact.** Dependency-first enqueue/FIFO is a _preference_, **not an execution completion guarantee**: an upstream may be PUBLISHED but not finished when downstream candidates are examined. If the existing guard cannot prove required execution completion for a new run path, **do not activate the new target-driven route in I3**; keep it behind a disabled-by-default feature flag or enforce a bounded guard-compatible safe admission rule. No silent correctness regression allowed before I4.
- Independent rollback/verification: no schema removal before cutover; old scheduled/manual runtime works during staging, then new target-run creation may be separately enabled after compatibility checks. PostgreSQL tests for referential integrity, commit/rollback, retry/crash, duplicate runs, multiple definitions and multiple dates; manual API and legacy outbox regression PASS.

### P14-I4 — JobRun snapshot, DispatchPlanner and outbox integration

Outbox planning now reads the persisted **JobRun path and run-step membership as authoritative planned work**, plus execution statuses (including published-but-unfinished) and separate outbox eligibility/lease aggregates. **No need to re-discover inter-step correlation from timestamp heuristics**. The static DAG validates path structure, but the immutable persisted JobRun instance fixes expected membership, join branches, and target. Snapshot queries are bounded and short-lived; topology is in-memory, runtime state is PostgreSQL-first. Do not add a long-lived authoritative binding cache; PostgreSQL snapshots remain the baseline. Any future cache optimization requires a new evidence-backed owner decision within the owning plan, not a pre-created debt epic.

- For each jobRunId and persisted resolved step scope, keep earliest unfinished dependency step eligible for remaining sends. Only advance past predecessor completion with **persisted closed membership and all required executions SUCCESS**; FAILED/BLOCKED/unknown/incomplete cannot open a barrier. At joins require every required predecessor. Do not conflate outbox PUBLISHED with worker completion; legacy execution RUNNING is preparation-time and is not evidence of worker start.
- DispatchPlanner returns typed step/run scope, quotas/order and diagnostic reason. Outbox queries only selected scopes; evaluate the existing `DependencyRegistry` for every candidate, then **revalidate cohort barrier at admission/claim**, preserve lease/fencing/retries/approved inputs and publish-ack semantics. Ensure fairness across independent roots/cohorts, no starvation on WAITING/BLOCKED/lost claims; add bounded observable fallback that **cannot bypass cohort barrier while enabled**. Explicit rollback to historical FIFO semantics requires a conscious operator decision.
- Verification: A→B→C 100 items, dispatch budget 75 ⇒ 75 A, then 25 A while earlier items RUNNING, wait for SUCCESS and closed membership, then B; cross-date/group/run isolation; joins, empty/failed/incomplete parents, concurrent enqueues, retries, stale snapshot, restart, old rows and full TD-014 transferred dispatcher safety.
- I4 must be deployable independently after I3 without a metrics/dashboard prerequisite.

### P14-I5 — Measurable diagnostics and benchmarks

Deliver runnable **read-only SQL diagnostics** for active JobRun cohorts, path completeness, parent/child status, waiting/failing joins, published-but-unfinished steps, eligible/leased/retry-delayed outbox, unknown identity and overcount detection. Each query documents parameters, bounds, expected cardinality, sample-safe use and interpretation. Benchmark snapshot and claim SQL using controlled `EXPLAIN (ANALYZE, BUFFERS)`, rows scanned/returned, index scans/buffer reads, transaction duration, Hikari active/idle/pending/wait, round trips, per-tick snapshot/planner latency p50/p95/p99 and planned→selected→claimed→published→executed breakdown. **No made-up PASS thresholds**; baseline and regression comparison with dataset size, environment and sampling window. `pg_stat_statements` is optional, not presumed installed. Define a low-cardinality metrics/read-projection dictionary for Plan 029 (not a UI).

I3/I4 require **targeted safety/query correctness diagnostics** before activation; moving the consolidated SQL _deliverable_ to I5 does **not** defer necessary I3/I4 security, concurrency, fail-closed, lock/cost or regression checks.

### P14-I6 — Operator read-model handoff

Expose or specify bounded JobRun/path/step progress, queue backlog, completion/wait/blocked reason, throughput, snapshot freshness and uncertain/UNKNOWN flags for Plan 029. Distinguish execution preparation/status from actual worker processing (P13-I1 timing evidence). No high-cardinality metric labels, full graph engine, dataset manifest reimplementation or dashboard UI duplication. Keep all required API addition/migration and backward-compatible fields scoped and independently testable; otherwise documentation/SQL contracts alone suffice and no new endpoint should be introduced.

### Symbol-rooted JobRun extensibility — owner design guard 2026-10-15

**Decision:** a JobRun represents one **target + explicitly scoped logical request**, _not_ inherently a sector-sized batch or a `businessGroup`. The identity is still the immutable UUID `jobRunId=job_runs.id`; `businessGroup`, `businessDate`, and the optional target scope are **attributes/filters**, not a composite replacement for run identity. Distinguish graph target (usually a _sink_ job in a DAG) from a _symbol_ target scope. A run may select one symbol, an exchange, a sector/group, or a global dataset without changing the static job-type DAG.

I3 must define a small **typed, versioned `RunScope`** or equivalent durable normalized fields: `scopeType` (`SYMBOL`, `SECTOR`, `EXCHANGE`, `GLOBAL` / other approved values), `scopeKey` (canonical source-qualified value), `businessDate`, optional `businessGroup`; exact fields/type/nullability, validation and indexing are frozen in I3. **Do not assume `businessGroup` is mandatory for a symbol-level run.** Example: `target=SYNC_INDICATORS, scopeType=SYMBOL, scopeKey=HOSE:HPG, businessDate=2026-10-15`. The same `SYNC_INDICATORS` static target definition is reused for one symbol or a bounded group; it is not cloned into one JobDefinition per symbol.

The DAG defines **job-type/definition dependencies**, while each edge's **scope mapping** is explicit: `SYMBOL → SYMBOL` for per-symbol upstream work; `SYMBOL → EXCHANGE` or `SYMBOL → GLOBAL` only when the upstream input is genuinely shared. A static edge alone is **not** evidence that a parent run can be narrowed to one symbol or that all global/exchange jobs should rerun. Validate target/producers can accept the scope; where a current producer always enumerates every symbol, narrow it via a separately approved additive dispatch context, or return `UNSUPPORTED_SCOPE` without silently expanding to whole-market fan-out. P14-I2 documents/matches scope capabilities, and I3 implements only supported mappings; a generic scope policy engine is out of scope.

**Run-step identity:** persist per-step resolved effective scope (`scopeType/scopeKey/businessDate`) and required predecessor membership, not just the JobRun's root scope or a static path of definition IDs. This permits two symbol runs to share **already-valid upstream dataset versions** without needing the same `jobRunId`, while their downstream completion barriers remain isolated. Define explicit reuse rules against actual dataset READY + exact lineage and persisted in-flight work; shared global/exchange upstream must be deduplicated by canonical logical work identity and must not be accidentally rerun per symbol. Do not make two symbol JobRuns each own competing execution of one non-idempotent global upstream. If shared active upstream work is referenced by more than one run, model durable referenced evidence (or safely wait for its exact ready dataset) and do **not** claim it is an owned child execution of every run.

**Snapshot/planner impact in I4:** group/allocate by `jobRunId` and resolved step scopes. No wholesale conversion to symbol-specific static DAG nodes. Fairness/quotas operate across incomplete runs; a run with no eligible message because upstream work is running returns WAIT. Index/query plans must be checked for scoped queries (run status, run ID, step state, scope type/key/date) at I3/I4 and measured in I5; high-cardinality symbol/run identifiers must not appear as unbounded metric labels in I5/I6.

**Compatibility tests / future acceptance:** one SYMBOL run for HPG must not cause all symbols to fan out; HPG and FPT runs for the same date must not block each other on symbol-local steps; shared EXCHANGE/GLOBAL upstream must not duplicate work and must not be mistaken for completed from mere outbox PUBLISHED; sector/global historical runs still work; target-only/unsupported-scope/retry/restart/multiple dates maintain stable run identity and safe completion. I3 may initially support only existing group-level scopes, **but must persist a scope contract that can evolve to SYMBOL without table redesign or remapping every execution**. Add concrete symbol scope implementation only when required by a later approved story; mark unsupported modes explicitly.

### Cross-cutting migration and deprecation

At **every story boundary**, build/test must PASS and the pre-existing enabled scheduled/manual/FIFO behavior must remain correct. Schema change is expand → cut over all readers/writers → safe forward-only drop, never mutate old applied Flyway migrations. Deprecate still-used Java interfaces via `@Deprecated` **when a safe compatibility adapter is retained**; document replacement, callers and removal gate. For SQL/tables, endpoints, or hard removals without an annotation, document deliberate breaking-contract removal and the exact migration/rollback plan. Preserve existing `triggeredAt`, `executionId`, `parentLogId` and `runKey` semantics until their usages are reviewed; `jobRunId` replaces only _cross-step_ correlation. No source is deprecated merely because cleanup might be useful.

## Relationship to Other Plans

- **Plan 023 / TD-014:** existing DependencyRegistry, guarded claim and publish are unchanged by I2/I3; their final post-planner safety verification transfers to **P14-I4**.
- **Plan 028:** marked **REVIEW REQUIRED** before scheduling. Its persisted job-key/SQL-edge graph migration (former Increment A) conflicts with the source-controlled static definition-edge owner in I3.a and is no longer an approved dependency. Reuse I2 topology and I3 JobRun preparation; reconsider only bounded date-range selection, preview, validation/repair and writer support after the Plan 030 cutover. Do not create a second graph or orchestration authority.
- **Plan 029:** basic Job Operations, Data Health and shell remain independent. JobRun/graph-specific read views consume I5 evidence and I6 bounded handoff; worker stage truthfulness remains P13-I1.
- **Runtime cache/recovery:** not scheduled or tracked as a speculative technical-debt epic. PostgreSQL remains authoritative; only revisit from measured query/pool evidence or a concrete reliability requirement.
- **TD-016:** a deferred redesign of dataset READY as an evidence-backed **scope + required date/date-range + immutable dataVersion/lineage** verdict, reconstructed from Parquet/manifests into PostgreSQL after parity validation. Each JobRun step may eventually ask whether its exact upstream input range is satisfied; a verified READY dataset may satisfy an explicitly approved dependency-data requirement **without fabricating a successful JobRun execution**. Do not bypass current DependencyGuard or I4 closed-execution barrier for actual in-flight runs. No active P14 prerequisite or cutover is authorized.

## Deferred Scope

The following remain deferred and are not acceptance criteria for P14:

- persisted job graph, graph administration API/UI, or migration away from static ownership;
- provider-specific rate policy, provider rotation, or an expanded provider-policy framework;
- weighted/aging/priority-class fairness, adaptive quotas, resource-aware placement, or automatic concurrency changes;
- runtime-generated DAG edges, conditional branches, ANY/quorum dependencies, remaining-dependency counters, or a workflow engine;
- backfill expansion, new historical producer transport contracts, or repair actions; reading and validating business-date/run/group identity from existing executions is required by this revision;
- TODO future: activate full symbol-scoped run preparation only after capability/producer mapping evidence; dedicated scheduler DLQ/replay policy also remains deferred. The I3 typed persisted RunScope is mandatory compatibility groundwork, not authorization to implement all symbol modes. Neither snapshot equality nor quarantine opens a failed cohort barrier;
- generic static dependency snapshot extraction from job-definition-specific resolution, tracked in [TD-012](../technical-debt/012-static-dag-dispatch-planner.md#deferred-generic-static-dependency-snapshot); current delivery reuses the existing definition-derived graph rather than introducing another abstraction;
- Kafka, protobuf, manifest, dataset-path, dataset-writer, or worker-runtime changes.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

The epic does not read or write dataset manifests as topology state. `DependencyGuard` continues to read existing readiness and exact-version evidence, and existing producers retain READY-last publication semantics.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No trading or research algorithm is introduced. The shared topology makes deterministic dependency/backfill tracing possible, and bounded planning makes scheduler selection explainable and independently testable.

## Contract Impact

### Field/DTO inventory — canonical 2026-10-15

| Owner    | Contract change                                                                                                                                           | Required compatibility                                                                                                                        |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| I2       | Static target→ancestors topological path, definition/node identity mapping, diagnostics                                                                   | Additive internal API; no runtime activation                                                                                                  |
| I3       | **New `job_runs` and durable run-step membership**; `jobRunId` link to root/child execution; scheduled/manual/backfill mode; target/scope/request context | New forward Flyway migration; manual API cutover before dropping manual table; old rows/FIFO/guard remain safe; no modification of applied V8 |
| I3       | Optional root-only autonomous cron scheduling                                                                                                             | Dependency definitions/producer configs retained; no silent loss of independent required schedules; explicit opt-in                           |
| I4       | Execution/outbox read projection, typed JobRun cohort/step/quota selection, scoped queries and enabled/fallback config                                    | Read-only bounded snapshot, claim-time revalidation, guarded/fenced publishing and rollback                                                   |
| I5       | Versioned diagnostic SQL, benchmark evidence, metric read dictionary                                                                                      | No metric storage/schema/public API required solely for measurement                                                                           |
| I6       | Bounded JobRun operations projection or approved API                                                                                                      | No duplicate Plan 029 UI, explicit UNKNOWN and authorization                                                                                  |
| Deferred | Optional runtime caching and full historical backfill (Plan 028)                                                                                          | Separate evidence and owner approval; no speculative cache epic                                                                               |

Kafka/protobuf, dataset manifests, storage object paths and Python worker transport stay unchanged **unless an I3 dependency-preparation implementation proves an additive producer/consumer contract is unavoidable**; if so stop, document blast radius and obtain a separately approved compatibility slice. Never conflate JobRun/step completion with outbox PUBLISHED or dataset READY. `triggeredAt` remains audit timestamp; legacy `runKey` remains compatible until cutover; `jobRunId` is run correlation, not child identity.

## Cross-Service Blast Radius

| Surface                    | Impact or no-impact reason                                                                                                                                                                                                                                                                                                                                                                                               |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Platform                   | Primary owner: topology population mapping, execution/parent/cohort projection, producer metadata inventory, JobService aggregation safety, planner, scoped queries, dispatcher, configuration and tests. Graph impact analysis on JobService, snapshot service and dispatcher found 16 additional impacted files; bounded results were truncated and included 84 unresolved call sites, not proof of no further impact. |
| Analyzer                   | No runtime change: planner selection is Platform-local and worker payloads remain unchanged. Reconcile only if a later story changes transport, which this epic forbids.                                                                                                                                                                                                                                                 |
| Ingestor                   | No runtime change for the same reason; provider behavior and ingestion concurrency remain deferred.                                                                                                                                                                                                                                                                                                                      |
| Query Service              | No runtime change. It does not own scheduler topology or dispatch planning.                                                                                                                                                                                                                                                                                                                                              |
| Omni Console               | No I2 runtime scope. Plan 029 graph-specific JobRun views consume I5 evidence and I6 handoff; basic operations remain independently scoped.                                                                                                                                                                                                                                                                                     |
| Shared contracts/libraries | No protobuf, Kafka, or shared Python change. Hand-written Java abstractions remain in Platform because current ownership is scheduler-local.                                                                                                                                                                                                                                                                             |
| Persistence                | Execution histories, parent aggregation, metadata identity and outbox eligibility are jointly impacted. Review bounded joins/indexes, late-child closure and retry correlation; no graph/count persistence or outbox status is approved. Missing identity/closure evidence requires an explicit contract/migration decision before implementation.                                                                       |
| Configuration              | No I2 configuration change. I3 owns safe target-route gating; I4 owns bounded planner/admission settings. No provider credentials, regions, topics, or storage paths change.                                                                                                                                                                                                                                                                                                          |
| Tests                      | Platform unit and PostgreSQL integration tests are directly impacted; producer/worker contract tests are no-impact while payloads remain unchanged.                                                                                                                                                                                                                                                                      |
| Operations                 | Add bounded planner diagnostics, fallback visibility, and query-cost evidence; no deployment topology, capacity, or provider-policy change.                                                                                                                                                                                                                                                                              |

## Repository Guidance Updates

Implementation must review and update, where applicable:

- [`AGENTS.md`](../../AGENTS.md), [`CLAUDE.md`](../../CLAUDE.md), and [`.roo/rules`](../../.roo/rules) only if architecture/workflow guidance changes;
- [`docs/README.md`](../README.md), [`docs/INDEX.md`](../INDEX.md), and the canonical roadmap;
- [`docs/architecture/001-system-overview.md`](../architecture/001-system-overview.md);
- [`docs/flows/001-job-execution.md`](../flows/001-job-execution.md);
- [`docs/data/003-database.md`](../data/003-database.md) if snapshot indexes or query ownership change;
- [`docs/development/001-where-to-change.md`](../development/001-where-to-change.md);
- Plans 023, 028, and 029 plus TD-012.

This planning update clarifies roadmap hierarchy and approved architecture but changes no runtime coding rule. Existing repository guidance already requires Platform-local dependency policy, graph impact analysis, claim/fencing preservation, and explicit cross-service reconciliation; therefore no immediate `AGENTS.md`, `CLAUDE.md`, or `.roo/rules` edit is required.

### Documentation disposition for the 2026-10-13 revision

Architecture, execution flow, developer ownership guidance, field/DTO inventory, registry, roadmap selection, TD-012 and release notes have revision records. Documentation indexes retain their existing Plan 030 links; no new document is introduced. Plans 023/028/029 and TD-014 retain their original ownership and dependency relationships; no independent schedule, backfill rollout or UI contract is changed. Data-lake/Kafka contracts and deployment/service runtime instructions have no implementation change from this documentation-only revision. Database documentation must be revisited when identity/closure or projection/index design is frozen. No agent-rule change is proposed: existing guard ownership and verification gates remain applicable. These dispositions do not claim that every linked document or path has passed a complete static audit; remaining audit scope must close before documentation synchronization is marked complete.

## Verification

### Historical evidence and new story-level gates

P14-I1 source implementation is present in the Platform scheduler topology package:

- `TopologyNodeKey` provides validated stable logical keys derived from `JobType`;
- `JobDefinitionIdentity` maps the existing `(source, jobType, cronExpr)` database identity without a migration;
- `StaticTopologyDeclaration` preserves raw nodes, edges, and mappings for validation;
- `StaticJobTopology` provides immutable roots, direct/transitive traversal in both directions, deterministic topological order, bounds, mapping lookup, validation, and Mermaid diagnostics;
- `StaticJobTopologyProvider` is the single active declaration boundary and derives job-to-job edges from existing seed `dependsOnJobs` metadata without reading manifest readiness;
- `StaticJobTopologyTest` covers the canonical seed graph, multiple definitions sharing one type, traversal/export, invalid keys, unknown/duplicate/self edges, cycles, unmapped/ambiguous definitions, bounds, unknown lookups, traversal depth, and malformed dependency metadata.
- `JobDefinitionSeederTest` covers seed-owned title/fallback/config synchronization while preserving source identity and runtime-owned scheduling fields.

P14-I1 blast-radius reconciliation:

- Platform: changed by additive topology classes/tests plus startup seed synchronization of title, fallback sources, and config for the existing `(source, jobType, cronExpr)` identity; scheduler dispatch, producers, claims, dependency guard, public controllers, runtime-owned scheduling fields, and stale-row policy are unchanged.
- Analyzer and Ingestor: no runtime impact because Kafka payloads, worker handlers, and job execution contracts are unchanged.
- Query Service and Console: no impact because no API or graph presentation endpoint is introduced.
- Shared contracts/libraries: no impact because Proto3, py-common, topic configuration, and generated code are unchanged.
- Persistence and configuration: no impact because the graph is process-local, derives from existing seeds, and adds no migration, table, index, property, or environment variable.
- Dataset/storage contracts: no impact because manifests, readiness, lineage, logical paths, producers, and consumers are unchanged.
- Historical backlog-only I2 scaffolding persistence/query impact: additive read-only PostgreSQL aggregation reused existing outbox/execution/job-definition columns and indexes. Current static-handoff I2 adds no query or persistence behavior; JobRun persistence belongs to I3 and runtime snapshot/admission to I4.
- Operations: startup constructs topology, snapshot service, and pure planner beans; no scheduler or dispatcher calls them, so runtime dispatch behavior remains unchanged.

Code-review-graph impact analysis was performed before implementation and reconciled against Platform, Analyzer, Ingestor, Query Service, Console, shared contracts/libraries, persistence, configuration, tests, and operations. The owner approved and reviewed the complete local verification gate on 2026-10-11:

- `nx run platform:test` — PASS;
- `nx run platform:coverage` — PASS, with attributable JaCoCo evidence for every changed critical class;
- `nx run platform:build` — PASS;
- compact gate: `PASS P14-I1 required=3 pass=3 fail=0 unknown=0 missing=0 sources=exit_code`;
- owner attestation: `OWNER_VERIFIED P14-I1 verified_by=tanlocit9`, bound to summary SHA-256 `77c59cef1535dfda4cf3c84d581e6ca3a715ede604b2228e32b20ef6309a2ad4`.

The checks emitted shutdown-hook Hikari warnings after tests but completed successfully. No CI, deployment, provider, production, migration, load, or live-runtime verification is claimed or required by the bounded P14-I1 process-local topology scope.

P14-I1 impact/source/test coverage matrix:

| Feature / criterion                                                  | Source                      | Executed tests                                                                | Attributable coverage       |
| -------------------------------------------------------------------- | --------------------------- | ----------------------------------------------------------------------------- | --------------------------- |
| Stable logical node key                                              | `TopologyNodeKey`           | `StaticJobTopologyTest`                                                       | 100.0% line / 100.0% branch |
| Stable definition identity                                           | `JobDefinitionIdentity`     | `StaticJobTopologyTest`                                                       | 93.3% line / 83.3% branch   |
| Immutable raw declaration                                            | `StaticTopologyDeclaration` | `StaticJobTopologyTest`                                                       | 100.0% line / no branches   |
| Validation, roots, traversal, order, bounds, mapping, Mermaid export | `StaticJobTopology`         | `StaticJobTopologyTest`                                                       | 99.2% line / 94.8% branch   |
| Seed-derived nodes/edges and malformed metadata diagnostics          | `StaticJobTopologyProvider` | `StaticJobTopologyTest`                                                       | 100.0% line / 100.0% branch |
| Startup seed synchronization with runtime-field preservation         | `JobDefinitionSeeder`       | `JobDefinitionSeederTest`                                                     | 93.5% line / 100.0% branch  |
| Canonical seed/dependency declaration                                | `JobDefinitionConfig`       | `JobDefinitionConfigTest`, `JobDefinitionSeederTest`, `StaticJobTopologyTest` | 99.4% line / 91.3% branch   |

Historical evidence: the earlier P14-I2 source passed its owner-approved local verification gate on 2026-10-12. This evidence does not cover the subsequent per-path/freshness correction:

- `nx run platform:test` — PASS after focused repair of Spring constructor selection and repository-proxy exception expectation;
- `nx run platform:coverage` — PASS, with every P14-I2 critical component above the 80% line/branch threshold;
- `nx run platform:build` — PASS;
- compact gate: `PASS P14-I2 required=3 pass=3 fail=0 unknown=0 missing=0 sources=exit_code`;
- owner attestation: `OWNER_VERIFIED P14-I2 verified_by=tanlocit9`, bound to summary SHA-256 `afacc8b02a7216de87631fbab178207dbe860bb869c3e560858a0478a2902255`.

The suite emitted known shutdown-hook Hikari warnings, and compilation reported the native-JPA unchecked warning tracked by TD-015; neither failed the gate. No CI, commit, merge, deployment, production, load, or dispatcher-runtime activation is claimed.

Historical source correction — 2026-10-12, superseded as the target design by the 2026-10-13 cohort revision: planner traversed nodes without dispatchable work, stopped occupied paths and deduplicated scopes. Explicit evaluation time and positive maximum snapshot age were added; tests were not run. This source does not enforce execution-cohort completion barriers. Earlier coverage below remains historical, not verification of the revised target.

Historical reconciliation for the 2026-10-12 source correction: only planner/result APIs and focused tests changed, without runtime activation or transport/schema changes. This does not describe the 2026-10-13 revised implementation blast radius; use the Cross-Service Blast Radius and revised P14-I2 requirements above. The referenced execution-log.md is absent; existing canonical evidence records are used. SQL output limits do not prove bounded scan cost; query-load evidence remains unclaimed.

Historical P14-I2 impact/source/test coverage matrix:

| Feature / criterion                                         | Source                                              | Executed tests                                        | Attributable coverage       |
| ----------------------------------------------------------- | --------------------------------------------------- | ----------------------------------------------------- | --------------------------- |
| Explicit persisted-definition scope                         | `DispatchScope`                                     | `DispatchPlannerTest`, `PlanningContractsTest`        | 100.0% line / 100.0% branch |
| Mutually exclusive backlog count contract                   | `BacklogCounts`                                     | `PlanningContractsTest`, `BacklogSnapshotServiceTest` | 100.0% line / 85.7% branch  |
| Timestamped bounded complete/incomplete snapshot            | `BacklogSnapshot`                                   | `BacklogSnapshotServiceTest`, `PlanningContractsTest` | 95.0% line / 83.3% branch   |
| Typed bounded planner result                                | `DispatchPlan`                                      | `DispatchPlannerTest`, `PlanningContractsTest`        | 100.0% line / 100.0% branch |
| Topology mapping, truncation, stale/duplicate scope failure | `BacklogSnapshotService`                            | `BacklogSnapshotServiceTest`                          | 100.0% line / 87.5% branch  |
| Roots, joins, backoff isolation, quotas and cursor fairness | `DispatchPlanner`                                   | `DispatchPlannerTest`                                 | 98.1% line / 87.5% branch   |
| Raw persisted aggregate identity/count contract             | `SchedulerBacklogGroup`                             | `PlanningContractsTest`, PostgreSQL repository test   | 100.0% line / 87.5% branch  |
| Read-only PostgreSQL backlog classification/projection      | `SchedulerOutboxRepositoryImpl.findBacklogGroups()` | `JobDefinitionClaimRepositoryPostgresTest`            | 100.0% line / 100.0% branch |

Implementation verification must use the confirmed Platform targets and receive approval before execution. Required evidence includes:

- code-review-graph impact analysis before shared API/configuration/persistence changes and change detection after edits;
- explicit reconciliation of Platform, Analyzer, Ingestor, Query Service, Console, shared contracts/libraries, persistence, configuration, tests, and operations;
- an impact-to-test matrix for every changed/directly impacted production path and acceptance criterion;
- independently attributable coverage of at least 80% line and 80% branch for topology validation/traversal, snapshot classification, planner selection, scoped candidate query, and integration/fallback components;
- P14-I1 tests for deterministic identity/mapping, multiple definitions per type, roots, joins, ancestors/descendants, duplicate/self/unknown/dangling edges, cycles, graph bounds, and stable export;
- P14-I2 tests for definition-specific ancestor closure, deterministic order, roots/joins, mapping/validation, explicit bindings and scoped capabilities, fail-closed bounds, immutable handoff and unchanged startup/outbox regression;
- P14-I3 tests for durable JobRun/scoped membership, dependency-first preparation, transaction/restart/idempotency, manual cutover and unchanged FIFO/guard behavior; no unsafe target route activation;
- P14-I4 snapshot/planner and PostgreSQL dispatcher tests for classification, closed membership and completion barriers, quotas/fairness, selected-scope querying, guard invocation for every candidate, READY/WAITING/BLOCKED, concurrent claim loss, lease expiry, token/instance fencing, attempt accounting, retry identity, publication acknowledgement, terminal non-reclaimability, underfill/refill, fallback, and legacy rows;
- bounded query-plan/load evidence before asserting a performance benefit;
- static documentation-consistency audit after documentation synchronization;
- approved targeted Platform test/coverage/build checks before any story is marked completed; CI/runtime evidence is required only when applicable to that story's declared acceptance and delivery path. No CI, deployment or runtime activation gate is added to the pure inactive I2 handoff.

Aggregate project coverage, source presence, graph reachability, broad suite success, or Git/CI identifiers cannot substitute for story-attributable behavior evidence.

## Acceptance Criteria

### Canonical 2026-10-15 criteria

### P14-I1 — completed (historical evidence retained)

- [x] Static topology, mapping, deterministic traversal and cycle/duplicate/dangling validation have already passed the recorded P14-I1 gates; do not rewrite the attestation for subsequent changes.

### P14-I2 — static topology handoff

- [ ] Reuse the P14-I1 validated graph to resolve target ancestors, stable job definition mapping and dependency-first topological order; multiple roots/joins, cycles, missing/duplicate definitions, deterministic output and bounded traversal covered by tests.
- [ ] Produce an approved internal graph-to-dispatch handoff **without** adding JobRun schema/runtime outbox integration/metrics UI; version the contract and validate I3 compatibility.
- [ ] Inactive earlier snapshot/planner scaffolding is reconciled or documented as candidate for deprecation/removal with caller inventory; legacy scheduler/dispatcher builds and tests pass unchanged.

### P14-I3 — unified JobRun, DAG path and durable preparation

- [ ] I3.a extracts job-to-job edges from `configJson.dependsOnJobs` to the single typed, source-controlled static definition-level declaration; all current edges/definitions/strategies match, provider/resolver and callers are migrated compatibly, startup/outbox/guard regression passes and no SQL dependency graph is introduced.
- [ ] `job_runs.id` is `jobRunId`, with durable target/mode/typed scope/date/path and immutable **scoped** run-step membership. Every step can be mapped to required predecessors, execution/outbox evidence and run identity after restart. No timestamp-based cross-step correlation.
- [ ] All executable ancestor job definitions remain available as dependency-only definitions; only independently scheduled **entry targets** are reduced after reviewing cron and manual use. DAG path resolves and enqueues dependencies before target without producing unsupported extra descendants.
- [ ] Manual trigger controller/API/idempotency/permissions and audit move to JobRun; obsolete `manual_job_triggers` table is dropped via **new forward-only migration after code cutover** (no data migration); prior Flyway migration files are untouched. No broken startup state during rollout.
- [ ] Existing outbox FIFO/guard/claim/retry/fencing/publish behavior remains compatible; **enqueue order is not completion safety**. Any newly introduced target-run route is gated off unless existing guard or a proven safe admission check prevents downstream execution before upstream SUCCESS/closed membership.
- [ ] Transaction, partial preparation, crash/replay, concurrent run, duplicate creation, zero-child, missing/failed work, and safe rollback tests pass with legacy/manual/scheduled behavior unaffected.

### P14-I4 — JobRun-aware planning and guarded outbox integration

- [ ] Bounded PostgreSQL read snapshot includes closed run-step and child membership, terminal and published-but-unfinished evidence, separate outbox lease/eligibility/retry states, freshness, completeness and UNKNOWN reasons.
- [ ] Planner selects earliest incomplete predecessor path, advances only on required predecessor SUCCESS + closed membership, respects joins and isolates businessGroup/businessDate/jobRunId; no implicit graph traversal beyond target.
- [ ] The 100-item budget-75 trace verifies 75→25 upstream dispatch while children run, barrier WAIT, eventual downstream advance and unused quota going to other scopes.
- [ ] Before every admission/claim, revalidate the selected JobRun cohort barrier, use DependencyRegistry READY/WAITING/BLOCKED and preserve atomic fenced publish/retry/ack. Bounded no-starvation fallback and legacy row compatibility tested, transferred [TD-014](../technical-debt/014-dependency-aware-dispatch-verification-residue.md) evidence completed.
- [ ] Targeted SQL query cost, concurrency, scope isolation (including planned SYMBOL and shared EXCHANGE/GLOBAL mapping), bounds and restart evidence are sufficient to activate I4 safely; the full performance/metrics pack is owned by I5.

### P14-I5 — measured evidence

- [ ] Versioned diagnostic SQL and safe bounded `EXPLAIN (ANALYZE, BUFFERS)` steps cover run steps, membership/completion, backlog, blocked/unknown causes, index usage and overcount prevention.
- [ ] Dataset/window/environment-aware p50/p95/p99 planner/snapshot/claim durations, rows and buffers, DB calls, Hikari pool pressure, execution/publish throughput, comparisons and uncertain states are measured rather than guessed.
- [ ] Definitions and limitations for Plan 029 metrics are recorded; no premature UI or new stats persistence.

### P14-I6 — operations read-model handoff

- [ ] Graph-specific JobRun/path status, completeness, backlog, uncertain states and measured progress can be consumed through a bounded read contract/fixtures or approved API if necessary; no duplicate dashboard implementation.
- [ ] Plan 029 owns actual operator UI; worker RUNNING is reported only with actual worker timing evidence, never inferred from preparation-time child RUNNING.

### Every story

- [ ] Explicit blast radius/caller inventory, compatible source/schema/transport evolution, independent build/test/coverage and rollback, documented deprecation/removal gates, no half-implemented breaking dependencies on a future story.
- [ ] No persisted second graph authority, generic workflow engine, cache/recovery implementation, arbitrary provider scheduler or unrelated Plan 028 backfill rollout is introduced.

## Risks and Stop Conditions — current

Stop and request owner input if:

- one stable static node cannot map unambiguously to current definitions without a persisted identity migration;
- topology and dataset dependency declarations materially disagree and selecting an authority changes product semantics;
- the approved group/date/run barrier expands into a whole-node or cross-cohort barrier, or authoritative cohort membership/correlation cannot be established from existing execution evidence;
- provider/resource quotas or advanced fairness become necessary for acceptable behavior;
- a snapshot query requires unbounded scans or a material schema/index migration without measured evidence;
- integration cannot preserve current claim/fencing, attempt, retry, BLOCKED, or legacy compatibility semantics;
- Plan 028 persisted topology is promoted, requiring an explicit one-authority cutover decision.

## Rollback — canonical 2026-10-15

- **I2:** inactive graph handoff may be rolled back without runtime/schema changes.
- **I3:** disable target-driven JobRun preparation; preserve legacy scheduling and FIFO outbox until safe migration/cutover. **The dropped manual table cannot be restored without backup**, and no manual history migration is planned: document this irreversible data-loss consequence before executing its forward migration, and preserve manual API availability through the cutover.
- **I4:** disable JobRun-aware outbox selection and retain explicitly reviewed historic FIFO behavior, DependencyGuard, claim fencing and persisted records; never silently bypass an enabled cohort barrier.
- **I5/I6:** diagnostics and bounded read-model consumers can be withdrawn without affecting claims or execution history.
- Never roll back by deleting JobRun/outbox/execution data, modifying applied migrations, replaying unpublished work without idempotency, or inventing successful upstream completion.
