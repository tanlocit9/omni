# Scheduler benchmark observation — v1

This is a source-bound local experiment, not a production SLA or performance PASS threshold.

Window: 2026-10-25T11:37:42.855141600Z to 2026-10-25T11:38:03.122529500Z. 5 warmups and 30 measured samples per repeated stage; nearest-rank percentiles. p99 is the maximum of 30 observations.

Environment: Windows 11 / Java 21.0.12.1, 16 reported processors, heap max 536870912 bytes; PostgreSQL 16.14 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit. Hikari max/min idle 10/10.

Dataset: synthetic GLOBAL/VN frozen three-step runs on business date 2035-01-02. No provider or retained environment is accessed. Test-only instrumentation overhead is included.

| Scenario                | Runs / child per step | Planner result |
| ----------------------- | --------------------- | -------------- |
| one-run-100             | 1 / 100               | PLANNED        |
| 32-runs-10              | 32 / 10               | PLANNED        |
| join-one-parent-success | 1 / 20                | PLANNED        |
| retry-and-live-lease    | 1 / 20                | WAIT           |
| unknown-closure         | 1 / 0                 | INCOMPLETE     |
| failed-predecessor      | 1 / 20                | WAIT           |

## Measured elapsed time and database-call boundary

| Scenario / stage                                     | p50 ms |  p95 ms |  p99 ms | JDBC execute calls/sample | Commit+rollback calls/sample |
| ---------------------------------------------------- | -----: | ------: | ------: | ------------------------: | ---------------------------: |
| one-run-100 / runDiscovery                           |  1.527 |   1.965 |   2.022 |                      1.00 |                         1.00 |
| one-run-100 / snapshot                               |  4.460 |   4.993 |   5.665 |                      3.00 |                         1.00 |
| one-run-100 / planner                                |  0.091 |   0.159 |   0.166 |                      0.00 |                         0.00 |
| one-run-100 / scopedCandidateRead                    | 12.742 |  15.689 |  16.315 |                      2.00 |                         1.00 |
| one-run-100 / fifoCandidateReadReference             | 93.117 | 100.655 | 102.391 |                    151.00 |                         1.00 |
| one-run-100 / claimRollback                          |  7.317 |   8.089 |   8.178 |                     10.00 |                         1.00 |
| 32-runs-10 / runDiscovery                            |  2.244 |   2.754 |   2.782 |                      1.00 |                         1.00 |
| 32-runs-10 / snapshot                                |  7.111 |   8.032 |   8.220 |                      3.00 |                         1.00 |
| 32-runs-10 / planner                                 |  0.314 |   0.429 |   0.566 |                      0.00 |                         0.00 |
| 32-runs-10 / scopedCandidateRead                     |  5.698 |   6.257 |   6.446 |                      2.00 |                         1.00 |
| 32-runs-10 / fifoCandidateReadReference              | 79.438 |  89.282 |  90.070 |                    151.00 |                         1.00 |
| 32-runs-10 / claimRollback                           |  6.413 |   7.331 |   7.411 |                     10.00 |                         1.00 |
| join-one-parent-success / runDiscovery               |  1.219 |   1.503 |   1.510 |                      1.00 |                         1.00 |
| join-one-parent-success / snapshot                   |  3.614 |   3.942 |   4.099 |                      3.00 |                         1.00 |
| join-one-parent-success / planner                    |  0.039 |   0.065 |   0.086 |                      0.00 |                         0.00 |
| join-one-parent-success / scopedCandidateRead        |  3.733 |   4.333 |   4.706 |                      2.00 |                         1.00 |
| join-one-parent-success / fifoCandidateReadReference | 58.935 |  62.587 |  63.013 |                    121.00 |                         1.00 |
| join-one-parent-success / claimRollback              |  6.047 |   6.453 |   6.508 |                     10.00 |                         1.00 |
| retry-and-live-lease / runDiscovery                  |  0.918 |   1.143 |   1.162 |                      1.00 |                         1.00 |
| retry-and-live-lease / snapshot                      |  3.593 |   3.912 |   3.961 |                      3.00 |                         1.00 |
| retry-and-live-lease / planner                       |  0.006 |   0.009 |   0.029 |                      0.00 |                         0.00 |
| unknown-closure / runDiscovery                       |  0.941 |   1.089 |   1.141 |                      1.00 |                         1.00 |
| unknown-closure / snapshot                           |  3.303 |   3.699 |   3.824 |                      3.00 |                         1.00 |
| unknown-closure / planner                            |  0.001 |   0.004 |   0.022 |                      0.00 |                         0.00 |
| failed-predecessor / runDiscovery                    |  1.142 |   1.501 |   1.673 |                      1.00 |                         1.00 |
| failed-predecessor / snapshot                        |  3.584 |   3.947 |   3.974 |                      3.00 |                         1.00 |
| failed-predecessor / planner                         |  0.004 |   0.005 |   0.015 |                      0.00 |                         0.00 |

The FIFO reference uses the same fixture/input state and candidate limit as the scoped read, but has different selection semantics. Only the one-run-100 case returns 75 rows from both readers; do not infer equivalent cohort safety or a production speedup. Other returned cardinalities are recorded in JSON. Claim includes rollback and closed-transitive admission; planner is pure and should issue no database calls.

Application database-call/round-trip boundary is JDBC execute calls plus commit/rollback calls. It is not a TCP packet or wire-protocol message count. Hikari poolNow is the end-of-window gauge; poolPeak fields are independent maxima and cannot be added together.

