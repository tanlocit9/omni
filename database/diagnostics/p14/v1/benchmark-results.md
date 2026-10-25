# Scheduler benchmark observation — v1

This is a source-bound local experiment, not a production SLA or performance PASS threshold.

Window: 2026-10-25T06:46:53.372300Z to 2026-10-25T06:47:13.957600900Z. 5 warmups and 30 measured samples per repeated stage; nearest-rank percentiles. p99 is the maximum of 30 observations.

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

| Scenario / stage                                     | p50 ms | p95 ms |  p99 ms | JDBC execute calls/sample | Commit+rollback calls/sample |
| ---------------------------------------------------- | -----: | -----: | ------: | ------------------------: | ---------------------------: |
| one-run-100 / runDiscovery                           |  1.574 |  2.003 |   2.069 |                      1.00 |                         1.00 |
| one-run-100 / snapshot                               |  4.615 |  5.566 |   5.742 |                      3.00 |                         1.00 |
| one-run-100 / planner                                |  0.065 |  0.146 |   0.159 |                      0.00 |                         0.00 |
| one-run-100 / scopedCandidateRead                    | 13.490 | 18.565 |  19.105 |                      2.00 |                         1.00 |
| one-run-100 / fifoCandidateReadReference             | 89.778 | 99.509 | 103.869 |                    151.00 |                         1.00 |
| one-run-100 / claimRollback                          |  6.796 |  8.507 |  13.278 |                     10.00 |                         1.00 |
| 32-runs-10 / runDiscovery                            |  2.305 |  2.655 |   2.715 |                      1.00 |                         1.00 |
| 32-runs-10 / snapshot                                |  7.328 |  8.920 |   9.628 |                      3.00 |                         1.00 |
| 32-runs-10 / planner                                 |  0.190 |  0.263 |   0.306 |                      0.00 |                         0.00 |
| 32-runs-10 / scopedCandidateRead                     |  5.288 |  6.924 |   7.051 |                      2.00 |                         1.00 |
| 32-runs-10 / fifoCandidateReadReference              | 80.725 | 94.687 |  99.319 |                    151.00 |                         1.00 |
| 32-runs-10 / claimRollback                           |  5.866 |  6.231 |   7.188 |                     10.00 |                         1.00 |
| join-one-parent-success / runDiscovery               |  1.031 |  1.336 |   1.373 |                      1.00 |                         1.00 |
| join-one-parent-success / snapshot                   |  3.361 |  3.690 |   3.782 |                      3.00 |                         1.00 |
| join-one-parent-success / planner                    |  0.020 |  0.032 |   0.085 |                      0.00 |                         0.00 |
| join-one-parent-success / scopedCandidateRead        |  3.165 |  3.905 |   3.930 |                      2.00 |                         1.00 |
| join-one-parent-success / fifoCandidateReadReference | 61.666 | 66.149 |  70.581 |                    121.00 |                         1.00 |
| join-one-parent-success / claimRollback              |  6.379 |  6.761 |   7.250 |                     10.00 |                         1.00 |
| retry-and-live-lease / runDiscovery                  |  0.920 |  1.021 |   1.163 |                      1.00 |                         1.00 |
| retry-and-live-lease / snapshot                      |  3.594 |  3.896 |   3.910 |                      3.00 |                         1.00 |
| retry-and-live-lease / planner                       |  0.003 |  0.006 |   0.015 |                      0.00 |                         0.00 |
| unknown-closure / runDiscovery                       |  0.966 |  1.066 |   1.098 |                      1.00 |                         1.00 |
| unknown-closure / snapshot                           |  4.291 |  6.013 |   6.108 |                      3.00 |                         1.00 |
| unknown-closure / planner                            |  0.002 |  0.004 |   0.004 |                      0.00 |                         0.00 |
| failed-predecessor / runDiscovery                    |  1.058 |  1.346 |   1.724 |                      1.00 |                         1.00 |
| failed-predecessor / snapshot                        |  3.549 |  3.911 |   3.992 |                      3.00 |                         1.00 |
| failed-predecessor / planner                         |  0.004 |  0.005 |   0.006 |                      0.00 |                         0.00 |

The FIFO reference uses the same fixture/input state and candidate limit as the scoped read, but has different selection semantics. Only the one-run-100 case returns 75 rows from both readers; do not infer equivalent cohort safety or a production speedup. Other returned cardinalities are recorded in JSON. Claim includes rollback and closed-transitive admission; planner is pure and should issue no database calls.

Application database-call/round-trip boundary is JDBC execute calls plus commit/rollback calls. It is not a TCP packet or wire-protocol message count. Hikari poolNow is the end-of-window gauge; poolPeak fields are independent maxima and cannot be added together.

## Synthetic stage window

Counters: `{'plannedQuota': 300, 'registryVisited': 300, 'fakeBrokerAcknowledged': 300, 'offeredCandidates': 375, 'claimed': 300, 'syntheticChildStatusSuccess': 300}`.

