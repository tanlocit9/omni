# Scheduler benchmark observation — v1

This is a source-bound local experiment, not a production SLA or performance PASS threshold.

Window: 2026-10-28T14:36:02.928180200Z to 2026-10-28T14:36:23.734475900Z. 5 warmups and 30 measured samples per repeated stage; nearest-rank percentiles. p99 is the maximum of 30 observations.

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
| one-run-100 / runDiscovery                           |  1.839 |   2.665 |   2.769 |                      1.00 |                         1.00 |
| one-run-100 / snapshot                               |  4.548 |   5.188 |   5.352 |                      3.00 |                         1.00 |
| one-run-100 / planner                                |  0.101 |   0.154 |   0.160 |                      0.00 |                         0.00 |
| one-run-100 / scopedCandidateRead                    | 14.812 |  23.731 |  24.506 |                      2.00 |                         1.00 |
| one-run-100 / fifoCandidateReadReference             | 96.797 | 104.077 | 104.914 |                    151.00 |                         1.00 |
| one-run-100 / claimRollback                          |  8.433 |  10.209 |  10.233 |                     11.00 |                         1.00 |
| 32-runs-10 / runDiscovery                            |  2.377 |   3.071 |   3.429 |                      1.00 |                         1.00 |
| 32-runs-10 / snapshot                                |  7.651 |   9.877 |  10.035 |                      3.00 |                         1.00 |
| 32-runs-10 / planner                                 |  0.293 |   0.528 |   0.596 |                      0.00 |                         0.00 |
| 32-runs-10 / scopedCandidateRead                     |  6.444 |   7.756 |   7.776 |                      2.00 |                         1.00 |
| 32-runs-10 / fifoCandidateReadReference              | 82.758 |  93.198 |  95.295 |                    151.00 |                         1.00 |
| 32-runs-10 / claimRollback                           |  7.119 |   7.862 |  10.640 |                     11.00 |                         1.00 |
| join-one-parent-success / runDiscovery               |  1.093 |   1.326 |   1.431 |                      1.00 |                         1.00 |
| join-one-parent-success / snapshot                   |  3.423 |   3.859 |   4.016 |                      3.00 |                         1.00 |
| join-one-parent-success / planner                    |  0.027 |   0.049 |   0.050 |                      0.00 |                         0.00 |
| join-one-parent-success / scopedCandidateRead        |  3.659 |   4.831 |   4.898 |                      2.00 |                         1.00 |
| join-one-parent-success / fifoCandidateReadReference | 56.742 |  63.067 |  64.190 |                    121.00 |                         1.00 |
| join-one-parent-success / claimRollback              |  6.427 |   6.876 |   7.250 |                     11.00 |                         1.00 |
| retry-and-live-lease / runDiscovery                  |  0.956 |   1.057 |   1.094 |                      1.00 |                         1.00 |
| retry-and-live-lease / snapshot                      |  3.721 |   4.249 |   4.257 |                      3.00 |                         1.00 |
| retry-and-live-lease / planner                       |  0.007 |   0.010 |   0.023 |                      0.00 |                         0.00 |
| unknown-closure / runDiscovery                       |  0.955 |   1.589 |  21.620 |                      1.00 |                         1.00 |
| unknown-closure / snapshot                           |  3.300 |   3.595 |   3.678 |                      3.00 |                         1.00 |
| unknown-closure / planner                            |  0.002 |   0.003 |   0.005 |                      0.00 |                         0.00 |
| failed-predecessor / runDiscovery                    |  1.046 |   1.183 |   1.211 |                      1.00 |                         1.00 |
| failed-predecessor / snapshot                        |  3.343 |   3.544 |   3.727 |                      3.00 |                         1.00 |
| failed-predecessor / planner                         |  0.007 |   0.009 |   0.014 |                      0.00 |                         0.00 |

The FIFO reference uses the same fixture/input state and candidate limit as the scoped read, but has different selection semantics. Only the one-run-100 case returns 75 rows from both readers; do not infer equivalent cohort safety or a production speedup. Other returned cardinalities are recorded in JSON. Claim includes rollback and closed-transitive admission; planner is pure and should issue no database calls.

Application database-call/round-trip boundary is JDBC execute calls plus commit/rollback calls. It is not a TCP packet or wire-protocol message count. Hikari poolNow is the end-of-window gauge; poolPeak fields are independent maxima and cannot be added together.

## Synthetic stage window

Counters: `{'syntheticChildStatusSuccess': 300, 'claimed': 300, 'offeredCandidates': 375, 'fakeBrokerAcknowledged': 300, 'registryVisited': 300, 'plannedQuota': 300}`.

Elapsed 5.299 seconds; fake publish and synthetic child-status rate 56.618 events/second over the same combined orchestration/status window.

