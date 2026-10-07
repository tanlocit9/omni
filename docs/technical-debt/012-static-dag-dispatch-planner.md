# Static DAG Dispatch Planner

## Decision

Historical design note — 2026-10-07; promoted by owner decision on 2026-10-08 into the active [Plan 030 — Static Graph & DispatchPlanner](../plans/030-static-graph-dispatch-planner.md) epic and canonical stories P14-I1, P14-I2, and P14-I3. Status: ACTIVE EPIC REFERENCE / runtime not implemented by this documentation change.

This record preserves the original deferred design and activation cautions. Plan 030 owns active scope, acceptance, dependencies, and bounded delivery. [Plan 023 — Dependency-Aware Outbox Dispatch](../plans/023-dependency-aware-outbox-dispatch.md) preserves the implemented baseline; P4-I3 is superseded, and [TD-014](014-dependency-aware-dispatch-verification-residue.md) transfers its unclosed safety/evidence contract to P14-I3. Do not introduce a workflow engine or runtime execution DAG.

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

[Plan 028 — Reusable Date-Range Job Backfill](../plans/028-reusable-date-range-backfill.md) may depend on P14-I1 to reuse the shared topology for tracing and enqueueing missing dated work. It does not depend on P14-I2 or P14-I3: DispatchPlanner selects already-enqueued work and does not perform backfill expansion. Plan 028's proposed persisted job graph remains a separate ownership migration. If promoted, provide one topology boundary with parity/cutover evidence; do not keep static and persisted graphs as competing authorities.

## Promoted story mapping and evidence

- P14-I1 owns node identity/mapping, one static topology boundary, validation, traversal, and diagnostics without a persisted graph.
- P14-I2 owns the bounded pending/dispatchable/in-flight snapshot and pure deterministic top-down node/scope/quota planning without runtime activation.
- P14-I3 owns scoped candidate queries and dispatcher integration while preserving the registry/guard and atomic claim boundary.
- Multiple roots, joins, empty roots, WAITING/backoff, in-flight work, cycles, duplicate node mappings, stale/incomplete snapshots, baseline bounded fairness, and concurrent claims require attributable evidence before the owning story completes.

Persisted graph ownership, expanded provider policy, provider budgets, adaptive concurrency, advanced weighted/aging fairness, runtime DAG edges, conditional branches, ANY/quorum dependencies, and persisted remaining-dependency counters remain deferred and require concrete demand plus a separate owner decision. No placeholder source TODO is added for an unbuilt class.

Rollback disables planner selection and restores the existing candidate-selection path while retaining job readiness checks and claim fencing. No execution history or pending outbox record is deleted. Tests/build/load/provider checks have not been run for this documentation-only change.

Refs: [Plan 030 active epic](../plans/030-static-graph-dispatch-planner.md), [debt index](README.md), [priority diagram](priority-order.md), [field/DTO inventory](../reference/002-planned-field-dto-impact.md), [canonical increment registry](../plans/roadmap/implementation-increments.md).
