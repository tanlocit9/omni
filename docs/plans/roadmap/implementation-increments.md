# Dependency-Ordered Implementation Increments

I9 signal-entry correction (2026-10-30): standard seed expectation is seven
strategy-aware entry targets; no new executed gate. I8/I9 remain
verification_pending. See [source correction](../030-static-graph-dispatch-planner.md#i9-signal-entry-correction--2026-10-30).


Current I9 delivery (2026-10-30): I9.a/b/c runtime/source are present and I9 is
`verification_pending`; I8 remains `verification_pending`. Owner requested all I9
source development before later local review. No new verification/coverage,
activation, retained migration or completion is claimed. See
[full source handoff](../030-static-graph-dispatch-planner.md#i9-full-source-delivery-and-review-handoff--2026-10-30).
Prior I9.a-only evidence below is historical and does not verify this continuation.


I9.a acceptance hardening (2026-10-30) adds isolated read-only snapshot planning,
sector-level projection and executed-coverage reconciliation. Local planning gate
`P14-I9a-8219aff` PASS: 486 tests, zero failures/errors, two opt-in skips; graph
coverage 92.7% line / 84.2% branch. I9.a completion still awaits I8 closure;
I8/I9 increment statuses and dependency gates are unchanged.
See [checkpoint evidence](../030-static-graph-dispatch-planner.md#i9a-acceptance-hardening--2026-10-30).

Historical owner-directed continuation (2026-10-29; superseded by full I9 delivery above): I8 remains
`verification_pending`; I9 is `in_progress` for I9.a read-only graph planning.
Owner explicitly allowed source development after the I8 test amendment without
waiving I8/I9 completion or activation gates. I9.b/c were unimplemented at that point and were
not automatically selected by that narrower exception. See
[I9.a implementation and evidence](../030-static-graph-dispatch-planner.md#i9a-read-only-graph-expansion-development--2026-10-29).

## Current P14 registration reconciliation — 2026-10-29

I7 is recorded completed by the 2026-10-28 isolated local gate below. The old
cloud dependency-download failure is historical and is not a current I8 dependency
blocker. Canonical registration now includes I8 (Unified work expansion), dependent
on I7, and I9 (Graph-driven expansion and definition consolidation), dependent on I8.
Reciprocal blocks are I7 -> I8 -> I9. Owner requested I8 implementation without
verification; that original delivery marked I8 verification_pending and I9 pending.
The owner-directed continuation above supersedes that starting status. See
[delivery, contract and local handoff](../030-static-graph-dispatch-planner.md#i8-source-delivery-and-local-verification-handoff--2026-10-29).
No new PASS, coverage or deployment is claimed; I7 is not reopened.
I9.a/b/c are internal checkpoints, not separate registry IDs. No new P12/P13
dependency or runtime/default activation is added.
See [current plan](../030-static-graph-dispatch-planner.md#current-i8i9-handoff--2026-10-29).

## I9 checkpoint and result-handoff alignment — 2026-10-28

P14-I9 is split into sequential internal checkpoints: I9.a graph-driven expansion,
I9.b shared ancestor work, I9.c definition consolidation with history/offset/
migration/diagnostics/rollback evidence. These are not new registry IDs and do not
change I9 status or activate P12.

Future consumers send computed result plus unchanged atomic work identity to the
writer: JobType, typed workKey, businessDate, canonical effective parameters and
execution correlation. Add operation, logical output/row identity, deterministic
intentId and result or a validated durable logical candidate reference. Keep
physical paths out of business routing and retain execution attribution.

Writer validates the intent, derives output writeKey and buffers only compatible
operations; it does not resolve static graph or expand work. One work can emit
multiple operations/outputs; several works or JobTypes can share a writeKey.
Conflicting results under one intentId require explicit rejection/conflict handling.
All required outputs must be durable before individual terminal SUCCESS.

Current terminal-status commit loop is not the future durable-intent handoff loop.
Preserve source-after-intent and writer-after-output/status commit boundaries.
No implementation, new test/coverage PASS, version-binding solution or default
activation is claimed. See [I9 checkpoints and handoff](../030-static-graph-dispatch-planner.md#i9-checkpoints-and-writer-result-handoff--2026-10-28).

This file is the canonical increment registry for scheduled autonomous delivery. Canonical numbered plans provide additional task detail; supporting plans under [`../../docs`](../..) provide rationale and must link back here instead of defining a competing schedule.

Current I7 checkpoint — 2026-10-28: isolated local verification against c20d9cb
completed under `P14-I7-reverify-20261028`. V15 and strict-work identity changes
are separate from future expansion plans and worker/readiness follow-ups. The verified commit keeps I5/I6
separate; later consolidation remains outside that commit. Preparation and cron behavior are unchanged. Retained deployment
requires the [V15 cutover procedure](../../deployment/004-job-run-admission-rollout.md#v15-legacy-outbox-identity-cutover-p14-i7).

Fresh local source/test/build/coverage verification passed: 444 tests / 67 suites, zero failures/errors/skips; all 31 critical Java components meet >=80% line/applicable branch coverage. Ten migration cases, candidate/admission/preparation regressions and actual SQL/read-model/benchmark cases pass. The final gate is `P14-I7-reverify-20261028` (nine required checks, including owned-file format and static documentation audit). SQL coverage is behavioral, not a Java percentage. Exact source hashes and the executed criterion/source/test matrix are retained under `.agent/check-results/P14-I7-reverify-20261028/`.

Dated execution sections below retain their historical status; the current
registry and checkpoint above own scheduling.

## Status model

See [`automation-rules.md`](automation-rules.md) for allowed statuses, execution modes, branch ownership, CI retry rules, and plan-update authority.

## Increment registry

| id     | title                                                              | status               | priority | depends_on            | blocks                              | owned_modules                                                                                                                             | execution_mode    | requires_owner_decision | pr                                        | last_verified_commit                     |
| ------ | ------------------------------------------------------------------ | -------------------- | -------- | --------------------- | ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ----------------- | ----------------------- | ----------------------------------------- | ---------------------------------------- |
| P0-I1  | Due-query predicate and repository matrix tests                    | completed            | critical | []                    | [P1-I1]                             | [apps/core]                                                                                                                               | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/7  | 8efc965b2084a16af9c733a9631e4e4729c23be4 |
| P0-I2  | Workspace path normalization and Linux Nx verification             | completed            | critical | []                    | [P1-I1, P2-I1, P6-I3]               | [workspace]                                                                                                                               | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/7  | 8efc965b2084a16af9c733a9631e4e4729c23be4 |
| P1-I0  | Scheduler claim ADR and boundary confirmation                      | completed            | critical | [P0-I1, P0-I2]        | [P1-I1]                             | [docs/adr, apps/core]                                                                                                                     | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/7  | 8efc965b2084a16af9c733a9631e4e4729c23be4 |
| P1-I1  | Claim data model and repository primitives                         | completed            | critical | [P1-I0]               | [P1-I2, P4-I1]                      | [apps/core, database]                                                                                                                     | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/7  | 8efc965b2084a16af9c733a9631e4e4729c23be4 |
| P1-I2  | Scheduler claim/outbox integration and concurrency tests           | completed            | critical | [P1-I1]               | [P2-I1, P3-I1, P4-I1, P5-I1, P7-I1] | [apps/core, database]                                                                                                                     | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/8  | 6956a6eeef1897b343870e44480181cdf7812ae0 |
| P1-I3  | Canonical sector universe and one shared transition writer         | verification_pending | high     | [P1-I2]               | [P3-I3, P9-I3]                      | [apps/core, apps/analyzer, configs]                                                                                                       | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/16 | ab2cc3cb0044c87d2b61a6736652c6fd4cfb2124 |
| P1-I4  | workType/workKey manual-history cutover and event ownership        | completed            | high     | [P1-I2]               | [P2-I2, P4-I1, P4-I3, P8-I1]        | [apps/core, apps/analyzer, apps/ingestor, libs/py-common, database]                                                                       | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/16 | 6c51dd600c06305d9841d60f98cab89a6e54c0ca |
| P2-I1  | Contracts project, Buf targets, and initial v1 schemas             | completed            | critical | [P1-I2]               | [P2-I2, P9-I1]                      | [contracts, apps/core, libs/py-common]                                                                                                    | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/9  | 8fc3e830944ca721d5d429dad9e4cdffb81a70e2 |
| P2-I2  | Generated Java/Python adapters and golden fixtures                 | superseded           | critical | [P2-I1, P1-I4]        | [P2-I3, P3-I2]                      | [contracts, apps/core, libs/py-common, apps/analyzer, apps/ingestor]                                                                      | autonomous        | false                   | null                                      | null                                     |
| P2-I3  | Pilot dual-read migration, producer switch, and observation window | superseded           | high     | [P2-I2]               | [P6-I1]                             | [apps/core, apps/analyzer, apps/ingestor, configs]                                                                                        | approval_required | true                    | null                                      | null                                     |
| P3-I1  | Manifest models, identity rules, and py_common abstractions        | superseded           | critical | [P1-I2]               | [P3-I2, P4-I1, P6-I1, P9-I1]        | [libs/py-common, configs, docs/data]                                                                                                      | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/12 | fd23482d2bb764b145ad2a199d45553c85ea39e8 |
| P3-I2  | First Ingestor READY manifest and failure safety                   | superseded           | critical | [P3-I1, P2-I2]        | [P3-I3, P4-I1]                      | [apps/ingestor, libs/py-common, configs]                                                                                                  | autonomous        | false                   | null                                      | null                                     |
| P3-I3  | Java read-only manifest client and first Analyzer migration        | superseded           | high     | [P3-I2, P1-I3]        | [P4-I1, P6-I1]                      | [apps/core, apps/analyzer, libs/py-common]                                                                                                | autonomous        | false                   | null                                      | null                                     |
| P3-I4  | Normalize Parquet date contracts and safe versioned rewrite        | completed            | critical | [P1-I2]               | [P9-I1, P9-I2, P10-I2, P13-I3]      | [libs/py-common, apps/ingestor, apps/analyzer, apps/query-service]                                                                        | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/16 | ab2cc3cb0044c87d2b61a6736652c6fd4cfb2124 |
| P3-I5  | Automatic EOD metadata reconciliation                              | superseded           | high     | [P3-I1, P7-I2]        | []                                  | [apps/core, apps/analyzer, libs/py-common, docs/data, docs/flows]                                                                         | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/16 | ad90a491022d6cdd0265850e549db01562e66696 |
| P4-I1  | Dependency policies and shadow guard                               | completed            | critical | [P1-I2]               | [P4-I2, P5-I2, P6-I1]               | [apps/core, configs, docs/data]                                                                                                           | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/14 | 0d09cbe14719f83d2536573575795171eca6a168 |
| P4-I2  | Scheduler enforcement for the first analytical job                 | completed            | critical | [P4-I1]               | [P5-I2, P6-I1, P7-I2, P14-I1]       | [apps/core, apps/analyzer, configs]                                                                                                       | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/14 | 0d09cbe14719f83d2536573575795171eca6a168 |
| P4-I3  | Dependency-aware outbox dispatch and terminal blocking             | superseded           | critical | [P1-I4, P4-I2, P7-I2] | []                                  | [apps/core, database, docs/flows, docs/data]                                                                                              | autonomous        | false                   | null                                      | null                                     |
| P5-I1  | Image hardening and configuration contracts                        | superseded           | high     | [P1-I2]               | [P5-I2, P6-I3]                      | [docker-compose, apps/core, apps/analyzer, apps/ingestor]                                                                                 | autonomous        | false                   | null                                      | null                                     |
| P5-I2  | Compose profiles and centralized object storage compatibility      | superseded           | high     | [P4-I2, P5-I1]        | [P5-I3, P6-I1]                      | [docker-compose, configs, libs/py-common]                                                                                                 | approval_required | true                    | null                                      | null                                     |
| P5-I3  | Backup/restore rehearsal and immutable image publication           | superseded           | medium   | [P5-I2]               | [P6-I4]                             | [docker-compose, .github, docs]                                                                                                           | manual            | true                    | null                                      | null                                     |
| P6-I1  | Server-side Query Service and read-only SQL boundary               | superseded           | high     | [P3-I3, P5-I2]        | [P6-I2, P6-I3]                      | [apps/query-service, libs/py-common, docs]                                                                                                | autonomous        | false                   | null                                      | 5ecb6c2                                  |
| P6-I2  | Omni Console Dataset Explorer and Parquet Viewer                   | superseded           | medium   | [P6-I1]               | [P6-I3]                             | [apps/omni-console, apps/query-service, docs]                                                                                             | autonomous        | false                   | null                                      | 5ecb6c2                                  |
| P6-I3  | SQL Console, Arrow results, history, and export                    | superseded           | medium   | [P6-I2]               | [P6-I4, P7-I1]                      | [apps/omni-console, apps/query-service]                                                                                                   | autonomous        | false                   | null                                      | 5ecb6c2                                  |
| P6-I4  | Saved Queries, Dashboard, and private identity boundary            | superseded           | medium   | [P6-I3, P5-I3]        | []                                  | [apps/omni-console, apps/core, database/migrations, docker-compose]                                                                       | approval_required | true                    | null                                      | null                                     |
| P7-I1  | Job-definition catalog and triggerability API                      | completed            | high     | [P1-I2]               | [P7-I2, P7-I3]                      | [apps/core, apps/omni-console, docs/flows]                                                                                                | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/15 | 0d09cbe14719f83d2536573575795171eca6a168 |
| P7-I2  | Safe trigger and execution-status contract                         | completed            | high     | [P7-I1, P4-I2]        | [P7-I3, P13-I3]                     | [apps/core, database/migrations, docs/flows]                                                                                              | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/15 | 0d09cbe14719f83d2536573575795171eca6a168 |
| P7-I3  | Omni Console Jobs tab and execution visibility                     | completed            | medium   | [P7-I1, P7-I2]        | [P13-I1]                            | [apps/omni-console, apps/core, docs]                                                                                                      | autonomous        | false                   | https://github.com/tanlocit9/omni/pull/15 | 0d09cbe14719f83d2536573575795171eca6a168 |
| P8-I1  | Operational and generic Telegram notification formats              | verification_pending | critical | [P1-I4]               | [P8-I2, P8-I3, P8-I5]               | [apps/core, configs, docs/plans]                                                                                                          | autonomous        | false                   | null                                      | null                                     |
| P8-I2  | Immediate and digest signal notification formats                   | verification_pending | critical | [P8-I1]               | [P8-I3, P8-I4, P8-I5]               | [apps/core, configs, docs/plans]                                                                                                          | autonomous        | false                   | null                                      | null                                     |
| P8-I3  | Telegram delivery safety, retries, idempotency, and rollout        | superseded           | critical | [P8-I1, P8-I2]        | []                                  | [apps/core, configs, docs/flows]                                                                                                          | autonomous        | false                   | null                                      | null                                     |
| P8-I4  | Equal-vote confirmed trend, symbol query, and notification choice  | pending              | critical | [P8-I2]               | []                                  | [apps/analyzer, apps/core, apps/query-service, apps/omni-console]                                                                         | autonomous        | false                   | null                                      | null                                     |
| P8-I5  | Notification Outbox and Durable Delivery                           | verification_pending | critical | [P8-I1, P8-I2]        | [P11-I1]                            | [apps/core, configs, database, docs]                                                                                                      | autonomous        | false                   | null                                      | null                                     |
| P9-I1  | Post-close intraday ingestion contracts and normalization          | verification_pending | medium   | []                    | [P9-I2, P9-I4]                      | [apps/core, apps/ingestor, libs/py-common, configs, docs]                                                                                 | autonomous        | false                   | null                                      | null                                     |
| P9-I2  | Intraday bars, reusable features, and manifests                    | superseded           | medium   | [P9-I1]               | [P9-I3, P10-I2]                     | [apps/analyzer, libs/py-common]                                                                                                           | autonomous        | false                   | null                                      | null                                     |
| P9-I3  | Sector aggregation and lineage publication                         | superseded           | medium   | [P9-I2, P1-I3]        | [P10-I1]                            | [apps/analyzer, libs/py-common]                                                                                                           | autonomous        | false                   | null                                      | null                                     |
| P9-I4  | Intraday-confirmed daily signal                                    | pending              | high     | [P9-I1, P8-I4]        | []                                  | [apps/analyzer, apps/core, docs]                                                                                                          | autonomous        | false                   | null                                      | null                                     |
| P9-I5  | VCI health metrics and basic visibility                            | superseded           | critical | [P9-I1]               | []                                  | [apps/ingestor, apps/core, apps/query-service, apps/omni-console, configs, docs]                                                          | autonomous        | false                   | null                                      | null                                     |
| P10-I0 | VCI realtime capability discovery and evidence                     | blocked              | critical | []                    | [P10-I3]                            | [docs/plans, docs/data, docs/flows]                                                                                                       | approval_required | true                    | null                                      | null                                     |
| P10-I1 | Strict MarketTick contract, identity, replay, and logical paths    | verification_pending | critical | []                    | [P10-I2]                            | [libs/py-common, configs, docs/data, docs/flows]                                                                                          | approval_required | true                    | null                                      | null                                     |
| P10-I2 | Provider-independent archive, rebuild, bars, and reconciliation    | verification_pending | critical | [P10-I1]              | [P10-I3]                            | [libs/py-common, configs, docs/data, docs/flows]                                                                                          | approval_required | true                    | null                                      | null                                     |
| P10-I3 | VCI live collector control, archive, and operational runtime       | blocked              | critical | [P10-I0, P10-I2]      | []                                  | [apps/core, apps/ingestor, libs/py-common, configs, database, docs]                                                                       | approval_required | true                    | null                                      | null                                     |
| P11-I1 | Correlation contract and structured logging                        | pending              | high     | [P14-I4, P8-I5]       | [P11-I2]                            | [libs/contracts, apps/core, libs/py-common, apps/analyzer, apps/ingestor, apps/query-service, docs]                                       | approval_required | true                    | null                                      | null                                     |
| P11-I2 | HTTP and Console correlation propagation                           | pending              | high     | [P11-I1]              | [P11-I3]                            | [apps/core, libs/py-common, apps/analyzer, apps/query-service, apps/omni-console, docs]                                                   | approval_required | true                    | null                                      | null                                     |
| P11-I3 | Durable job and outbox correlation                                 | pending              | high     | [P11-I2]              | [P11-I4]                            | [apps/core, database, docs/data, docs/flows]                                                                                              | approval_required | true                    | null                                      | null                                     |
| P11-I4 | Kafka correlation propagation                                      | pending              | high     | [P11-I3]              | [P11-I5]                            | [apps/core, libs/py-common, apps/analyzer, apps/ingestor, configs, docs/data, docs/flows]                                                 | approval_required | true                    | null                                      | null                                     |
| P11-I5 | Fluent Bit and VictoriaLogs deployment                             | pending              | medium   | [P11-I4]              | [P12-I1]                            | [docker-compose, configs, docs/deployment, docs/architecture]                                                                             | approval_required | true                    | null                                      | null                                     |
| P12-I1 | Python worker offset safety and capacity baseline                  | pending              | high     | [P11-I5, P13-I1]      | [P12-I2]                            | [libs/py-common, apps/analyzer, apps/ingestor, docs]                                                                                      | approval_required | true                    | null                                      | null                                     |
| P12-I2 | Job write policy and bounded independent-output concurrency        | pending              | high     | [P12-I1]              | [P12-I3]                            | [apps/core, apps/analyzer, apps/ingestor, libs/py-common, configs, docs/data, docs/flows]                                                 | approval_required | true                    | null                                      | null                                     |
| P12-I3 | Platform bulk child-status application and parent aggregation      | pending              | high     | [P12-I2]              | [P12-I4]                            | [apps/core, docs/flows, docs/data]                                                                                                        | approval_required | true                    | null                                      | null                                     |
| P12-I4 | Independent Python dataset-writer service and signal batching      | pending              | high     | [P12-I3]              | []                                  | [apps/core, apps/analyzer, apps/dataset-writer, libs/py-common, configs, docs/data, docs/flows, docs/deployment]                          | approval_required | true                    | null                                      | null                                     |
| P13-I1 | Truthful execution stage model and baseline instrumentation        | pending              | critical | [P14-I4, P7-I3]       | [P13-I2, P12-I1]                    | [apps/core, apps/analyzer, apps/ingestor, libs/py-common, libs/contracts, database, configs, docs]                                        | autonomous        | false                   | null                                      | null                                     |
| P13-I2 | Job Operations dashboard, daily throughput, lag, and stall signals | pending              | critical | [P13-I1]              | [P13-I4]                            | [apps/core, apps/omni-console, database, configs, docs]                                                                                   | autonomous        | false                   | null                                      | null                                     |
| P13-I3 | Manual EOD Parquet Data Health scan                                | pending              | high     | [P3-I4, P7-I2]        | []                                  | [apps/query-service, apps/omni-console, configs, docs]                                                                                    | autonomous        | true                    | null                                      | null                                     |
| P13-I4 | Operator-first fixed dashboard shell                               | pending              | high     | [P13-I2]              | []                                  | [apps/omni-console, apps/core, apps/query-service, docs]                                                                                  | autonomous        | false                   | null                                      | null                                     |
| P14-I1 | Static topology and validation                                     | completed            | critical | [P4-I2]               | [P14-I2]                            | [apps/core, configs, docs]                                                                                                                | autonomous        | false                   | null                                      | null                                     |
| P14-I2 | Static DAG dispatcher contract and handoff                         | completed            | critical | [P14-I1]              | [P14-I3]                            | [apps/core, configs, docs]                                                                                                                | autonomous        | false                   | null                                      | null                                     |
| P14-I3 | Static definition edges, unified JobRun and target preparation     | completed            | critical | [P14-I2]              | [P14-I4]                            | [apps/core, database, configs, docs]                                                                                                      | autonomous        | false                   | null                                      | null                                     |
| P14-I4 | JobRun-aware outbox planning and guarded admission                 | completed            | critical | [P14-I3]              | [P14-I5, P14-I7, P13-I1]            | [apps/core, database, configs, docs]                                                                                                      | autonomous        | false                   | null                                      | null                                     |
| P14-I5 | Diagnostics, measured performance and operator read-model handoff  | completed            | high     | [P14-I4]              | [P14-I7]                            | [apps/core, database, docs]                                                                                                               | autonomous        | false                   | null                                      | null                                     |
| P14-I6 | Merged into P14-I5 (historical read-model handoff)                 | superseded           | high     | []                    | []                                  | [apps/core, database, docs]                                                                                                               | autonomous        | false                   | null                                      | null                                     |
| P14-I7 | Legacy outbox data cleanup and compatibility retirement            | completed            | high     | [P14-I4, P14-I5]      | [P14-I8]                            | [apps/core, database, docs]                                                                                                               | autonomous        | false                   | null                                      | null                                     |
| P14-I8 | Unified work expansion                                             | verification_pending | critical | [P14-I7]              | [P14-I9]                            | [apps/core, apps/analyzer, apps/ingestor, libs/py-common, libs/contracts, database, configs, docs]                                        | autonomous        | false                   | null                                      | null                                     |
| P14-I9 | Graph-driven expansion and definition consolidation                | verification_pending          | critical | [P14-I8]              | []                                  | [apps/core, apps/analyzer, apps/ingestor, apps/query-service, apps/omni-console, libs/py-common, libs/contracts, database, configs, docs] | autonomous        | false                   | null                                      | null                                     |

I7 local completion — 2026-10-25: source-bound gate PASS 8/8, 444 tests /
67 suites, ten migration/upgrade/concurrency cases and all 31 critical Java
components >=80% line/applicable branch. V15 preserves intent/history while
normalizing valid NULL-JobRun aliases; strict work-FK readers/guard retire alias
compatibility. Retained migration, backup/quiescence, zero-alias reconciliation and
recovery remain explicit deployment gates, not a claimed retained cleanup run.
I5 includes former I6; I6 remains superseded and historical commit/check IDs stay.
See [I7 source/test matrix and operational limits](../030-static-graph-dispatch-planner.md#i7-local-completion--2026-10-25).

## Canonical Plan 030 scope revision — 2026-10-15

Owner consolidation — 2026-10-25: P14-I5 includes diagnostics, measured performance
and the operator read-model handoff previously delivered as I6. I5 remains completed;
I6 is superseded into I5 and is not a scheduling dependency. I7 keeps its ID and
depends only on I4/I5. Commits and historical P14-I5/P14-I6 check manifests/results
remain unchanged, including c20d9cb and the source-bound 434-test/66-suite gate.
See [consolidation decision](../030-static-graph-dispatch-planner.md#i5i6-consolidation--2026-10-25).
The dated I6 completion entry below is historical evidence for the merged scope.

I6 completion — 2026-10-25: local gate PASS 8/8, 434 tests / 66 suites,
nine I6 contract cases, eight diagnostic cases, refreshed six-workload/24-plan
benchmark and 31 critical Java components >=80% line/applicable branch.
The bounded SQL/read-contract handoff is complete with no endpoint/UI/schema or
retained-data change. I7 remains ready; preparation stays disabled.
See [I6 acceptance and self-review](../030-static-graph-dispatch-planner.md#i6-bounded-read-model-completion--2026-10-25).
Earlier dated I5 checkpoints retain their historical evidence and readiness.

I5 review repairs — 2026-10-25: renewed source-bound local gate PASS 7/7,
425 tests / 65 suites, eight SQL cases and 31 critical Java components >=80%
line/applicable branch. BLOCKED progress and jsonb-incompatible poison payloads
have executed PostgreSQL regression coverage; measured artifacts are refreshed.
I5 remains completed; I6/I7 remain ready with their own acceptance/rollout gates.
The original 423-test checkpoint below is historical, not evidence for repaired SQL.
See [review repair matrix](../030-static-graph-dispatch-planner.md#i5-review-repairs--2026-10-25).

I5 completion — 2026-10-25: I5 is completed on local gate PASS 7/7,
423 tests / 65 suites, six SQL cases, six benchmark workloads,
24 real query plans and 31 critical components >=80% line/branch.
I5.a is committed as 734d2ef; [final source-bound evidence](../030-static-graph-dispatch-planner.md#i5-completion-and-measured-evidence--2026-10-25)
records measurements and limits. I6/I7 become ready with bounded writer isolation
recorded there; neither is implemented/complete. Retained migration/data cleanup,
real provider/worker timing, preparation and hosted CI are not authorized/claimed.
All earlier dated checkpoints below retain historical statuses; the registry table and current completion evidence govern.

I5.a checkpoint — 2026-10-25: P14-I5 is `in_progress` on read-only versioned SQL,
legacy-alias inventory and the I6 metric dictionary. Runtime/status/producer code
is untouched. SQL uses bounded explicit IDs/pages and is exercised against
disposable PostgreSQL in actual read-only transactions. Local checkpoint PASS 6/6:
422 full tests, six SQL cases and 31 critical Java components >=80% line/branch.
Benchmarks remain I5.b, and I6/I7 stay pending. See
[I5.a scope/evidence](../030-static-graph-dispatch-planner.md#i5a-read-only-diagnostic-pack--2026-10-25).

I7 registration — 2026-10-25: owner requested legacy-data cleanup as P14-I7.
Status `pending`; dependencies I4/I5, without adding an I6 or P13 prerequisite.
Normalize historical parent-FK/payload-child outbox references, preserve delivery
state/history, then retire only that compatibility branch. Keep the active
NULL-JobRun scheduling path. Retained migration execution and physical purging
require separate authorization. See [I7 scope and acceptance](../030-static-graph-dispatch-planner.md#p14-i7--legacy-outbox-data-cleanup-and-compatibility-retirement).
This is planning only; no migration file, data change or new execution PASS.

I4 review repair — 2026-10-25: both real PostgreSQL regressions were reproduced
before repair. The renewed local gate passes 8/8 with 416 tests,
31 critical Java components >=80% line/branch and source-bound acceptance.
Rotated pages survive snapshot sorting; legacy admission skips the actual busy
child before metadata updates, tested against `JobService.applyStatus()`.
See [current repair evidence](../030-static-graph-dispatch-planner.md#i4-review-regressions--2026-10-25). I4 remains completed; I5 ready/I6 pending.
Preparation stays false; no migration/deletion/retained rollout is included.
The dated 413-test checkpoint below remains historical.

Current I4 completion — 2026-10-24: P14-I1–I4 are `completed`.
[I4.c integrated admission](../030-static-graph-dispatch-planner.md#i4c-integrated-admission-and-completion--2026-10-24).
The local gate passes 8/8: 413 full tests / 63 suites, 88 focused cases,
all 31 critical Java components >=80% line/branch, actual PostgreSQL
locks/fences/rollback/restart/query plans and the ten transferred TD-014 invariants.
I4.a is committed as 9c7fbfc and I4.b as 7213795; final integrated source belongs
to the same I4 delivery. No CI/deployment/provider/runtime activation is claimed.
Preparation remains false, all 32 cron identities remain and TD-017 stays deferred.
P14-I5 becomes `ready`: its read-only SQL/benchmark artifacts and docs are isolated
from P1-I3/P8-I1/I2/I5/P9-I1 production writers. Any production instrumentation
change must first reconcile that overlap. I6 is pending on I5. P13-I1 depends on
completed I4/P7-I3, but remains pending until its broader execution/status/contract
writer ownership is reconciled; dependency completion alone is insufficient.
The task ends at I4; no I5/I6 implementation is included. Earlier dated statements
below preserve historical statuses and do not override the registry table.

Current completion update — 2026-10-24: P14-I2 and P14-I3 are `completed`.
Source/test/migration bindings still match the executed 357-test/59-suite gate
`P14-I2-I3-coverage-docs-20261023` (required=7/pass=7); all 32 audited critical
components meet 80% line/branch and the separate I2/I3 acceptance matrices and
documentation are reconciled in [Plan 030](../030-static-graph-dispatch-planner.md#i2i3-completion-and-remaining-p14-work--2026-10-24).
Preparation stays false and all 32 cron identities remain under the existing I3.d
boundary. No owner attestation, deployment or runtime activation is claimed.
I4 remains `pending`: dependencies are met, but concrete path isolation against
other verification_pending apps/core ownership must be recorded before autonomous
selection. I5/I6 remain pending on I4/I5. The dated checkpoint paragraphs below
preserve historical statuses and do not override this update or the registry table.

P14-I1 static topology remains `completed`. **P14-I2 is narrowed** to its static graph dispatcher-facing topology/path contract and standalone compatibility verification; the previously attempted backlog-only snapshot and pure planner PASS is historical evidence only. I2 remains `verification_pending`, NOT automatically complete. No new JobRun persistence or runtime activation in I2.

P14-I2 source evidence (2026-10-15), renewed verification (2026-10-16), documentation
reconciled (2026-10-17): pure `RequiredPathResolver` version 1 and its focused tests
are present. The owner approved and the agent executed `npx nx run platform:test`,
`npx nx run platform:coverage` and `npx nx run platform:build` through check_result:
**PASS P14-I2 required=3 pass=3 fail=0 unknown=0 missing=0 sources=exit_code**.
Clean build executed 287 tests, zero failures/errors/skips, including 11 resolver tests.
Resolver coverage is **100% line / 95.83% branch**; every changed/directly impacted
critical component exceeds 80% line/branch, as mapped in Plan 030. HEAD was
`b50a1063d5aa13ff9eeb976a0dd122d7bc008603` on `WIP`; artifacts and source hashes are
preserved under ignored `.agent/check-results/P14-I2/verification-mHSZ/`.
No checks were rerun by the 2026-10-17 documentation-only edit.
Owner approved definition-specific dependency closure, explicit multi-definition
bindings, and fail-closed scoped capabilities/edge mappings. No runtime defaults,
bean activation, schema or outbox changes. Historical inactive planner/backlog
source remains a **DEPRECATION CANDIDATE** with caller inventory and removal gate in
[Plan 030's handoff](../030-static-graph-dispatch-planner.md#p14-i2-implementation-handoff--2026-10-15-locally-verified-verification-pending).
Status remains `verification_pending` in this documentation-only synchronization;
no completion/readiness promotion or new owner attestation is recorded. Documentation
review disposition is recorded in Plan 030. CI/deployment/live runtime are not gates
for the inactive I2 handoff, and historical backlog-only attestation does not attest it.
I3 remains `pending`; no product scope or dependency is changed by this evidence update.

Owner-requested bounded I3.a pre-cutover source (2026-10-17): typed definition-level declaration/provider and resolver input plus parity/validation tests are present. Legacy seed JSON remains behind consumer-removal gates. The owner-requested cleanup removes unused topology adapters and migrates all repository test callers to typed input; JSON baseline parsing is test-only. The later full Platform regression passed 293 tests in 50 suites with zero failures/errors/skips (`WINDOWS-HIDDEN-CONSOLES-20261018`); the separate coverage/build gate remains open. Previous I2 PASS does not cover the modified provider/resolver. I2 remains `verification_pending`, I3 remains `pending` and is not autonomously ready for I3.b. No JobRun schema or scheduling/dispatcher activation. See [I3.a evidence and gates](../030-static-graph-dispatch-planner.md#i3a-pre-cutover-source--2026-10-17).

- **P14-I3:** begin with checkpoint I3.a: move job-to-job `configJson.dependsOnJobs` into typed, source-controlled static edges keyed by `JobDefinitionIdentity`, preserving one topology provider and I2 resolver with parity/startup regression and no persisted graph. Then unified `job_runs` persisted logical-run and frozen ancestor→target paths; scheduled/manual modes and future backfill identity; decommission `manual_job_triggers` via new forward-only migration **after** all manual runtime readers/writers have cut over, without historical-data migration. Keep necessary dependency job definitions/producers, reduce only independently cron-scheduled entry targets. Prepare and enqueue dependency-first; keep legacy FIFO outbox, guard, claim, publish and retry behavior unchanged. Enqueue order is not upstream-completion proof: feature-gate target-driven admission if the old guard cannot safely enforce run-step predecessor completion.

Owner-requested I3.b bounded source (2026-10-18): additive V12 JobRun/frozen-step
schema, inactive transactional snapshot repository, immutable scoped membership,
request idempotency and nullable execution links are present with new unit and
PostgreSQL tests. Full local Platform regression passed 310 tests across 52 suites
with zero failures/errors/skips (17 new cases), plus changed-file formatting.
The dedicated coverage and clean build targets also passed (`P14-I3b`: four
required checks PASS); clean build reran all 310 tests and produced the application
JAR. Attributable JaCoCo coverage and the executed SQL case matrix are in Plan 030.
This is local verification, without CI, deployment or owner attestation.
No prior I3.a PASS substitutes for this new schema/source evidence. No scheduled/manual cutover, outbox activation or table removal.
I2 remains `verification_pending`, I3 `pending`; this is not readiness/completion
promotion. See [I3.b scope and gates](../030-static-graph-dispatch-planner.md#i3b-persistence-foundation--2026-10-18).

Owner-approved I3.c staging (2026-10-18): bounded GLOBAL/VN preparation, adapted
producer/enqueue boundaries and V13 immutable execution/message closure passed the
four-check local format/test/coverage/clean-build gate, renewed after the 2026-10-19
symbol-upsert membership fix: 346 tests across 56 suites,
zero failures/errors/skips. Plan 030 records attributable coverage and the executed
SQL matrix. The route is false by default; existing
cron, manual audit/API, FIFO and guard remain active. No readiness/status promotion
or I3.d/I4 activation. See [I3.c scope and gates](../030-static-graph-dispatch-planner.md#i3c-bounded-preparation--2026-10-18).

I3.c lock repair (locally verified 2026-10-20): the real-service race reproduced
`deadlock detected` against the exact old SQL. Narrowed execution/outbox progress
guards passed a separate five-check local gate: format, 28 focused PostgreSQL
tests, full test, coverage and clean build (351 tests / 56 suites, no failures,
errors or skips). Attributable critical Java coverage exceeds 80% line/branch;
SQL has an executed criterion matrix. The 346-test PASS above remains historical.
Preparation stays false, with no status/readiness promotion or production activation. See the
[lock-safety repair plan](../030-static-graph-dispatch-planner.md#i3c-lock-safety-repair-plan--2026-10-19).

I3.c replay lock repair (2026-10-20): target definition now precedes run locking in
visible replay, matching fresh preparation. Old Java reproduced a deadlock; both
scheduled/manual replay variants pass with preserved identity/membership/cadence.
The separate five-check local gate passed 29 focused and 352 full tests / 56 suites,
no failures/errors/skips; preparation coverage 99.35% line / 91.67% branch. A second
static review found no further issue in the changed lock/claim path. Plan 030 records
the matrix, review limits and unchanged false flag/statuses. Earlier counts above
remain historical checkpoints.

I3.d owner scope decision (2026-10-21): operator-triggered jobs and request audit
are deferred under [TD-017](../../technical-debt/017-manual-request-rejection-audit.md).
Source removes manual service/storage/config and Console trigger/sync actions;
read APIs remain, old trigger/audit routes return authenticated HTTP 410. New V14
drops the legacy table after old readers stop; audit history is intentionally not
migrated. JobRun invariants, automatic cron and false preparation flag remain.
The bounded I3.d checkpoint passed its seven-check local gate on 2026-10-22:
341 Platform tests / 55 suites and 27 Console tests / six files, with attributable
controller/catalog coverage and executed migration/source mapping in Plan 030.
The static inventory retains all 32 canonical cron identities pending I4 safety.
No retained-environment migration, activation, cron reduction, status promotion
or commit is claimed.

I2/I3 reconciliation — 2026-10-23: the full review command gate passed (341 Platform
and 27 Console tests), while semantic acceptance retained coverage/documentation
findings. Owner requested their repair: populated outbox parent validation,
claim ownership/release, SYNC_SYMBOLS fallback and catalog compatibility tests;
a regression also exposed and repaired null-config message construction.
The new seven-check local gate and fresh per-source coverage are recorded in
[Plan 030](../030-static-graph-dispatch-planner.md#i2i3-coverage-and-documentation-reconciliation--2026-10-23).
Clean build executed 357 tests / 59 suites with zero failures/errors/skips; focused
tests passed 19 cases. All 32 audited critical components exceed the default
80% line/branch gate (JobService 95.12% / 88.76%, outbox 99.37% / 91.30%,
SyncSymbols/catalog 100% / 100%). Current manual/ERD and ownership guidance is
reconciled, with the plan-update skill now shared under `.agents/skills`, without
changing acceptance or registry statuses:
I2 `verification_pending`, I3 `pending`, preparation false, 32 cron identities retained.

- **P14-I4:** execution-centered **JobRun path snapshot** drives outbox selection, cohort barrier, joins and fair quotas with admission/claim revalidation, fallback, fenced publish and TD-014 safety evidence.
- **P14-I5:** runnable, versioned read-only SQL diagnostics, `EXPLAIN (ANALYZE, BUFFERS)`, Hikari/DB/query/throughput measured baseline, and metric dictionary. Essential I3/I4 safety and query tests remain their own acceptance gates.
- **P14-I5 (includes former I6):** diagnostics, measurement and bounded read-model handoff for Plan 029, without creating a second UI or unnecessary API.
- Static topology is immutable configuration; `job_runs` persists one logical request and its required run steps, **not** a competing mutable graph authority. P14-I3 reserves a durable typed `RunScope` (symbol/sector/exchange/global-compatible), and immutable run steps record _effective_ upstream scope; symbol-specific producer activation is separately gated. `jobRunId` identifies a run regardless of its businessGroup, and sharing a global/exchange upstream must not produce duplicated conflicting work. `jobRunId=job_runs.id`; `executionId` and `parentLogId` retain existing semantics. Backfill rollout remains an approval-gated **review-required** Plan 028 capability; its persisted topology migration is not approved.
- Every story has a green build/regression checkpoint and may be deployed without awaiting the next story. Java `@Deprecated` or explicit documented migration/removal gates are mandatory when decommissioning still-used contracts; no half-migration or deletion of applied Flyway V8 scripts. `RUNNING` on child execution does not prove worker processing has started.

Canonical implementation/exit contracts are in [Plan 030](../030-static-graph-dispatch-planner.md). All earlier owner revisions, metrics-gate-at-I2 text and I3 dispatcher integration language were superseded by this revision; they are not current acceptance requirements.

Owner-requested startup regression source (2026-10-18): `PlatformStartupPostgresTest`
boots the real application twice over one ephemeral PostgreSQL Testcontainer with
Flyway/seeding, HTTP readiness, typed-edge parity and persisted identity/runtime-field
preservation assertions. Outbound work is isolated in test-only wiring. Owner-approved
focused local execution passed: **1 test, 0 failures/errors/skips**; separate
`check_result` ID `P14-I3a-startup-20261018` reports required=1/pass=1. This does not
close the full I3.a coverage/regression/build gate or promote dependency/readiness/
completion. See [startup test evidence](../030-static-graph-dispatch-planner.md#i3a-testcontainers-startup-regression--2026-10-18).

## Milestone 1 — Safe and explainable scheduler operations

Owner-requested cleanup (2026-10-18): removed unused topology JSON/list adapters and
the obsolete Telegram properties constructor; all repository test callers migrated
in the same change. JSON baseline parsing is test-only, while seed JSON itself stays
for catalog compatibility. Focused startup rerun passed: 1 test, zero failures/errors/
skips (`P14-I3a-cleanup-20261018` required=1/pass=1); it compiles the migrated unit
tests but does not execute them. A subsequent owner-requested affected unit run passed
109 tests in 13 classes, zero failures/errors/skips (`P14-I3a-unit-20261018`
required=1/pass=1). The later full regression passed 293 tests in 50 suites, zero failures/errors/skips (`WINDOWS-HIDDEN-CONSOLES-20261018`). The attributable coverage/build gate remains open; see the later full-suite evidence in Plan 030.
I2/I3 status and dependencies are unchanged.
See [cleanup scope/evidence](../030-static-graph-dispatch-planner.md#unused-compatibility-adapter-cleanup--2026-10-18).

Milestone 1 groups the active scheduler-safety and operator-trust epics. Plan 030 is the priority epic with I1 static topology, I2 graph contract, I3 unified JobRun and DAG preparation, I4 JobRun-aware outbox integration, I5 measured diagnostics and operations read-model handoff; I6 is superseded into I5. Plan 028 is **REVIEW REQUIRED / ON HOLD**: its old persisted dependency graph Increment A is superseded by Plan 030 I3.a static declaration ownership; any remaining backfill rollout must be redesigned and approved separately. Plan 029 basic operations remains independent; JobRun-specific presentation waits for accepted I5 read evidence. No status is promoted by changing the schedule.

## Active MVP priority order

Owner-approved order (2026-10-15): P14-I1 (done) → I2 static contract → I3 JobRun preparation → I4 guarded outbox integration → I5 diagnostics/read-model handoff (I6 merged into I5). TD-014 former P4-I3 verification transfers to **I4**, not I3. P13-I1 timing evidence may proceed after I4 and P7-I3 independently of I5.

| Order | Increment | Current status         | Exit condition before advancing                                                                                                                                                                                                                                                                      |
| ----- | --------- | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | P14-I1    | `completed`            | Local Platform test/coverage/build gate passed; every critical class met the 80% line/branch threshold, cross-service no-impact boundaries were reconciled, and owner attestation was recorded for `tanlocit9`. No CI/deployment/runtime claim.                                                      |
| 2     | P14-I2    | `completed`            | Freeze static ancestor-path/definition mapping handoff, independent validation, compatibility and tests; leave FIFO runtime unchanged.                                                                                                                                                               |
| 3     | P14-I3    | `completed`            | Static per-definition edge cutover/parity first, then JobRun persistence and complete static path membership, scheduled/manual cutover, dependency-first durable enqueue, runtime old-guard safety, forward-only manual-table drop with tests and rollback.                                          |
| 4     | P14-I4    | `completed`            | Bounded JobRun snapshot, cohort completion gates, scoped outbox admission and TD-014 guard/claim/fencing/retry/concurrency/fallback verification.                                                                                                                                                    |
| 5     | P14-I5    | `completed`            | Measured SQL performance/connection-pool/throughput baselines, executable diagnostic scripts and metric dictionary.                                                                                                                                                                                  |
| 6     | P14-I6    | `superseded`           | Scope completed under consolidated P14-I5; historical ID retained; no separate scheduling gate.                                                                                                                                                                                                      |
| 7     | P14-I7    | `completed`            | After I4/I5: inventory and normalize historical outbox parent/child aliases, prove preservation/rollback, then retire compatibility; no I6 dependency or active NULL-JobRun removal.                                                                                                                 |
| 8     | P14-I8    | `verification_pending`              | Common expansion across existing creation routes, explicit atomic units/dates/parameters and consumer semantics; no second outbox fan-out. Plan 030 owns acceptance and readiness.                                                                                                                   |
| 9     | P14-I9    | `verification_pending`              | After I8: I9.a graph composition, I9.b shared ancestor work, I9.c definition consolidation with history/offset/migration/rollback evidence; all checkpoint gates required.                                                                                                                           |
| 10    | P13-I1    | `pending`              | After P14-I4 and P7-I3, establish truthful worker timing evidence and stage model.                                                                                                                                                                                                                   |
| 11    | P13-I2    | `pending`              | After P13-I1, basic Job Operations; graph-specific read model requires appropriate I5 handoff.                                                                                                                                                                                                       |
| 12    | P13-I4    | `pending`              | After P13-I2, operator-first shell independent of graph-specific extras.                                                                                                                                                                                                                             |
| 13    | P8-I1     | `verification_pending` | Reconcile the Platform notification blast radius and prove attributable coverage for rendering, routing, deduplication, escaping, Unicode bounds, timezone, severity, and failure branches; record applicable runtime evidence.                                                                      |
| 14    | P8-I2     | `verification_pending` | After P8-I1 completes, prove the additive `newSignalDate` producer/consumer impact and attributable Analyzer/Platform coverage, including unchanged-signal, first-date, digest, and compatibility branches.                                                                                          |
| 15    | P8-I4     | `pending`              | Paused to release active apps/core ownership for P14. After P8-I2 completes and work resumes, reconcile Analyzer, Platform, Query Service, and Console impacts and prove attributable coverage for the equal-vote matrix, invalid/stale input, persistence, query, and notification-selection paths. |
| 16    | P8-I5     | `verification_pending` | After P8-I1 and P8-I2 complete, prove notification-outbox migration, claim/fencing, restart, retry/429/5xx, terminal failure, idempotency, and durable-delivery behavior with attributable coverage and required runtime evidence.                                                                   |
| 17    | P9-I1     | `verification_pending` | Reconcile Platform/Ingestor/py-common/storage impacts and prove date/range, normalization, replay, READY-last, failure preservation, provider, deployment allow-list, and republishing behavior with attributable coverage.                                                                          |
| 18    | P9-I4     | `pending`              | Paused to release active apps/core ownership for P14. After P9-I1 and P8-I4 dependencies complete and work resumes, prove exact-date confirmation/suppression, stale/missing input, lineage, persistence, and notification-runtime behavior without reactivating P9-I2 or P9-I3.                     |
| 19    | P1-I3     | `verification_pending` | Reconcile Platform/Analyzer/config/storage writer impacts and prove one-writer, canonical-universe, retry, lineage, and failure-path coverage; Git/CI metadata remains optional traceability unless an explicit delivery gate applies.                                                               |

P13-I3 is a separate owner-decision lane after P3-I4/P7-I2. Query Service memory caching is resolved; calendar/lifecycle evidence must be approved or scope explicitly narrowed. Its activation does not block P13-I4. Owner decision on 2026-10-10 pauses P8-I4 and P9-I4 at `pending`, preserves all source/evidence/acceptance, and hands off their active apps/core ownership to P14. P14-I1 through I5 are completed after source-bound local acceptance, coverage and documentation reconciliation. I7 is locally completed; retained cutover/recovery gates remain; P13-I1 remains pending on wider writer-overlap reconciliation, not an incomplete I4 dependency.

## Deferred follow-up boundary — 2026-10-07

P10, P11, and P12 are now owner-gated technical-debt follow-ups, not active automatic phases. Their implementation/evidence statuses and proposed dependency graph are preserved; execution_mode=approval_required and requires_owner_decision=true prevent silent readiness promotion. See [TD-011](../../technical-debt/011-deferred-observability-capacity-and-realtime.md) and [debt priorities](../../technical-debt/README.md). Correctness/security slices in TD-001/003/005/008/009/010 may be separately promoted from evidence; feature deferral does not require waiting for a logging deployment to fix offset/data-loss risks.

## First eligible increment

The MVP is the existing daily/EOD pipeline plus usable Telegram notifications and basic operator controls.
P14-I1–I5 and I7 are locally completed; retained cutover/recovery gates remain.
Consolidated I5 includes the former I6 read-model scope and its historical evidence;
I6 is superseded and is not a separate scheduling gate.
P13-I1's dependency gate is met but its wider writer overlap is
not yet reconciled, so it remains pending. Preparation stays disabled and no
retained rollout is claimed. P4-I3 remains superseded; TD-014's transferred final
dispatcher safety package is closed by I4, not by reconstructing P4-I3 completion.
P8-I1 remains the evidence gate for P8-I2/P8-I5; notification and intraday ownership
are unchanged. Phase 11 remains deferred under TD-011.

Owner-approved dependency order (revised 2026-09-19): P8-I5 depends on completed P8-I1 and P8-I2. P9-I5 VCI health metrics is superseded into post-MVP technical debt and no longer blocks Notification Outbox. Any later capacity assessment or multi-provider ingestion requires separate owner reactivation and approval. P8-I5 adds no WebSocket runtime, Kafka tick transport, always-on collector, rate-limit-bypass concurrency, provider rotation, or fallback.

P10-I0 and P10-I3 remain `blocked`. The Phase 9 vnstock history endpoint and P9-I5 health measurements are not evidence of a live provider capability and do not authorize P10 implementation. P10-I1/P10-I2 remain `verification_pending`; P9-I2/P9-I3 remain superseded and P9-I4 is paused at `pending`. Completed increments and historical implementation evidence remain intact.

Historical Phase 11 design order (2026-09-17; revised on 2026-10-08): P11-I1 starts only after both P14-I3 and P8-I5 complete, then P11-I2, P11-I3, P11-I4, and P11-I5 execute sequentially. Plan 024 is canonical; Plan 015 is superseded historical design. Phase 11 remains owner-gated under TD-011.

Owner-approved operator-trust scope (canonical 2026-10-15 revision): P13-I1 depends on completed P14-I4/P7-I3 and gates P12-I1. P13-I2 follows P13-I1; P13-I4 follows P13-I2 without waiting for P13-I3. Query Service owns bounded in-memory Data Health runs/results; durable persistence is out of scope. P13-I3 remains pending with requires_owner_decision=true for calendar/lifecycle sources or explicit narrowing.

## Increment detail standard

Each phase file must express its increments with the template in [`templates/increment.md`](templates/increment.md): goal, verified baseline, dependencies, scope, approach, likely files/modules, acceptance criteria, required tests, required Nx/build/CI commands, migration/backward compatibility, risks, stop conditions, and rollback notes.

## Migration summary from previous increment names

| Old label | Canonical increment                                                    | Notes                                                                                                                                                                                                                                                         |
| --------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PR-0A     | P0-I1                                                                  | Completed correctness hotfix evidence is in current branch/source state.                                                                                                                                                                                      |
| PR-0B     | P0-I2                                                                  | Completed portability evidence is in current branch/source state.                                                                                                                                                                                             |
| PR-1A     | P1-I1                                                                  | Completed claim foundation with merged PostgreSQL integration evidence.                                                                                                                                                                                       |
| PR-1B     | P1-I2                                                                  | Completed claim/outbox integration with PostgreSQL concurrency and publish-recovery evidence.                                                                                                                                                                 |
| PR-1C     | P1-I3                                                                  | Sector universe and shared writer remain one coherent increment.                                                                                                                                                                                              |
| PR-1D     | P1-I4                                                                  | Backfill, coordinated breaking cutover, legacy removal, and notification ownership remain one coherent increment.                                                                                                                                             |
| PR-2A     | P2-I1                                                                  | Contracts project and initial schemas.                                                                                                                                                                                                                        |
| PR-2B     | P2-I2                                                                  | Generated adapters and fixtures.                                                                                                                                                                                                                              |
| PR-2C     | P2-I3                                                                  | Approval required for compatibility window and producer switch timing.                                                                                                                                                                                        |
| PR-3A     | P3-I1                                                                  | Manifest abstractions only.                                                                                                                                                                                                                                   |
| PR-3B     | P3-I2                                                                  | First Ingestor manifest publication.                                                                                                                                                                                                                          |
| PR-3C     | P3-I3                                                                  | Java reader plus first Analyzer migration.                                                                                                                                                                                                                    |
| PR-4A     | P4-I1                                                                  | Shadow guard before enforcement.                                                                                                                                                                                                                              |
| PR-4B     | P4-I2                                                                  | First enforced analytical job.                                                                                                                                                                                                                                |
| PR-5A+    | P5-I1, P5-I2, P5-I3                                                    | Split because deployment, storage compatibility, and restore rehearsal have different authority and verification.                                                                                                                                             |
| PR-6A+    | P6-I1, P6-I2, P6-I3, P6-I4                                             | Split to separate API/access decisions, scaffold, MVP, and security/expiry hardening.                                                                                                                                                                         |
| PR-7A+    | P7-I1, P7-I2, P7-I3                                                    | Split catalog visibility, safe trigger semantics, and Console interaction.                                                                                                                                                                                    |
| PR-8A+    | P8-I1, P8-I2, P8-I3, P8-I5                                             | P8-I3 preserves the historical deferred label; P8-I5 actively owns the separately designed notification outbox and durable delivery.                                                                                                                          |
| PR-9A+    | P9-I1, P9-I2, P9-I3, P9-I5                                             | P9-I5 adds bounded VCI health evidence without reactivating bars, sectors, or realtime collection.                                                                                                                                                            |
| PR-10A+   | P10-I0, P10-I1, P10-I2, P10-I3                                         | Re-split provider evidence, strict contract, provider-independent archive/rebuild, and provider/live runtime after owner reactivation.                                                                                                                        |
| Plan 024  | P11-I1, P11-I2, P11-I3, P11-I4, P11-I5                                 | Split contract/logging, HTTP, durable persistence, Kafka propagation, and optional deployment into sequential reviewable increments.                                                                                                                          |
| Plan 027  | P12-I1, P12-I2, P12-I3, P12-I4                                         | Split offset safety, independent-object concurrency, Platform bulk status updates, and standalone Python writer into sequential increments after Phase 11 and the P13-I1 measurement gate.                                                                    |
| Plan 029  | P13-I1, P13-I2, P13-I3, P13-I4                                         | Split truthful stage/baseline work, Job Operations metrics, manual EOD Data Health scans, and the operator-first fixed dashboard into separate reviewable increments. Basic operations remain independent; only graph-specific presentation waits for P14-I2. |
| Plan 030  | P14-I1, P14-I2, P14-I3, P14-I4, P14-I5, P14-I6, P14-I7, P14-I8, P14-I9 | Static topology/contract → JobRun DAG preparation → guarded outbox integration → measured metrics → operator read-model handoff.                                                                                                                              |

Do not combine a data contract migration, scheduler concurrency change, deployment authority change, or UI feature in one pull request.

## Field/DTO impact and task boundaries — 2026-10-07

[Cross-plan field/DTO inventory](../../reference/002-planned-field-dto-impact.md) records change kinds, LOW/MEDIUM/HIGH impact and candidate contract decisions. Plan 029 separates contract inventory, additive timing, stage read projection, startedAt semantic cutover, baseline, operations API/ETA/heuristics, scanner checks/cache/calendar and fixed UI into bounded tasks. Plan 030 includes P14-I1 through P14-I9 as independently scheduled stories; its internal task suffixes do not create more increments. Existing evidence and statuses remain unchanged. Deferred P10/P11/P12 and proposed Plan 028 remain outside automatic selection.
