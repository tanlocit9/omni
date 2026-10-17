# Static DAG Dispatch Planner

## Decision

Historical design note — 2026-10-07; promoted by owner decision on 2026-10-08 into the active [Plan 030 — Static Graph & DispatchPlanner](../plans/030-static-graph-dispatch-planner.md) epic and canonical stories P14-I1 through P14-I6. Status: ACTIVE EPIC REFERENCE / runtime not implemented by this documentation change.

This record preserves the original deferred design and activation cautions. Plan 030 owns active scope, acceptance, dependencies, and bounded delivery. [Plan 023 — Dependency-Aware Outbox Dispatch](../plans/023-dependency-aware-outbox-dispatch.md) preserves the implemented baseline; P4-I3 is superseded, and [TD-014](014-dependency-aware-dispatch-verification-residue.md) transfers its unclosed safety/evidence contract to P14-I4. Do not introduce a workflow engine or runtime execution DAG.

## Owner revision — 2026-10-13

The backlog-only selection and DTO proposals below are historical. [Plan 030](../plans/030-static-graph-dispatch-planner.md) now owns execution-centered parent/cohort summaries and completion ordering by businessGroup/businessDate/jobRunId. BANKS and REAL_ESTATE remain independent inside the static job-type map; symbol grouping is a future TODO. This is a scoped execution-completion barrier, not a whole-node barrier or new execution DAG. Existing candidate guard retains dataset readiness/version authority; no new outbox processing status is approved. Earlier P14-I2 checks do not verify this revision.

## Owner revision — 2026-10-15 (canonical Plan 030)

Plan 030 P14-I2 now freezes the static DAG target→ancestor dispatch contract; P14-I3 persists unified JobRun with immutable required steps and prepares dependency-first outbox; P14-I4 uses JobRun execution snapshots to select/admit guarded messages; I5 and I6 deliver measured diagnostics and operations read-model. The 2026-10-13 backlog-only story mapping below is historical. Existing dependency job definitions/producers must remain even if they stop being independently scheduled. JobRun step membership is persisted instance evidence, **not** a second mutable topology authority. Runtime cache is not in active scope; PostgreSQL-first run-step snapshots remain authoritative. Revisit caching only on measured need with a new owner-approved bounded plan.

## Plan 030 static definition-edge ownership — 2026-10-16

Plan 030 **P14-I3.a** owns the source-controlled extraction of `configJson.dependsOnJobs` to typed per-`JobDefinitionIdentity` static edges, using the existing single topology provider and I2 resolver. This is not a PostgreSQL graph migration. Plan 028's earlier persisted graph Increment A is **superseded as a delivery candidate** and Plan 028 is **REVIEW REQUIRED / ON HOLD** pending redesign. The older proposals below are historical only; neither a second registry nor a second topology runtime authority is approved. Symbol scope belongs to run-step scope mapping, not one definition per symbol.

## Deferred generic static dependency snapshot

- Status: DEFERRED; owner-requested follow-up on 2026-10-13, not a P14 blocker or implemented capability.
- Current coupling: pending execution discovery groups persisted parent IDs, loads parent job definitions, resolves their dependency declarations and maps onto the definition-derived static topology. Execution progress remains a separate per-cohort summary.
- Future goal: extract an immutable generic static dependency snapshot/provider boundary independent of JPA entities, job-definition configuration representation and runtime execution state. Reuse the existing topology boundary; do not introduce a second graph authority or an execution DAG.
- Activation: a concrete additional consumer or material mapping/maintenance cost justifies the extraction, followed by owner-approved scope and graph impact analysis.
- Safety: preserve stable nodes, definition mapping, edge validation, cohort isolation, guard ownership, exact-version semantics and claim/fencing. Static snapshot means topology/dependency declarations, not cached execution progress or a mutable global scheduler state.
- Closure evidence: parity tests between old and extracted declarations/mappings, bounded validation/export, compatibility for planner and other approved consumers, one-provider cutover/rollback and synchronized canonical docs. No Kafka, manifest, storage or database change is authorized by this debt entry; declare any later contract change separately.

## Ownership and relationship

| Component                                  | Serves              | Responsibility                                                                                                                                                |
| ------------------------------------------ | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Scheduler / manual trigger and JobProducer | Work acceptance     | Prepare execution and PENDING outbox records through the existing transactional path.                                                                         |
| Static job DAG                             | Shared job topology | Define upstream/downstream relationships once, with stable logical node identity and explicit mapping to existing definitions.                                |
| DispatchPlanner                            | Scheduler dispatch  | Use a backlog snapshot and the static DAG to select nodes and bounded quotas. It does not enqueue upstream jobs, read manifests, or declare data READY.       |
| DependencyGuard through DependencyRegistry | Job dependencies    | Resolve and evaluate the selected job's required input, scope and exact version evidence. Existing ENFORCED versus DOCUMENTATION_ONLY semantics remain valid. |
| SchedulerOutboxDispatcher                  | Dispatch execution  | Query selected candidates, evaluate via the registry, atomically claim READY rows, publish, and record fenced outcomes.                                       |