Elapsed 5.430 seconds; fake publish and synthetic child-status rate 55.252 events/second over the same combined orchestration/status window.

The real dispatcher, registry, claim, fencing and JobService.applyStatus execute; Kafka acknowledgement is mocked and worker completion is injected. These rates do not measure broker capacity, provider processing or actual worker start/completion. Offered candidates count repeated tick offers, not distinct message identities.

## Controlled pool pressure

4 concurrent snapshot requests wait while the pool's 10 slots are held. One slot is released after an observed waiter and a controlled 30ms hold. Pool observations and acquisition distributions are in JSON; this is intentional saturation, not normal workload pressure.

Observed peak pool: `{'active': 10, 'total': 10, 'idle': 10, 'waiting': 3}`.

## SQL plan evidence

| Source query / scenario                      | Root rows | Root hit/read buffers | Planning ms | Execution ms | Scan/index nodes                                                                                                                                                                                                |
| -------------------------------------------- | --------: | --------------------- | ----------: | -----------: | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 32-runs-10-claim-update-explain              |         0 | 22 / 0                |       0.048 |        0.123 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                          |
| 32-runs-10-diagnostic-explain                |         1 | 3718 / 0              |       2.164 |        9.728 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| 32-runs-10-fifo-explain                      |        75 | 17 / 0                |       0.060 |        0.071 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                |
| 32-runs-10-inventory-explain                 |         1 | 701 / 0               |       0.594 |       11.418 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| 32-runs-10-scoped-explain                    |        10 | 3256 / 0              |       0.193 |        2.746 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                         |
| 32-runs-10-snapshot-explain                  |        96 | 3715 / 0              |       0.486 |        3.641 | Aggregate, CTE Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Result, Seq Scan, Sort; idx_execution_job_run_step, uq_scheduler_outbox_execution_message                                                 |
| failed-predecessor-diagnostic-explain        |         1 | 143 / 0               |       1.291 |        1.677 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| failed-predecessor-inventory-explain         |         1 | 278 / 0               |       0.581 |        7.417 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| join-one-parent-success-claim-update-explain |         0 | 13 / 0                |       0.055 |        0.140 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                          |
| join-one-parent-success-diagnostic-explain   |         1 | 145 / 0               |       2.105 |        1.998 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| join-one-parent-success-fifo-explain         |        60 | 43 / 0                |       0.078 |        0.093 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                |
| join-one-parent-success-inventory-explain    |         1 | 283 / 0               |       0.902 |        6.109 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| join-one-parent-success-scoped-explain       |        20 | 257 / 0               |       0.197 |        0.395 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                         |
| join-one-parent-success-snapshot-explain     |         3 | 149 / 0               |       0.465 |        0.478 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                |
| one-run-100-claim-update-explain             |         0 | 19 / 0                |       0.056 |        0.131 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                          |
| one-run-100-diagnostic-explain               |         1 | 942 / 0               |       2.568 |        2.287 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| one-run-100-fifo-explain                     |        75 | 70 / 0                |       0.060 |        0.078 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                |
| one-run-100-inventory-explain                |         1 | 692 / 0               |       0.886 |       10.800 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| one-run-100-scoped-explain                   |        75 | 1302 / 0              |       0.234 |        3.207 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                         |
| one-run-100-snapshot-explain                 |         3 | 946 / 0               |       0.603 |        1.119 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                |
| retry-and-live-lease-diagnostic-explain      |         1 | 143 / 0               |       1.657 |        1.960 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| retry-and-live-lease-inventory-explain       |         1 | 360 / 0               |       0.626 |        5.302 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| unknown-closure-diagnostic-explain           |         1 | 15 / 0                |       1.525 |        1.550 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| unknown-closure-inventory-explain            |         1 | 1 / 0                 |       1.092 |        0.506 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |

These are observed plans on small synthetic tables. Sequential scans can be rational; no index or cost threshold is forced. Root counters are not sums across nested plans. Mutation EXPLAIN uses the actual repository claim UPDATE only inside a disposable rollback transaction; no claim attempt persists. Raw plans are regenerated under apps/core/build/reports/scheduler-benchmark/.

## Reproduction and limitations

From workspace root: `nx run platform:test --args="--tests=*JobRunDispatchBenchmarkPostgresTest"`. Docker/Testcontainers and existing Platform dependencies are required. Full verification additionally runs the diagnostic SQL suite, clean Platform build and coverage. Output numbers vary by machine and load; the committed JSON records this observation, its exact SQL/test/production source hashes and the complete measurement dictionary.

Hashes bind exact local checkout bytes, including line endings. Additional unchanged measured production sources are checked against the passing pre-benchmark I5.a source binding; all production owners also have the full executed coverage matrix.

No pg_stat_statements is presumed. Real network/provider/worker timings remain unavailable; P13-I1 owns actual processing evidence. No runtime instrumentation, schema/index/role/extension, configuration/preparation, retained data, worker throughput or UI change is introduced. Existing Java components still require attributable coverage and the SQL feature/test matrix.
