---
name: check-jobs-backlog
description: Collect and summarize Omni job backlog from PostgreSQL execution/outbox state and Kafka consumer lag using the existing jobs-backlog Nx target. Use for current backlog or queued/stale job reports, with optional active-definition and failure summaries when requested. Read-only; cleanup and configuration changes require a separate explicit request.
---

# Check Jobs Backlog

Use the existing [tool and interpretation guide](../../../tools/jobs_backlog.md).
Follow [AGENTS.md](../../../AGENTS.md). This workflow observes runtime state; it
does not run application tests or establish P14 increment acceptance.

## Collect a current snapshot

Run once from the repository root:

```bash
nx run omni:jobs-backlog --json
```

On Windows, use `npx.cmd nx run omni:jobs-backlog --json` if needed. Set
`NX_NO_CLOUD=true` and `NX_DAEMON=false` for the local invocation. Prefer this
target over rebuilding the inventory from Docker commands or ad hoc SQL.
The target already uses local Docker for its PostgreSQL and Kafka reads.
If Nx returns a running session, poll that session; do not launch duplicate
inventories while it is still running.

Use the tool's documented connection, sample-limit, threshold and skip options
only as the request requires. Defaults use a 30-minute review threshold and a
20-execution oldest sample. Do not silently skip an unavailable source.

Inspect each section's `status` before interpreting numbers. `unavailable` is
unknown, and `not_checked` is omitted, not zero. Exit 0 means reads succeeded or
were explicitly skipped; it does not mean no backlog. Exit 2 means a requested
source was unavailable. Report usable partial results and the missing source.
With intentional skips, `complete=false` alone does not mean the attempted read
failed. Include each available `observedAt` in the summary, preferably in
Asia/Ho_Chi_Minh; DB and Kafka timestamps describe separate snapshots.

If Docker access is denied, use the environment's approval mechanism for the
same read-only target. Do not restart services, change permissions or substitute
old observations for a fresh measurement.

## Summarize the observations

Lead with whether there is unfinished DB work, Kafka lag, or unavailable data.
Keep the response in the user's language and cover:

- **DB:** `unfinishedCount`, `staleCount`, `unknownAgeCount`; break down nonzero
  groups by job type/status and distinguish root from child. Their sum is an
  execution count, not a count of independent business jobs. Age is since trigger,
  not a heartbeat; exceeding the threshold does not prove a hung or failed worker.
- **Outbox:** group non-published rows by topic/status. PENDING intent may still
  wait on dependency guards, a lease or retry delay. Separate it from BLOCKED
  history, and show published/nonterminal execution
  counts when relevant. Do not add outbox totals to DB or Kafka totals.
- **Kafka:** `knownLag`, `unknownLagPartitions`, nonzero lag by group/topic and
  whether consumers are active. Summing partitions within a group/topic is valid;
  the same message delivered to multiple groups is multiple delivery backlogs.
- **Limits:** lag zero does not prove successful execution, dataset READY or
  provider health. Active consumers do not mean job definitions are enabled.
  Historical BLOCKED messages can remain while there is no pending backlog.

Show a table only when several nonzero groups/topics need comparison. Mention
sample truncation when using execution details. If a prior snapshot is available,
label any comparison with both observation times; do not infer a processing rate
from one snapshot. Keep operational exports local in ignored `.agent/reports`
when requested; omit credentials and raw payloads.

## Optional follow-up observations

The backlog tool does not report failed-job rates or active definitions. Query
those separately only when requested, using the existing local DB connection and
read-only transactions with statement and lock timeouts. Inspect unfamiliar
schema/source through the repository graph/navigation workflow first.

- **Enabled jobs:** report `job_definitions.is_active` and `schedule_enabled`
  separately, grouping by job type. Do not infer either field from consumer state.
- **Failures:** state the date window and timestamp used. Define the failure-rate
  denominator, normally FAILED / (SUCCESS + FAILED), and separate root/child and
  unfinished executions. Identify operator cancellations such as
  `OPERATOR_CANCELLED` rather than treating them as provider/algorithm failures.
- **Top errors:** specify whether top means most frequent error groups or most
  recent failed executions. For this week, start Monday 00:00 Asia/Ho_Chi_Minh.
  Use bounded details and deterministic ordering; avoid publishing secrets in
  errors. When recent failures are dominated by operator cancellation, report it
  explicitly and provide a separately labelled processing-failure view if useful.
- **P14 work/READY:** the tool does not inventory atomic requests, attempt ownership
  or scoped readiness. Use the [P14 diagnostics pack](../../../database/diagnostics/p14/v2/README.md)
  and current identity handoff only when that deeper inspection is requested.

Do not mark jobs FAILED, toggle definitions, mutate outbox, reset offsets, delete
messages/tables, apply migrations or start services as part of this skill.
A later explicit remediation request has its own scope and authorization; the
observation skill itself supplies none.
