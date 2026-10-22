# TD-017 — Deferred Manual Triggering and Request Audit

## Status

**DEFERRED / accepted for the current phase by owner decision, 2026-10-21.**
Priority: **P3 conditional** on an approved operational need for durable rejection
history. Nonblocking for the current P14-I3.d slice; not a new roadmap increment.
The owner subsequently deferred manual triggering itself. I3.d withdrawal source
passed its local seven-check gate on 2026-10-22; deployment remains pending. The table is removed only
when V14 is applied, not merely by editing migration source.

## Current Evidence

Historical V8 `manual_job_triggers` stored REQUESTED, ACCEPTED, BLOCKED, CONFLICT
and FAILED, with rejection/error/resolution details and an optional execution ID.
I3.d removes its service/entity/repository and Console actions. Old POST trigger
and GET request-audit routes return authenticated HTTP 410 with an explicit code;
read-only catalog and execution status remain.

`job_runs` already stores actor, request time, mode, idempotency key, reason,
parameters, target definition, run state and failure reason. V12 requires frozen
path/step membership before commit; V13 closes prepared execution/message
membership. `reason` is the operator's request reason, not a rejection reason.
`failure_reason` describes a run failure and does not by itself model a request
rejected before a valid run exists.

Source: [historical V8](../../database/migrations/V8__create_manual_job_triggers.sql),
[V14 removal](../../database/migrations/V14__drop_deferred_manual_job_triggers.sql),
[operator controller](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/controllers/JobOperationsController.java),
[V12](../../database/migrations/V12__create_job_runs_and_frozen_steps.sql),
[V13](../../database/migrations/V13__seal_job_run_execution_membership.sql).

## Accepted Current-Phase Boundary

- Defer durable standalone audit for requests that never produce a valid committed
  JobRun, including rejection reason/time and a durable audit-only request ID.
- Do not extend JobRun with unsealed request-only rows, nullable fake paths or
  fabricated successful executions solely to replace this legacy audit feature.
- Old operator trigger/poll routes return explicit sanitized HTTP 410 reasons
  behind existing authentication; they never accept work. Future reactivation
  requires reviewed authorization, actor isolation, allow-list and validation.
- Preserve committed-run idempotency, frozen membership, rollback, retry/fencing,
  and run/execution failure diagnosis. These are active correctness requirements.
- Operator job triggering, its audit/polling and metadata-sync buttons are deferred
  by explicit owner decision. Internal MANUAL JobRun preparation is not an exposed
  feature; automatic scheduler/FIFO/guard remain active.
- Old audit-history deletion remains an explicit irreversible consequence if its
  table is later dropped. Preserve applied migration checksums and document rollout.

Owner acceptance supersedes the earlier I3.d proposal to expand JobRun's request
lifecycle as a prerequisite. [Plan 030](../plans/030-static-graph-dispatch-planner.md)
owns the current implementation/rollout boundary. Preparation stays false until
I4 admission safety is proved; this audit debt does not authorize activation.

## Activation and Closure

Promote one bounded slice when the owner reactivates manual job triggering or
requires searchable rejection history,
durable polling/replay of audit-only request IDs, or diagnosis across process
restarts that cannot be satisfied by existing sanitized errors/logs.

Before implementation, approve storage ownership and whether request audit is
separate from executable JobRun state. Define bounded/sanitized fields, reason
codes, retention, actor-scoped access and idempotency policy. Add migration, API/
Console compatibility and transaction/concurrency tests for accepted/rejected/
failed requests, duplicates, crash recovery and access isolation. Do not weaken
sealed JobRun invariants or claim execution success from request acceptance.

## Impact and Verification

Future ownership is Platform request/audit persistence and its operator API, with
Console polling/DTO compatibility. No current Kafka/protobuf, Python worker,
Query Service, dataset/path/ownership, READY-last, exact lineage or dispatch
contract change. I3.d withdrawal passed local verification: 341 Platform tests,
27 Console tests, attributable controller/catalog coverage and executed V14
upgrade/startup cases; see Plan 030. No retained-environment migration, deployment,
provider or runtime activation evidence is claimed by this debt record.
