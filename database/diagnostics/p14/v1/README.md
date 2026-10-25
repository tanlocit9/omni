# Scheduler diagnostics v1

Owner: [Plan 030 / P14-I5](../../../../docs/plans/030-static-graph-dispatch-planner.md).
These SELECT-only scripts observe persisted state. They do not claim messages,
repair data, evaluate dataset READY, publish, or activate JobRun preparation.

## Safe execution

Use a SELECT-only connection/role and a repeatable-read, read-only transaction.
Set `statement_timeout=5s`, `lock_timeout=500ms` and `ON_ERROR_STOP=1`. A timeout,
parse error, missing run or incomplete window is not evidence of zero backlog or
successful completion. Begin with one run or a small legacy page on a quiet system.
No retained environment is accessed by implementation tests or benchmarks.

Example with a locally configured libpq service (keep credentials out of commands,
reports and this repository):

```powershell
$env:PGOPTIONS = '-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=500'
psql 'service=omni-diagnostics' -X --single-transaction --set=ON_ERROR_STOP=1 --set='run_ids=["RUN_UUID"]' --set='observed_at=2035-01-02T12:00:00Z' -c 'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY' -f database/diagnostics/p14/v1/run_steps.sql
psql 'service=omni-diagnostics' -X --single-transaction --set=ON_ERROR_STOP=1 --set='after_id=' --set='row_limit=100' --set='observed_at=2035-01-02T12:00:00Z' -c 'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY' -f database/diagnostics/p14/v1/legacy_outbox_inventory.sql
```

Replace RUN_UUID with an actual UUID. `observed_at` is the explicit observation
clock, used for lease/availability classification; it is not worker start time.
The transaction controls are part of the invocation, not mutations hidden in SQL.
Tests bind the same psql quoted-value placeholders using JDBC parameters inside
an actual read-only transaction. Do not concatenate unquoted SQL input.

## Query contracts and bounds

| Script                      | Parameters / output cardinality                                                                                                                    | Interpretation and limits                                                                                                                                                                                                                                                                                                                  |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| run_steps.sql               | JSON array of 1–32 distinct existing run UUIDs; one envelope with at most 1024 step rows, at most 8192 predecessor edges; explicit UTC observed_at | Missing/duplicate/oversized run windows are incomplete. Invalid JSON/UUID/time fails. Separate execution and outbox aggregates prevent message fan-out multiplying execution counts. Closed expected counts, identities, failure/unknown state, transitive predecessor waits and published-but-unfinished evidence are independent fields. |
| legacy_outbox_inventory.sql | after_id empty/UUID, row_limit clamped to 1–400, observed_at; one envelope with at most row_limit rows plus a one-row lookahead                    | UUID keyset over all delivery states. hasMore/nextCursor describe only this page; no global zero-alias claim. Rows retain message/work IDs for bounded operator investigation; raw payload and credentials are not exposed.                                                                                                                |

Both scripts return `version=1`. An incomplete run envelope cannot authorize
admission. A complete envelope means the requested structural window was observed,
not that every step is SUCCESS or every dataset is READY. `progress` reflects
execution/membership only; DependencyRegistry retains READY/exact-version authority.

Run membership scans use exact run/step indexes and aggregate all associated work;
the result/parameter limits do not guarantee a fixed number of database pages.
Large fan-out can still be expensive. Review controlled query plans and buffers
before widening a sample. Do not force indexes or invent a universal latency limit.

Inventory states distinguish current identity, a validated historical alias,
malformed/non-object JSON, missing/invalid work ID, missing work execution, linked
run/parent/definition/payload-parent conflicts and projected unique-key collision.
Collision checks include messages outside the current page. A legacy alias is
evidence for I7 investigation, not permission to rewrite it. Live lease, terminal
delivery state and row count must be reconciled under I7's writer-quiescence policy.
Paging separate snapshots while writes continue is not an atomic global inventory.

## Query plans and measurement

Run `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` only on the SELECT statement, with
the same parameters, read-only transaction and timeouts. ANALYZE executes the
query. Never apply this to UPDATE/DELETE/claim SQL on a retained environment.
Claim benchmarking belongs only in the disposable harness, where rollback and
synthetic state transitions cannot affect retained work.

Record PostgreSQL version, fixture/dataset sizes, state distribution, indexes,
environment, sample clock/window, warmups and sample count alongside planning/
execution time, actual rows/loops, buffer hits/reads and index/scan nodes. Compare
the same workload and input against the production snapshot/scoped SELECT or
documented FIFO reference. Differences are measurements, not SLA/pass thresholds.
`pg_stat_statements` is optional and is not required by this pack.

See the [metric/read-projection dictionary](metrics.md),
[measured report](benchmark-results.md) and [source-bound JSON](benchmark-results.json).
Reproduce with `nx run platform:test --args="--tests=*JobRunDispatchBenchmarkPostgresTest"`
from workspace root. The harness uses five warmups and 30 measured samples,
six synthetic workloads, test-only JDBC/pool instrumentation, actual query plans
and rollback-only claim measurement. Fake Kafka and synthetic applyStatus calls
do not measure real worker/provider processing. It is included in the full Platform
regression, never an automatically executed retained-database benchmark.

## Compatibility and recovery

Requires the existing V12/V13 schema and PostgreSQL 16 (`IS JSON OBJECT` safely
guards payload parsing). No migration, extension, role, index, metric persistence,
worker contract or runtime configuration is added. Remove the diagnostic client
session to stop using the pack; no data rollback is needed. Do not edit applied
migrations, fabricate execution SUCCESS or infer dataVersion/lineage from these
observations. Physical history deletion and compatibility retirement belong to I7.