Guard ownership is job dependency semantics, not worker placement: the scheduler may invoke it before publication. Planner and guard are not independent implementations of the same readiness rule. The planner must not duplicate manifest evaluation; the guard must not decide cross-node priority or quota.

The graph guides selection; an empty upstream backlog is not proof of input readiness. A planner-selected candidate can still receive WAITING or BLOCKED from the guard. DAG edges must come from the shared dependency definition rather than a second manually maintained scheduler graph. Job-to-job edges and dataset conditions are related but not interchangeable; document their mapping.

## Proposed selection

1. Query a bounded snapshot grouped by graph node before fetching message payloads. Distinguish pending, currently claimable and in-flight work; lease/retry evidence must not be interpreted as completion. Counts include explicit scope and snapshot time.
2. Traverse each root upstream to downstream. An occupied node is preferred over its downstream nodes for that selection round. With root1 → a → b, an empty root1 and occupied a selects a; with root2 → c → d, occupied root2 and c selects root2.
3. Combine independent root selections and assign bounded quotas within one dispatch budget. Shared descendants are visited once and reconcile all required upstream paths. Validate cycles, unknown node mappings and graph bounds; explicit root threads are not required.
4. Return typed node/scope/quota selections. Repository code constructs parameterized queries; neither planner nor guard returns raw SQL.
5. Query candidates, evaluate their job dependencies via the existing registry/guard, and claim/publish only READY work. Snapshot counts are hints, not reservations; the atomic claim still rechecks status, availability and fencing.

Before promotion, specify whether in-flight work stops traversal on each edge and how WAITING/backoff nodes avoid starving other roots. Node-wide top-down selection may delay a ready symbol behind unrelated upstream symbols. Preserve Plan 023 item-level readiness and unrelated-ready-work guarantees; a stricter whole-node barrier needs an explicit owner decision. Metadata retains its separately defined global barrier. Do not silently change dispatch semantics through graph traversal.

## Internal field/DTO impact

Candidate names are design labels, not approved classes or wire schemas.

| Candidate surface    | Facts                                                                                   | Kind / impact                                                                                                                                  |
| -------------------- | --------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| NodeBacklogSnapshot  | nodeKey, scope, pendingCount, claimableCount, inFlightCount, snapshotTime, completeness | DERIVED, MEDIUM: bounded Platform query projection; no new persisted counts or status enum. Missing coverage is UNKNOWN, not zero.             |
| DispatchSelection    | nodeKey, scope, quota                                                                   | ADD internal / SEMANTIC, MEDIUM: changes candidate selection and ordering, not input readiness. Exact types and limits require a design slice. |
| Shared topology view | stable node identity, directed edges, definition mapping                                | REUSE/UNRESOLVED, MEDIUM static mapping; no dependency table or per-run edges in this proposal.                                                |

No public API, Kafka/protobuf, Python model, manifest, dataset path, environment variable or database migration is introduced by this note. Future implementation affects Platform dispatcher, dependency topology adapter, repository queries and focused tests. Workers, Query Service, Console, shared transport libraries and storage writers remain unchanged unless a later slice explicitly declares otherwise. Query/index changes need measured review; no performance gain is asserted.

## Plan 028 reuse

[Plan 028 — Reusable Date-Range Job Backfill](../plans/028-reusable-date-range-backfill.md) may depend on P14-I1 to reuse the shared topology for tracing and enqueueing missing dated work. It does not depend on P14-I2 or P14-I3: DispatchPlanner selects already-enqueued work and does not perform backfill expansion. Plan 028's former persisted job graph is superseded by Plan 030 I3.a; Plan 028 must be reviewed before promotion. If promoted, provide one topology boundary with parity/cutover evidence; do not keep static and persisted graphs as competing authorities.

## Canonical story mapping (2026-10-15)

- **P14-I1:** completed static topology and validation.
- **P14-I2:** validated static graph ancestor path/definition mapping and I3 handoff.
- **P14-I3:** JobRun persistence, immutable run-step path, dependency-first preparation and unchanged FIFO outbox/guard.
- **P14-I4:** scoped candidate planning and guarded/fenced admission from bounded JobRun snapshot; transferred TD-014 safety proof.
- **P14-I5/I6:** measured SQL/metrics and bounded operations read-model handoff, not dashboard UI.

Persisted graph ownership, expanded provider policy, provider budgets, adaptive concurrency, advanced weighted/aging fairness, runtime DAG edges, conditional branches, ANY/quorum dependencies, and persisted remaining-dependency counters remain deferred and require concrete demand plus a separate owner decision. No placeholder source TODO is added for an unbuilt class.

Rollback disables planner selection and restores the existing candidate-selection path while retaining job readiness checks and claim fencing. No execution history or pending outbox record is deleted. Tests/build/load/provider checks have not been run for this documentation-only change.

Refs: [Plan 030 active epic](../plans/030-static-graph-dispatch-planner.md), [debt index](README.md), [priority diagram](priority-order.md), [field/DTO inventory](../reference/002-planned-field-dto-impact.md), [canonical increment registry](../plans/roadmap/implementation-increments.md).
