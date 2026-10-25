# JobRun operator read contract v1

P14-I6 hands this bounded SQL contract and its
[executed expectation fixtures](read-model-fixtures.json) to
[Plan 029](../../../../docs/plans/029-operator-trust-console.md).
Run [run_steps.sql](run_steps.sql) using the existing
[read-only invocation](README.md#safe-execution). The additive `readModel` member
is a projection of the same frozen rows as the original v1 diagnostic envelope;
the original `version`, `observedAt`, `complete`, `reason` and `rows` remain.
This is a consumer handoff, not an HTTP endpoint, Console implementation,
processing-stage ledger, stored statistics table or admission authority.

## Window, authority and unavailable results

The caller explicitly selects 1–32 distinct run UUIDs. The whole window is bounded
to 1024 steps and 8192 direct predecessor edges. Each selected run appears once;
each nested step appears once in ascending step_index order. Run order is UUID
order; it is not scheduler fairness or request order. Counts apply only to this
requested window and are never a global backlog. Identity/scope/date details are
bounded detail fields, not metric labels. Raw payload, request_json, actor and
provider credentials are not projected.

Use a SELECT-only role, read-only repeatable-read transaction, statement_timeout
5 seconds and lock_timeout 500 milliseconds. A query error/timeout, invalid
parameter, absent envelope or unsupported readModel version is unavailable;
the consumer must retain that reason and null/absent measurements. Do not render
an unavailable request as idle, zero backlog, zero throughput or SUCCESS.
The connection/endpoint owner must authorize selected runs if this contract is
later exposed over an API; this SQL pack introduces no application authorization.

`complete=false` reports INVALID_MISSING_OR_OVERSIZED_WINDOW, MISSING_STEPS or
EDGE_BOUND and returns `readModel.runs=[]`. That empty array means unavailable,
not zero work. The original diagnostic rows may still show partial evidence;
they must not substitute for a complete operator projection. `complete=true`
proves structural coverage of the selected window only. A complete window can
still contain unknown membership, failure, blocked work or unfinished execution.

## Shape and interpretation

| Field                                                                                                                                      | Type / interpretation                                                                                                                                                                                       |
| ------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| readModel.version / complete / reason                                                                                                      | integer 1 / boolean / bounded reason above or OBSERVED                                                                                                                                                      |
| captureStartedAt / captureFinishedAt / captureDurationMs                                                                                   | UTC database statement start and clock after step materialization / nonnegative elapsed milliseconds; excludes client transport and rendering                                                               |
| runs[].job_run_id / path_fingerprint / path_contract_version / target_definition_id                                                        | frozen run/path/target identity; fingerprint is opaque, not a second graph authority                                                                                                                        |
| runs[].run_state                                                                                                                           | persisted JobRun lifecycle state, kept separate from derived execution progress                                                                                                                             |
| runs[].expected_steps / observed_steps / completed_steps / failed_steps / unknown_steps                                                    | scoped step counts; completed means step execution SUCCESS, failed includes outbox BLOCKED, unknown includes unsupported lifecycle                                                                          |
| runs[].progress                                                                                                                            | UNKNOWN if any step has unknown/unsupported evidence; otherwise FAILED_OR_BLOCKED if any failed; otherwise SUCCESS only if all expected steps succeeded; else UNFINISHED                                    |
| runs[].completion_fraction                                                                                                                 | successful steps / expected steps, in [0,1]; null with unknown evidence. Step-weighted, not child-weighted, worker activity, READY, ETA or a successful-run count                                           |
| runs[].counts_trusted                                                                                                                      | false when membership/lifecycle is unknown. Numerical counts then describe observed rows only; consumers must not use zero to assert no outstanding work                                                    |
| runs[].eligible / leased / retry_delayed / published / blocked                                                                             | sums of separate per-step outbox aggregates; no message/execution fan-out multiplication. Eligible means an available candidate, still subject to predecessor, guard and fenced-claim checks                |
| runs[].steps[]                                                                                                                             | existing diagnostic step columns plus the additions below; immutable scope_type/key/business_date and definition identity come from the frozen step                                                         |
| steps[].predecessor_step_indices / ancestor_count / predecessor_wait                                                                       | direct frozen predecessor indices / transitive predecessor count / at least one ancestor lacks SUCCESS. Joins wait for every ancestor; neither an empty eligible queue nor publication proves completion    |
| steps[].progress / observation_reason                                                                                                      | persisted execution/membership classification and the bounded explanation below; neither means actual worker processing                                                                                     |
| steps[].roots / executions / successful / failed / unknown_executions / closed / child_count / message_count / messages / identities_match | observed membership and expected immutable closure; counts include the parent execution; zero-child closed steps still require their one root SUCCESS                                                       |
| steps[].eligible / leased / retry_delayed / published / blocked / published_but_unfinished                                                 | independent queue and completion observations at the explicit observedAt lease clock; PUBLISHED is broker acknowledgement, not execution SUCCESS                                                            |
| steps[].worker_processing / dataset_ready                                                                                                  | always null in v1; use workerProcessingReason and datasetReadyReason below                                                                                                                                  |
| workerProcessingReason / datasetReadyReason                                                                                                | NO_AUTHORITATIVE_WORKER_TIMING / DEPENDENCY_REGISTRY_AUTHORITY. Preparation-time RUNNING/started_at does not prove worker activity; execution SUCCESS does not prove dataset READY or exact-version lineage |
| throughput.publishedPerSecond / completedPerSecond / unit / reason                                                                         | null / null / events/second / NO_AUTHORITATIVE_COUNTER_WINDOW. Current queue counts are not event counters or rates; rates require a comparable, authoritative counter window                               |

Step `observation_reason` precedence is UNKNOWN_MEMBERSHIP or
TERMINAL_OR_UNSUPPORTED_RUN, then FAILED_OR_BLOCKED, PREDECESSOR_WAIT,
EXECUTION_SUCCESS, LIVE_LEASE, RETRY_DELAYED, PUBLISHED_BUT_UNFINISHED,
ELIGIBLE_CANDIDATES, and finally EXECUTION_UNFINISHED. Multiple queue partitions
can be nonzero; the reason is a summary, so retain their independent counts.
An already-successful downstream step may still show PREDECESSOR_WAIT if an
ancestor is unfinished; do not reinterpret local SUCCESS as path completion.
Outbox BLOCKED overrides local execution SUCCESS after unknown checks.

Terminal/unsupported JobRun lifecycle is retained verbatim in run_state while
the existing diagnostic progress remains unknown; v1 does not reconstruct
terminal-run completion semantics from missing evidence. Failure reasons here
are bounded categories, not arbitrary payload/error text.

## Snapshot freshness

`observedAt` is the caller's lease-classification clock. It may intentionally be
synthetic and must never substitute for captureFinishedAt. The captured rows are
paired with the outer envelope's observedAt; consumers must carry that field when
extracting readModel, so lease/retry/eligible counts retain their clock provenance.
These capture clocks do not validate the caller's chosen lease clock. The rows are
an MVCC repeatable-read view, not guaranteed live at rendering time. Database
statement timestamps measure this collection statement, not the start of an
arbitrarily old transaction. Start a fresh transaction for each refresh and
promptly end it; reusing a long-lived transaction cannot prove current freshness.

The future Plan 029 consumer must declare its maximum age and clock-skew policy.
With compatible clocks, age is consumer_now minus captureFinishedAt. The snapshot
is fresh only when complete is true, the version is supported, timestamps are
ordered and age is nonnegative and no greater than the declared maximum age.
Age above the limit is STALE_SNAPSHOT. Missing clocks, negative age or unordered
clocks are UNKNOWN_CLOCK. No capture is UNAVAILABLE. Do not clamp a future clock
to zero age or a negative duration to zero. Fresh structural evidence can still
contain UNKNOWN membership; freshness does not change counts_trusted.

For an illustrative consumer policy maxAge=30 seconds, a finish clock 10 seconds
before render is fresh; 31 seconds is stale; one second in the future is unknown.
These are deterministic contract examples, not a production polling interval,
tested Console behavior, latency SLA or new runtime configuration.

## Measurement and compatibility handoff

The [I5 measured report](benchmark-results.md) and
[source-bound observation](benchmark-results.json) supply local synthetic
snapshot/planner/claim distributions, application database-call counters, pool
pressure, query plans and fake publish/status stage rates. Preserve their source,
environment, sample window and synthetic provenance when comparing them. Never
fill the live throughput nulls with these benchmark rates. P13-I1 owns actual
worker-stage/processing throughput; Plan 029 owns presentation and any approved
application API. The [dictionary](metrics.md) keeps units and label boundaries.

Consumers may ignore the new readModel member and continue reading original v1
diagnostics. Consumers of readModel must gate its own version and completeness,
allow additive fields, and fail unavailable on unknown states rather than guessing.
Reverting the additive SQL/docs/fixtures withdraws this consumer handoff without
changing claims, messages or execution history. No migration, extension, API,
Kafka contract, worker/provider call, retained-data repair or new runtime flag
is needed. I7 cleanup and preparation activation remain separate work.
