# TD-018 - Deferred Execution Watchdog During Observation

## Status

DEFERRED / owner-accepted on 2026-11-11. Automatic execution expiry is temporarily
disabled while the owner observes whether it is operationally necessary. It is
not a blocking acceptance requirement of the current P14-I10 scope. Priority is
P2 conditional on observed stalled work, or P1 when a concrete correctness impact
is established. This record is not permission to activate the watchdog.

## Decision and evidence

The existing ExecutionTimeoutWatchdog expires child executions after a deadline
measured from outbox published_at, including time waiting for a worker or for
Platform to consume its completion. Queue delay is not proof that a worker failed.
Its scheduled bean is conditional on app.output-registration.watchdog-enabled=true;
the source default is false. Root local and environment examples now explicitly set
APP_OUTPUT_REGISTRATION_WATCHDOG_ENABLED=false. The source implementation, tests,
timeout history and operator diagnostics are retained.

The owner accepts the old full SYNC_METADATA scan duration as outside the current
PostgreSQL-readiness observation path; it is no longer a poll-window blocker for
this scope. This decision does not claim coordinated reader cutover is complete,
disable PostgreSQL reconciliation or remove SYNC_METADATA from the catalog.

## Accepted observation boundary

- Do not schedule automatic FAILED/EXECUTION_TIMEOUT transitions. Worker-reported
  success/error and validated completion/READY transactions remain active.
- A worker that dies before acknowledging completion may leave PENDING/RUNNING
  work indefinitely. Investigate and resolve it manually during observation; age
  alone does not justify failure, retry or an assumed worker shutdown.
- Preserve immutable event/attempt identity, frozen upstream approvals, exact
  output-version validation and READY-last. Do not reopen historical FAILED rows.
- Disabling expiry does not revoke workers, cancel provider/storage calls or remove
  Kafka backlog. Conflicting late completion/poison-record behavior is a separate
  correctness issue and remains unresolved by this decision.
- Existing client/network timeouts, outbox claims/leases, consumer membership and
  the Python 15-minute max-poll interval are unchanged. Sequential processing and
  durable worker recovery remain deferred separately.

## Completion delivery and storage commit boundaries

Recorded on 2026-11-12 at the owner's request. Track these two distinctions when
investigating registration/completion delays and planning recovery:

1. **Broker ACK is not Platform DB commit.** For WORKER_COMPLETION_V1, the worker
   waits for Kafka to acknowledge the combined terminal status and registration.
   Platform may consume it later. A finished worker can therefore still appear
   RUNNING in the UI during completion-consumer backlog. Keep worker completion,
   broker acceptance and Platform commit distinct in diagnostics; queue delay
   alone must not trigger failure, retry or a fabricated SUCCESS/READY transition.
2. **Parquet and PostgreSQL do not share a transaction.** The worker writes and
   reads back Parquet before publishing completion. A crash, delivery failure or
   delayed/failed Platform transaction can leave physical output without committed
   registration/READY. Platform atomically commits registration, terminal status
   and eligible READY within PostgreSQL; it cannot roll back the preceding object
   write. File existence alone must never grant READY or prove execution SUCCESS.

Deferred follow-up: use existing execution/attempt/event identities to distinguish
the delivery stages and investigate unfinished work. Durable worker publication
recovery and safe handling of unregistered object versions remain separately scoped
under [TD-009](009-python-kafka-worker-throughput-and-offset-safety.md). Any future
recovery/cleanup slice must demonstrate crash/replay and DB-outage behavior without
changing accepted versions, frozen lineage or terminal attempts, and without
deleting still-referenced bytes. This note authorizes no retries, garbage collection,
new statuses, tracing rollout or watchdog activation. Existing I10 completion/READY
and coordinated-cutover acceptance requirements remain unchanged.

Evidence: [completion ordering](../reference/018-i10-worker-completion.md#ordering-and-transaction),
[worker publication](../../libs/py-common/py_common/messaging/output_publication.py),
[transactional completion](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/WorkerCompletionStore.java).
Documentation-only recording; no tests or runtime changes were performed.

## Revisit triggers and candidate scope

During real requests, retain existing request/execution IDs and logs, pending/running
ages, worker receipt/completion evidence and consumer lag. Choose the observation
period with the owner; no unsupported duration or heartbeat is assumed here.
Revisit when healthy queues repeatedly leave work without completion after worker
loss, manual recovery becomes frequent, or the owner requests automatic expiry.

If reactivated, distinguish queued work, running work and completion-consumer delay.
Define a worker-liveness signal and a separate bounded policy for each phase. A
timeout cannot prove write ownership is released; safe retries require immutable
versions or proven writer exclusion. Reconcile duplicate/late completion and manual
recovery before enabling automatic failure. Design changes require owner approval.

## Future acceptance

Require clock-controlled deadline tests, real queue-delay/worker-loss/rebalance and
late-completion regressions, multi-instance locking/transaction coverage and a
documented staged enable/rollback procedure. Existing source/tests do not prove
that automatic expiry is necessary or that current published-based expiry is safe
to enable. Do not delete failing recovery tests to close this deferred item.

## Ownership and evidence boundary

Platform owns watchdog scheduling/failure aggregation; current configuration and
operations keep it off. Python workers, Query, Console, shared Kafka contracts,
storage writers/readers, dataset ownership, paths and database schema are unchanged.
Existing diagnostics remain available; no watcher, heartbeat, tracing or migration
is added. Historical local watchdog test evidence remains historical. This change
uses static flag/source/document inspection only; no tests, live restart or runtime
bean-disable evidence is claimed. A running process with a prior explicit true
override needs operator restart/reconfiguration before this file change affects it.

Documentation reconciliation updates Plan 030/current registry scope, ADR-008,
architecture, flow, deployment, Platform guidance, current I10 handoffs, release
notes and debt indexes/priority diagrams. Kafka/data-lake/database documents,
Python/Query/Console guides, ownership, repository guidance and generated contracts
need no policy/schema update: completion validation, physical ownership and all
message/read contracts are unchanged. Existing tests and failed late-recovery
evidence are retained; no check manifest is weakened or asserted passing.

References: [Plan 030](../plans/030-static-graph-dispatch-planner.md),
[completion protocol](../reference/018-i10-worker-completion.md),
[rollout](../deployment/004-job-run-admission-rollout.md),
[watchdog source](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/ExecutionTimeoutWatchdog.java),
[TD-009](009-python-kafka-worker-throughput-and-offset-safety.md),
[TD-010](010-kafka-poison-record-and-dead-letter-policy.md).
