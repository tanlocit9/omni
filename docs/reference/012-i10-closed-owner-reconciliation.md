# I10 closed-owner reconciliation checkpoint

Source follow-up: 2026-11-07. Focused local PostgreSQL regressions passed;
P14-I10 remains in_progress and its complete acceptance/cutover gate is open.
This checkpoint is not durable worker recovery, automatic retry or a new status
state machine. No migration, service restart or live cleanup was run in this task.

## Ownership and settlement

`GraphWorkRequestService.acquire` shares an unfinished attempt only if a requester
still owns it in PREPARING/PREPARED. Canonical work identity and the exact frozen
upstream-attempt list must still match. New requests create new attempts after all
owners close; old terminal requests, membership and lineage remain immutable.

The existing graph/backfill reconciler examines at most 100 orphan candidates
per tick. A candidate must be sealed, nonterminal, have a SUCCESS/FAILED/CANCELLED
owner and no PREPARING/PREPARED owner. Automatic settlement additionally requires:

- Exactly one outbox command, PENDING, attempts=0, published_at NULL.
- No claim token, claim owner or lease, including expired leases.
- No workerStarted receipt, registration, completion SUCCESS or timeout record.

Reconciliation takes the same work-slot lock as acceptance, skips busy slots and
rechecks eligibility in the UPDATE after locking. Outbox becomes BLOCKED with
REQUEST_CLOSED; execution becomes FAILED with finished_at and REQUEST_CLOSED in
the same transaction. Existing bounded propagation then settles atomic parents
and JobRuns over subsequent ticks. Request history, registrations and READY proofs
are unchanged. Repeated ticks are idempotent; shared work with another live owner
is preserved. The existing graph/backfill gate must be enabled for maintenance.

Attempted commands, lost ACKs, published commands, receipts, registrations,
completion observations and timeout ownership remain for review. Upstream-failure
propagation uses the same never-dispatched fence, preventing another reconciliation
branch from immediately bypassing it. PENDING/expired lease/zero lag do not prove
that the worker never received a command. No worker is killed.

## Additive request diagnostics

`GET /api/v1/jobs/atomic-work/requests/{id}` preserves status, counts and isSettled.
Each member adds:

| Field | Meaning |
| --- | --- |
| executionStatus | Existing persisted attempt status; never overwritten by a later attempt's success |
| outputReady | Current scoped work catalog covers declared logical outputs; null if neither declaration nor current work output exists |
| readyOutputs | Current dataset/partition/dataVersion/businessDate plus source executionId/attemptId |
| reasonCode | Persisted REQUEST_CLOSED or ATOMIC_UPSTREAM_FAILED, otherwise null |
| orphaned | Nonterminal attempt has no PREPARING/PREPARED requester |
| requiresExecutionReview | Nonterminal orphan lacks never-dispatched proof, or execution has timeout ownership uncertainty even if already terminal |

A failed attempt may report outputReady=true with readyOutputs identifying a
newer attempt for the same atomicWorkId. This does not certify the failed attempt's
own output, alter frozen approvals or grant dispatch admission. The query uses
output_work_ready_catalog, including current row ownership; invalidated overlapping
proofs disappear. It does not substitute latest SUCCESS/whole-file pointers for
READY and does not perform a fresh Parquet/provider read.

## Impact and regression inventory

| Source / safety branch | Regression in GraphWorkRequestsPostgresTest | Executed now |
| --- | --- | --- |
| Owner-aware acquire and terminal history | closedOwnerAttemptsAreReplacedThenSafelySettledWithoutReopeningHistory: FAILED/CANCELLED/SUCCESS owners | PASS |
| Shared ownership remains usable | overlappingRequestsShareIndependentAncestorRunsAndCancellationKeepsOtherOwner; final owner closure now requires new attempts | PASS |
| Claim, expired lease, ACK loss, publish, receipt, timeout, registration, completion remain untouched | orphanWithAnyDispatchEvidenceIsPreservedForReviewEvenWhenItsUpstreamFails | PASS |
| Acceptance lock contention and later settlement | reconciliationSkipsSlotsLockedByAcceptanceAndClosesThemOnALaterTick | PASS |
| Transaction rollback preserves execution and outbox together | orphanOutboxAndExecutionUpdatesRollBackTogether | PASS |
| Old FAILED versus newer exact-work READY; invalidated row proof | failedAttemptStaysFailedWhileSameWorkHasReadyOutputFromALaterAttempt | PASS |

Graph impact found two HTTP callers/four impacted nodes and 196 unresolved call
sites. Direct cross-service reconciliation, rather than graph reachability alone:

- Platform changes acquire, maintenance, diagnostics and PostgreSQL regression
  fixtures. Existing slot/outbox/execution objects and authenticated API are used.
- Analyzer/Ingestor have no worker/provider/status change. Their publication and
  receipt evidence prevents unsafe settlement. Query keeps its READY reader.
- Console does not consume this request-member DTO; no UI/source change. External
  operator clients receive additive fields. No contracts/protobuf/Kafka change.
- Shared Python/configuration, storage paths, dataset/writer ownership, READY-last
  authority and frozen lineage are unchanged. No enum, migration or new flag.
- Updated Platform can settle eligible historical never-dispatched orphans when
  the existing reconciliation gate is enabled. Uncertain commands require review;
  no automatic retry, service start, live SQL cleanup or Kafka reset is included.

Owner-authorized local run: GraphWorkRequestsPostgresTest 68 passed,
IndicatorBackfillControllerTest 21 passed and WorkExpansionTest 9 passed, no skips
or failures. Results are recorded as integration/postgres-policy under
`P14-I10-policy-integration-20261107` through tools/check_result.py. Tests used
Testcontainers and an ignored Gradle init script/build directory, preserving the
normal devtools classpath. The executed Nx target used this filter plus the
controller and expansion filters, isolated-build init script and --rerun-tasks:

```text
npx.cmd nx run platform:test --skip-nx-cache --args="--tests com.omni.platform.GraphWorkRequestsPostgresTest"
```

Focused JaCoCo: GraphWorkRequestService 98.10% line/86.50% branch; inspect and
reconcileOrphanedAttempts both 100% line/branch. SQL predicates are exercised by
the named PostgreSQL cases, not numerically instrumented SQL coverage. Reports
are retained in the gate's ignored artifacts/postgres directory and
artifacts/postgres-jacoco.xml. No lint/format, separate build target, live cleanup
or deployment was run. Broader coverage and full I10 acceptance remain required.

## Documentation disposition

Plan 030's earlier ownerless reattachment wording is superseded. Registry,
roadmap, release notes, docs index, flow, database, deployment, service and prior
handoffs link this checkpoint. Architecture/ADR-008, Kafka/data-lake, ownership
and agent rules need no new policy: authority, paths, schemas and deferred recovery
are unchanged. This is a checkpoint under I10, not another roadmap increment.
Static changed-scope review covers links/reference numbers, unchanged I10 status,
additive diagnostics and verification wording; it is not full-repository audit,
executed acceptance, deployment or runtime evidence.
