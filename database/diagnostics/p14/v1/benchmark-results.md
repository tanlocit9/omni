# Scheduler benchmark observation — v1

This is a source-bound local experiment, not a production SLA or performance PASS threshold.

Window: 2026-10-25T07:34:23.338132900Z to 2026-10-25T07:34:47.387834200Z. 5 warmups and 30 measured samples per repeated stage; nearest-rank percentiles. p99 is the maximum of 30 observations.

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

| Scenario / stage                                     |  p50 ms |  p95 ms |  p99 ms | JDBC execute calls/sample | Commit+rollback calls/sample |
| ---------------------------------------------------- | ------: | ------: | ------: | ------------------------: | ---------------------------: |
| one-run-100 / runDiscovery                           |   2.054 |   2.578 |   2.763 |                      1.00 |                         1.00 |
| one-run-100 / snapshot                               |   5.299 |   6.202 |   6.654 |                      3.00 |                         1.00 |
| one-run-100 / planner                                |   0.069 |   0.149 |   0.180 |                      0.00 |                         0.00 |
| one-run-100 / scopedCandidateRead                    |  15.800 |  19.666 |  20.332 |                      2.00 |                         1.00 |
| one-run-100 / fifoCandidateReadReference             | 109.564 | 119.897 | 121.030 |                    151.00 |                         1.00 |
| one-run-100 / claimRollback                          |   9.421 |  11.456 |  11.806 |                     10.00 |                         1.00 |
| 32-runs-10 / runDiscovery                            |   2.532 |   3.471 |   3.592 |                      1.00 |                         1.00 |
| 32-runs-10 / snapshot                                |   7.726 |  11.917 |  13.256 |                      3.00 |                         1.00 |
| 32-runs-10 / planner                                 |   0.347 |   0.521 |   0.554 |                      0.00 |                         0.00 |
| 32-runs-10 / scopedCandidateRead                     |   6.179 |   7.289 |   8.550 |                      2.00 |                         1.00 |
| 32-runs-10 / fifoCandidateReadReference              |  94.306 | 102.404 | 102.555 |                    151.00 |                         1.00 |
| 32-runs-10 / claimRollback                           |   7.883 |  11.540 |  12.337 |                     10.00 |                         1.00 |
| join-one-parent-success / runDiscovery               |   1.212 |   1.469 |   1.567 |                      1.00 |                         1.00 |
| join-one-parent-success / snapshot                   |   4.127 |   4.489 |   4.540 |                      3.00 |                         1.00 |
| join-one-parent-success / planner                    |   0.039 |   0.074 |   0.093 |                      0.00 |                         0.00 |
| join-one-parent-success / scopedCandidateRead        |   4.856 |   6.568 |   6.843 |                      2.00 |                         1.00 |
| join-one-parent-success / fifoCandidateReadReference |  70.372 |  82.313 |  90.541 |                    121.00 |                         1.00 |
| join-one-parent-success / claimRollback              |   7.370 |   7.847 |   7.909 |                     10.00 |                         1.00 |
| retry-and-live-lease / runDiscovery                  |   1.103 |   1.258 |   1.277 |                      1.00 |                         1.00 |
| retry-and-live-lease / snapshot                      |   4.032 |   4.591 |   4.954 |                      3.00 |                         1.00 |
| retry-and-live-lease / planner                       |   0.007 |   0.018 |   0.031 |                      0.00 |                         0.00 |
| unknown-closure / runDiscovery                       |   0.984 |   1.121 |   1.186 |                      1.00 |                         1.00 |
| unknown-closure / snapshot                           |   3.792 |   4.092 |   4.276 |                      3.00 |                         1.00 |
| unknown-closure / planner                            |   0.001 |   0.003 |   0.025 |                      0.00 |                         0.00 |
| failed-predecessor / runDiscovery                    |   1.196 |   1.352 |   1.368 |                      1.00 |                         1.00 |
| failed-predecessor / snapshot                        |   4.238 |   4.594 |   4.986 |                      3.00 |                         1.00 |
| failed-predecessor / planner                         |   0.009 |   0.012 |   0.013 |                      0.00 |                         0.00 |

The FIFO reference uses the same fixture/input state and candidate limit as the scoped read, but has different selection semantics. Only the one-run-100 case returns 75 rows from both readers; do not infer equivalent cohort safety or a production speedup. Other returned cardinalities are recorded in JSON. Claim includes rollback and closed-transitive admission; planner is pure and should issue no database calls.

Application database-call/round-trip boundary is JDBC execute calls plus commit/rollback calls. It is not a TCP packet or wire-protocol message count. Hikari poolNow is the end-of-window gauge; poolPeak fields are independent maxima and cannot be added together.

## Synthetic stage window

Counters: `{'syntheticChildStatusSuccess': 300, 'plannedQuota': 300, 'registryVisited': 300, 'fakeBrokerAcknowledged': 300, 'offeredCandidates': 375, 'claimed': 300}`.

Elapsed 6.399 seconds; fake publish and synthetic child-status rate 46.884 events/second over the same combined orchestration/status window.

The real dispatcher, registry, claim, fencing and JobService.applyStatus execute; Kafka acknowledgement is mocked and worker completion is injected. These rates do not measure broker capacity, provider processing or actual worker start/completion. Offered candidates count repeated tick offers, not distinct message identities.

## Controlled pool pressure