The real dispatcher, registry, claim, fencing and JobService.applyStatus execute; Kafka acknowledgement is mocked and worker completion is injected. These rates do not measure broker capacity, provider processing or actual worker start/completion. Offered candidates count repeated tick offers, not distinct message identities.

## Controlled pool pressure

4 concurrent snapshot requests wait while the pool's 10 slots are held. One slot is released after an observed waiter and a controlled 30ms hold. Pool observations and acquisition distributions are in JSON; this is intentional saturation, not normal workload pressure.

Observed peak pool: `{'total': 10, 'active': 10, 'waiting': 3, 'idle': 10}`.

## SQL plan evidence

| Source query / scenario                      | Root rows | Root hit/read buffers | Planning ms | Execution ms | Scan/index nodes                                                                                                                                                                                                               |
| -------------------------------------------- | --------: | --------------------- | ----------: | -----------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 32-runs-10-claim-update-explain              |         0 | 22 / 0                |       0.049 |        0.131 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                                         |
| 32-runs-10-diagnostic-explain                |         1 | 3718 / 0              |       2.408 |       26.372 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| 32-runs-10-fifo-explain                      |        75 | 19 / 0                |       0.064 |        0.070 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                               |
| 32-runs-10-inventory-explain                 |         1 | 701 / 0               |       1.046 |       17.982 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| 32-runs-10-scoped-explain                    |        10 | 3262 / 0              |       0.262 |        3.987 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                                        |
| 32-runs-10-snapshot-explain                  |        96 | 3715 / 0              |       0.714 |        3.952 | Aggregate, CTE Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Result, Seq Scan, Sort; idx_execution_job_run_step, uq_scheduler_outbox_execution_message                                                                |
| failed-predecessor-diagnostic-explain        |         1 | 143 / 0               |       2.113 |        2.955 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| failed-predecessor-inventory-explain         |         1 | 275 / 0               |       0.536 |        9.385 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| join-one-parent-success-claim-update-explain |         0 | 13 / 0                |       0.049 |        0.111 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                                         |
| join-one-parent-success-diagnostic-explain   |         1 | 145 / 0               |       1.925 |        2.725 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| join-one-parent-success-fifo-explain         |        60 | 39 / 0                |       0.061 |        0.068 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                               |
| join-one-parent-success-inventory-explain    |         1 | 279 / 0               |       0.567 |        7.755 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| join-one-parent-success-scoped-explain       |        20 | 253 / 0               |       0.193 |        0.385 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                                        |
| join-one-parent-success-snapshot-explain     |         3 | 149 / 0               |       0.393 |        0.587 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                               |
| one-run-100-claim-update-explain             |         0 | 19 / 0                |       0.059 |        0.136 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                                         |
| one-run-100-diagnostic-explain               |         1 | 942 / 0               |       3.026 |        3.897 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| one-run-100-fifo-explain                     |        75 | 70 / 0                |       0.069 |        0.078 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                               |
| one-run-100-inventory-explain                |         1 | 695 / 0               |       1.139 |       19.251 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| one-run-100-scoped-explain                   |        75 | 1364 / 0              |       0.309 |        3.212 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                                        |
| one-run-100-snapshot-explain                 |         3 | 946 / 0               |       0.609 |        1.176 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                               |
| retry-and-live-lease-diagnostic-explain      |         1 | 143 / 0               |       2.195 |        2.850 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| retry-and-live-lease-inventory-explain       |         1 | 359 / 0               |       0.582 |        7.815 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |
| unknown-closure-diagnostic-explain           |         1 | 15 / 0                |       1.979 |        2.522 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, Subquery Scan, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| unknown-closure-inventory-explain            |         1 | 1 / 0                 |       0.564 |        0.307 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                                            |

These are observed plans on small synthetic tables. Sequential scans can be rational; no index or cost threshold is forced. Root counters are not sums across nested plans. Mutation EXPLAIN uses the actual repository claim UPDATE only inside a disposable rollback transaction; no claim attempt persists. Raw plans are regenerated under apps/core/build/reports/scheduler-benchmark/.

## Reproduction and limitations

From workspace root: `nx run platform:test --args="--tests=*JobRunDispatchBenchmarkPostgresTest"`. Docker/Testcontainers and existing Platform dependencies are required. Full verification additionally runs the diagnostic SQL suite, clean Platform build and coverage. Output numbers vary by machine and load; the committed JSON records this observation, its exact SQL/test/production source hashes and the complete measurement dictionary.

Hashes bind exact local checkout bytes, including line endings. Additional measured production sources are bound to this isolated checkout; all production owners have the fresh executed coverage matrix. No historical coverage is reused.

No pg_stat_statements is presumed. Real network/provider/worker timings remain unavailable; P13-I1 owns actual processing evidence. No runtime instrumentation, schema/index/role/extension, configuration/preparation, retained data, worker throughput or UI change is introduced. Existing Java components still require attributable coverage and the SQL feature/test matrix.
