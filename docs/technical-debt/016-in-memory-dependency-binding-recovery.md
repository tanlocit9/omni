# TD-016 — In-memory Dependency Binding Recovery

## Decision

Owner direction — 2026-10-14: target one Platform instance without a new durable binding table for now. Keep an immutable runtime dependency-binding snapshot in memory and recover it at startup using retained scheduler outbox records as discovery anchors, joined to persisted execution/parent/job-definition evidence and the existing static topology.

Status: OPEN / recovery design and evidence pending. This is a planned risk record, not a report of observed production data loss or implemented recovery. [Plan 030](../plans/030-static-graph-dispatch-planner.md) owns implementation and activation. No new outbox processing status or execution DAG is authorized.

## Risk and Lost State

Process restart, crash, deployment or memory exhaustion discards runtime parent bindings, snapshot versions and advisory allocation cursors. Atomic memory updates prevent readers from seeing partial snapshots; they do not make state durable or atomic with PostgreSQL commits.

Persisted executions and outbox messages are not lost merely because the RAM snapshot disappears. The risk is loss of scheduling context: unresolved predecessor bindings can stall downstream work; guessing bindings can admit it too early. Committing enqueue before publishing its in-memory binding also creates a crash gap. Updating memory before commit can expose rolled-back executions.

## Recovery Boundary

- Outbox is the recovery discovery anchor, not standalone proof of cohort completion or cross-step binding.
- Do not assume PENDING-only rows are sufficient: upstream messages may be PUBLISHED while their executions remain unfinished, or upstream completion evidence may be needed by a pending downstream group.
- Rebuild the relevant dependency closure using retained outbox records and execution parent membership, job definitions and authoritative group/date/run identity. Parent ID identifies fan-out membership, not a cross-job predecessor by itself.
- Current metadata/run correlation must be inventoried before claiming deterministic reconstruction. Missing or ambiguous identity remains UNKNOWN and blocks the affected path with diagnostics; do not select a predecessor by timestamp proximity or newest SUCCESS.
- Outbox retention/cleanup must not remove the only reconstruction evidence while dependent work remains active. Define the required retention closure before relying on recovery.
- Children can already be RUNNING at enqueue time. Execution PENDING alone does not identify all messages awaiting dispatch; use outbox eligibility and execution progress separately.

## Minimum Safety Requirements

1. Publish bindings only after the enqueue transaction commits. Reconcile committed rows if the after-commit memory update was missed.
2. Build a complete immutable candidate snapshot and replace the previous snapshot atomically; serialize writers or use version-checked updates to prevent lost concurrent bindings.
3. Startup must gate planner-driven dispatch until reconstruction is complete for its admitted scopes. Partial rebuild is not an empty graph or completed upstream node.
4. Preserve all still-active cohorts when publishing the latest snapshot. Do not overwrite old active runs with only the latest enqueue batch.
5. Query current execution progress each dispatch tick; do not treat stored mapping or unchanged counts as completion. Guard and atomic database claims remain mandatory.
6. Use bounded rebuild queries and diagnostics for missing parents, unmatched definitions, retained evidence, ambiguous binding and reconstruction failures.
7. If reconstruction fails, keep affected cohorts waiting. Historical FIFO behavior requires an explicit rollback decision and must retain guard/claim safety; it must not silently bypass enabled cohort barriers.

## Deferred Hardening

Consider durable binding identity/checkpoints or a dedicated persisted projection when current metadata cannot support restart recovery, evidence retention becomes costly, reconstruction latency is unacceptable, or multiple Platform instances are introduced. These are owner-gated follow-ups, not authorization to add persistence now. Single-instance operation reduces coordination requirements but does not remove restart/crash recovery requirements.

Generic static dependency snapshot extraction remains separately tracked in [TD-012](012-static-dag-dispatch-planner.md#deferred-generic-static-dependency-snapshot). Static dependency declarations and volatile parent bindings are different state.

## Contract Impact

| Area                        | Decision                                                                                                                                              |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka/protobuf              | No change from this record; recovery reuses existing commands/status identity. Any missing identity contract needs separate producer/consumer review. |
| Dataset manifests/readiness | Unchanged; existing guard remains the readiness and exact-version authority.                                                                          |
| Storage paths/ownership     | Unchanged.                                                                                                                                            |
| Public Java/Python API      | No new external API. Future internal recovery boundary requires caller review.                                                                        |
| Configuration/deployment    | Single-instance assumption must be documented when implemented; no setting changed here.                                                              |
| Persistence                 | No new table/status/migration authorized. Existing outbox/execution retention and reconstructability require review.                                  |

## Verification and Closure

Executable checks: NOT RUN. Static source inspection found durable execution-parent references, definition references, child work identity and a runKey fallback to trigger time; this does not establish cross-step cohort reconstruction.

Close only with attributable executed evidence for:

- restart after enqueue commit but before memory publication;
- transaction rollback without a ghost binding;
- published-but-running predecessors and completed upstream rows needed by pending downstream work;
- retention/cleanup boundaries, missing and ambiguous identity, and bounded fail-closed rebuild;
- concurrent memory updates, atomic snapshot replacement and preservation of old active cohorts;
- BANKS/REAL_ESTATE and date/run isolation, fan-out membership closure and failed upstream behavior;
- the 75/25/wait/advance trace before and after restart, with existing guard/claim/fencing preserved;
- reviewed operational recovery and rollback instructions plus synchronized Plan 030 and canonical documentation.

A documented risk may be deferred, but unsafe recovery or missing predecessor evidence cannot be waived by marking technical debt recorded.