4 concurrent snapshot requests wait while the pool's 10 slots are held. One slot is released after an observed waiter and a controlled 30ms hold. Pool observations and acquisition distributions are in JSON; this is intentional saturation, not normal workload pressure.

Observed peak pool: `{'idle': 10, 'waiting': 3, 'active': 10, 'total': 10}`.

## SQL plan evidence

| Source query / scenario                      | Root rows | Root hit/read buffers | Planning ms | Execution ms | Scan/index nodes                                                                                                                                                                                                |
| -------------------------------------------- | --------: | --------------------- | ----------: | -----------: | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 32-runs-10-claim-update-explain              |         0 | 22 / 0                |       0.056 |        0.139 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                          |
| 32-runs-10-diagnostic-explain                |         1 | 3718 / 0              |       2.447 |       10.717 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| 32-runs-10-fifo-explain                      |        75 | 21 / 0                |       0.072 |        0.082 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                |
| 32-runs-10-inventory-explain                 |         1 | 701 / 0               |       0.971 |       19.986 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| 32-runs-10-scoped-explain                    |        10 | 3263 / 0              |       0.219 |        3.040 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                         |
| 32-runs-10-snapshot-explain                  |        96 | 3715 / 0              |       0.431 |        4.644 | Aggregate, CTE Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Result, Seq Scan, Sort; idx_execution_job_run_step, uq_scheduler_outbox_execution_message                                                 |
| failed-predecessor-diagnostic-explain        |         1 | 143 / 0               |       1.388 |        1.743 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| failed-predecessor-inventory-explain         |         1 | 280 / 0               |       0.659 |       12.343 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| join-one-parent-success-claim-update-explain |         0 | 13 / 0                |       0.050 |        0.109 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                          |
| join-one-parent-success-diagnostic-explain   |         1 | 145 / 0               |       1.711 |        1.730 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| join-one-parent-success-fifo-explain         |        60 | 40 / 0                |       0.066 |        0.065 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                |
| join-one-parent-success-inventory-explain    |         1 | 280 / 0               |       0.747 |        8.549 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| join-one-parent-success-scoped-explain       |        20 | 253 / 0               |       0.215 |        0.439 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                         |
| join-one-parent-success-snapshot-explain     |         3 | 149 / 0               |       0.439 |        0.525 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                |
| one-run-100-claim-update-explain             |         0 | 19 / 0                |       0.078 |        0.194 | Index Scan, ModifyTable; idx_scheduler_outbox_dispatch                                                                                                                                                          |
| one-run-100-diagnostic-explain               |         1 | 942 / 0               |       3.584 |        3.409 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| one-run-100-fifo-explain                     |        75 | 70 / 0                |       0.100 |        0.121 | Index Scan, Limit; idx_scheduler_outbox_dispatch                                                                                                                                                                |
| one-run-100-inventory-explain                |         1 | 694 / 0               |       1.245 |       23.299 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| one-run-100-scoped-explain                   |        75 | 1392 / 0              |       0.260 |        3.477 | Index Scan, Limit, Nested Loop; idx_execution_job_run_step, idx_job_run_steps_definition, idx_scheduler_outbox_dispatch                                                                                         |
| one-run-100-snapshot-explain                 |         3 | 946 / 0               |       0.675 |        1.332 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; idx_execution_job_run_step, job_run_step_preparations_pkey, uq_job_run_step_definition, uq_scheduler_outbox_execution_message                |
| retry-and-live-lease-diagnostic-explain      |         1 | 143 / 0               |       1.561 |        1.675 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| retry-and-live-lease-inventory-explain       |         1 | 360 / 0               |       0.676 |        8.878 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |
| unknown-closure-diagnostic-explain           |         1 | 15 / 0                |       1.523 |        1.489 | Aggregate, CTE Scan, Function Scan, Hash, Hash Join, Index Scan, Limit, Nested Loop, Recursive Union, Result, Seq Scan, Sort, WorkTable Scan; idx_execution_job_run_step, uq_scheduler_outbox_execution_message |
| unknown-closure-inventory-explain            |         1 | 1 / 0                 |       0.642 |        0.425 | Aggregate, CTE Scan, Index Scan, Limit, Nested Loop, Result, Sort; scheduler_outbox_messages_pkey, uq_execution_run_identity, uq_scheduler_outbox_execution_message                                             |

These are observed plans on small synthetic tables. Sequential scans can be rational; no index or cost threshold is forced. Root counters are not sums across nested plans. Mutation EXPLAIN uses the actual repository claim UPDATE only inside a disposable rollback transaction; no claim attempt persists. Raw plans are regenerated under apps/core/build/reports/scheduler-benchmark/.

## Reproduction and limitations

From workspace root: `nx run platform:test --args="--tests=*JobRunDispatchBenchmarkPostgresTest"`. Docker/Testcontainers and existing Platform dependencies are required. Full verification additionally runs the diagnostic SQL suite, clean Platform build and coverage. Output numbers vary by machine and load; the committed JSON records this observation, its exact SQL/test/production source hashes and the complete measurement dictionary.

Hashes bind exact local checkout bytes, including line endings. Additional unchanged measured production sources are checked against the passing pre-benchmark I5.a source binding; all production owners also have the full executed coverage matrix.

No pg_stat_statements is presumed. Real network/provider/worker timings remain unavailable; P13-I1 owns actual processing evidence. No runtime instrumentation, schema/index/role/extension, configuration/preparation, retained data, worker throughput or UI change is introduced. Existing Java components still require attributable coverage and the SQL feature/test matrix.
