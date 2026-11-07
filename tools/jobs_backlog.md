# Local job backlog inventory

Agent workflow: use the shared
[`check-jobs-backlog` skill](../.agents/skills/check-jobs-backlog/SKILL.md)
to collect a fresh snapshot and summarize DB, outbox and Kafka separately.

Run once from the workspace root:

```bash
nx run omni:jobs-backlog
nx run omni:jobs-backlog --stale-minutes=60 --limit=50 --json
nx run omni:jobs-backlog --skip-kafka --json
```

Uses the existing local Docker CLI: PostgreSQL container `postgres`, database
`omni`, user `postgres`; Kafka container `local-kafka`, internal bootstrap address
`localhost:9092`. Override with `--postgres-container`, `--database`, `--db-user`,
`--kafka-container` and `--bootstrap-server`. The bootstrap address is resolved
inside the Kafka container. No password/DSN is accepted or printed; use the existing
container authentication. `--skip-db` and `--skip-kafka` explicitly omit a source.

The database query runs in one REPEATABLE READ READ ONLY transaction with statement
and lock timeouts. It groups PENDING/RUNNING histories by job type, status and
root/child role; shows age since `triggered_at`, counts past the review threshold,
unknown ages and a bounded oldest-execution sample. It includes outbox counts by
topic/status and published messages whose stored execution FK is nonterminal.
Root and child counts are separate; adding them is not a count of business jobs.
The sample limit bounds returned detail, not rows scanned by aggregate queries.

Works with the V16 execution/outbox schema as well as later migrations that retain
those tables. It does not inventory I9 request ownership or atomic-attempt states;
use the [P14 v2 pack](../database/diagnostics/p14/v2/README.md) for that inspection.
Legacy outbox FKs may point at a parent while the payload identifies a child:
the published/nonterminal count follows the stored FK, not decoded payload work.

Kafka output lists consumer-group partitions, committed offset, log end, lag and
current assignment. It includes every group with retained offsets, not just worker
commands. The same topic in two groups is two delivery backlogs; known lag totals
must not be interpreted as distinct messages or unfinished database executions.
Uncommitted/negative lag is unknown. No readable offsets, malformed output or
connection failure is unavailable, not zero backlog. Newly created groups without
offsets and deleted groups cannot be fully inventoried this way.

DB and Kafka observations are separate snapshots. Age is not a heartbeat or proof
of a hung worker. Dataset READY, input-date coverage, provider health, ownership and
deployment flags are not evaluated. Empty history/zero lag does not establish those
properties. A 30-minute threshold is diagnostic only, never an automatic failure.

Text is the default; `--json` returns section status, observation timestamps,
summary, bounded execution IDs and all parsed partition offsets. Exports omit raw
payloads/errors/metadata but contain operational identifiers; keep them private.
Exit 0 means requested reads succeeded (or were explicitly skipped), regardless of
backlog; exit 2 means at least one requested source was unavailable. Inspect section
status and unknown counters before using a report. No polling, cleanup, status
update, offset reset, provider request or service startup is performed.

Ownership: `tools/jobs_backlog.py`, the root `omni:jobs-backlog` target and offline
tests in `tools/tests/test_jobs_backlog.py`. Tests run through `nx run omni:tools-test`.
This tool does not close a P14 increment or implement the planned I10 watchdog.

Impact: Platform persistence is read through the existing execution/outbox tables;
Kafka is described through the existing local broker CLI. Aggregate queries may scan
history, bounded by timeouts. Analyzer/Ingestor/Query Service/Console runtime,
shared libraries/contracts, datasets, migrations and deployment configuration are
unchanged. The root Nx target invokes the existing Python launcher, with no changes
to other targets. No application watcher, writer or consumer is started.

Local verification on 2026-10-31: `nx run omni:tools-test` passed 25 tests,
including 11 backlog-tool cases. `nx run omni:jobs-backlog` read the local DB and
broker successfully: zero unfinished histories, zero known lag, zero unknown-lag
partitions at that observation. No build/lint/format, CI or I9 ownership/readiness
verification is claimed. On Windows, `nx.cmd` invokes the same targets if PowerShell
execution policy blocks the `nx.ps1` shim; no policy change is needed.