## Synthetic stage window

Counters: `{'plannedQuota': 300, 'registryVisited': 300, 'fakeBrokerAcknowledged': 300, 'offeredCandidates': 375, 'claimed': 300, 'syntheticChildStatusSuccess': 300}`.

Elapsed 5.325 seconds; fake publish and synthetic child-status rate 56.340 events/second over the same combined orchestration/status window.

The real dispatcher, registry, claim, fencing and JobService.applyStatus execute; Kafka acknowledgement is mocked and worker completion is injected. These rates do not measure broker capacity, provider processing or actual worker start/completion. Offered candidates count repeated tick offers, not distinct message identities.

## Controlled pool pressure

4 concurrent snapshot requests wait while the pool's 10 slots are held. One slot is released after an observed waiter and a controlled 30ms hold. Pool observations and acquisition distributions are in JSON; this is intentional saturation, not normal workload pressure.

Observed peak pool: `{'idle': 10, 'waiting': 3, 'active': 10, 'total': 10}`.

## SQL plan evidence

| Source query / scenario                      | Root rows | Root hit/read buffers | Planning ms | Execution ms | Scan/index nodes                                                                                                                                                                                                               |
| -------------------------------------------- | --------: | --------------------- | ----------: | -----------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 32-runs-10-claim-update-explain              |         0 | 22 / 0                |       0.053 |        0.148 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                                         |
| 32-runs-10-diagnostic-explain                |         1 | 3718 / 0              |       2.261 |       29.764 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| 32-runs-10-fifo-explain                      |        75 | 21 / 0                |       0.091 |        0.077 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                               |
| 32-runs-10-inventory-explain                 |         1 | 700 / 0               |       0.799 |       15.793 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| 32-runs-10-scoped-explain                    |        10 | 3259 / 0              |       0.217 |        2.814 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                                        |
| 32-runs-10-snapshot-explain                  |        96 | 3715 / 0              |       0.530 |        4.230 | Aggregate, CTE Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Result, Seq Scan, Sort; idx_execution_job_run_step, uq_scheduler_outbox_execution_message                                                                |
| failed-predecessor-diagnostic-explain        |         1 | 143 / 0               |       2.056 |        2.884 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| failed-predecessor-inventory-explain         |         1 | 278 / 0               |       0.660 |        7.594 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| join-one-parent-success-claim-update-explain |         0 | 13 / 0                |       0.056 |        0.111 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                                         |
| join-one-parent-success-diagnostic-explain   |         1 | 145 / 0               |       2.188 |        2.888 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| join-one-parent-success-fifo-explain         |        60 | 32 / 0                |       0.061 |        0.066 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                               |
| join-one-parent-success-inventory-explain    |         1 | 272 / 0               |       0.596 |        8.209 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| join-one-parent-success-scoped-explain       |        20 | 246 / 0               |       0.197 |        0.388 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                                        |
| join-one-parent-success-snapshot-explain     |         3 | 149 / 0               |       0.427 |        0.473 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                               |
| one-run-100-claim-update-explain             |         0 | 19 / 0                |       0.050 |        0.124 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                                         |
| one-run-100-diagnostic-explain               |         1 | 942 / 0               |       3.842 |        3.504 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| one-run-100-fifo-explain                     |        75 | 72 / 0                |       0.062 |        0.079 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                               |
| one-run-100-inventory-explain                |         1 | 698 / 0               |       1.143 |       16.929 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| one-run-100-scoped-explain                   |        75 | 1269 / 0              |       0.343 |        3.120 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                                        |
| one-run-100-snapshot-explain                 |         3 | 946 / 0               |       0.794 |        1.586 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                               |
| retry-and-live-lease-diagnostic-explain      |         1 | 143 / 0               |       1.988 |        2.694 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| retry-and-live-lease-inventory-explain       |         1 | 360 / 0               |       0.565 |        8.028 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| unknown-closure-diagnostic-explain           |         1 | 15 / 0                |       2.303 |        2.619 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| unknown-closure-inventory-explain            |         1 | 1 / 0                 |       0.553 |        0.438 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |

These are observed plans on small synthetic tables. Sequential scans can be rational; no index or cost threshold is forced. Root counters are not sums across nested plans. Mutation EXPLAIN uses the actual repository claim UPDATE only inside a disposable rollback transaction; no claim attempt persists. Raw plans are regenerated under apps/core/build/reports/scheduler-benchmark/.

## Reproduction and limitations

From workspace root: `nx run platform:test --args="--tests=*JobRunDispatchBenchmarkPostgresTest"`. Docker/Testcontainers and existing Platform dependencies are required. Full verification additionally runs the diagnostic SQL suite, clean Platform build and coverage. Output numbers vary by machine and load; the committed JSON records this observation, its exact SQL/test/production source hashes and the complete measurement dictionary.

Hashes bind exact local checkout bytes, including line endings. Additional unchanged measured production sources are checked against the passing pre-benchmark I5.a source binding; all production owners also have the full executed coverage matrix.

No pg_stat_statements is presumed. Real network/provider/worker timings remain unavailable; P13-I1 owns actual processing evidence. No runtime instrumentation, schema/index/role/extension, configuration/preparation, retained data, worker throughput or UI change is introduced. Existing Java components still require attributable coverage and the SQL feature/test matrix.
