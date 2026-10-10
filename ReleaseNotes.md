# Omni Release Notes

## 2026-10-11 — P14-I1 static topology verified

P14-I1 completed its owner-approved local verification gate: `platform:test`, `platform:coverage`, and `platform:build` passed. Attributable JaCoCo evidence met the 80% line/branch threshold for every changed critical class; topology classes ranged from 93.3–100% line and 83.3–100% branch coverage, while seed/config synchronization classes reached 93.5–99.4% line and 91.3–100% branch coverage. Owner attestation `OWNER_VERIFIED P14-I1 verified_by=tanlocit9` is bound to summary SHA-256 `77c59cef1535dfda4cf3c84d581e6ca3a715ede604b2228e32b20ef6309a2ad4`. P14-I1 is `completed` and P14-I2 is `ready`. This is local verification only; no CI, commit, merge, deployment, provider, production, migration, load, or live-runtime evidence is claimed.

## 2026-10-10 — P14-I1 static topology source implementation

Implemented the Platform-local immutable static topology boundary from existing job-definition seed declarations. Stable kebab-case logical node keys map every `(source, jobType, cronExpr)` definition identity to its `JobType` node, including many definitions sharing one type. The topology validates unknown/unmapped/ambiguous definitions, duplicate/self edges, cycles and graph bounds; exposes deterministic roots, topological order, bounded ancestors/descendants in both directions, and Mermaid diagnostics. Focused unit tests were added but not executed. No Kafka/protobuf, manifest/readiness, dataset path, database, configuration, public API, planner, dispatcher, claim/fencing or worker behavior changed. P14-I1 moved from `ready` to `verification_pending`; P14-I2 remained pending until the approved verification gate completed.

## 2026-10-08 — Static Graph & DispatchPlanner epic planning

Owner promoted the bounded static-graph dispatch design into active Milestone 1 Plan 030 with pending stories P14-I1 topology/validation, P14-I2 snapshot-based pure planning, and P14-I3 dispatcher integration. Source and code-graph review confirmed reusable Platform dependency-registry/guard, candidate-service/repository, atomic claim/fencing, and seed-declaration boundaries; no P14 runtime implementation is claimed. Plan 028 may reuse P14-I1 topology without waiting for P14-I2/P14-I3. Plan 029 basic operations remain independently deliverable; only graph-specific presentation waits for P14-I2. Persisted graph, expanded provider policy, and advanced fairness remain deferred. Existing statuses and historical evidence were preserved; no build, test, lint, format, runtime verification, or commit was performed.

Owner then prioritized Static Graph as the active delivery path: P14-I1 is `ready`, followed by P14-I2 and P14-I3. P4-I3 is `superseded` rather than completed; TD-014 retains its unclosed evidence, and all dependency-aware READY/WAITING/BLOCKED, no-starvation, claim/fencing, retry, compatibility, migration, fallback, and runtime invariants transfer to mandatory P14-I3 acceptance. TD-013 retains unused blocked-job tracking and optional absent evaluators; TD-007 continues to own executor/admission/load risk. On 2026-10-10, P8-I4 and P9-I4 were paused from `in_progress` to `pending`, preserving source/evidence/acceptance while handing off active `apps/core` ownership to P14-I1. No source implementation, executable verification, or completion claim is added by these scheduling decisions.

## 2026-10-07 — Field/DTO impact review and bounded tasks

Reviewed delivery-bearing plans 011/012/013/014/021/022/023/024/027/028/029 against declared contracts and canonical scheduling. Added cross-plan field/DTO inventory, change-kind/impact/behavior tables and bounded task boundaries; candidate types/names/transport remain unresolved until source/contract reconciliation. Phase 13 task splitting preserves all IDs/dependencies/acceptance criteria and isolates startedAt semantic cutover from additive timing and read UI. Idle observed-duration remains a proposal, not an authoritative field contract. Corrected navigation classifications and stale reactivation wording. No runtime implementation, verification, status promotion or schema approval is claimed.

This root-level record replaces the former roadmap execution ledger and the dated
status-reconciliation note. It records durable delivery and verification milestones;
current scheduling metadata remains canonical in
[`docs/plans/roadmap/implementation-increments.md`](docs/plans/roadmap/implementation-increments.md).

## Evidence semantics

- `completed` means the increment met the evidence rules applicable when it was
  accepted. Current verification policy additionally requires cross-service blast-radius
  reconciliation and attributable coverage for changed and directly impacted behavior.
- `source present` and `locally verified` do not imply CI, deployment, provider, or
  production verification.
- Pull requests, commits, and CI are traceability evidence unless an increment declares
  them as explicit delivery gates.
- Historical entries are not current scheduling metadata and do not override the
  canonical increment registry.

## Planning revision — 2026-10-07

Owner requested P4-I3 verification then P13-I1/P13-I2/P13-I4 before the remaining MVP evidence queue. P13-I4 no longer waits for Data Health. P13-I3 caches bounded scan runs/results in Query Service memory only, with TTL, size limits, exact-version keys, refresh, and explicit cache-loss behavior. Calendar/lifecycle classifications retain an owner-decision gate. No runtime implementation, test pass, or completion-status promotion is claimed.

## Debt review and follow-up deferral — 2026-10-07

Owner moved P10/P11/P12 follow-ups into owner-gated technical debt; existing evidence statuses remain unchanged. Reviewed TD-001 through TD-010 against targeted source, kept TD-006 historical/closed as written, recorded partial resolution in TD-008, and added TD-011 for feature deferral. Added source-local TODO references only at confirmed current gaps and a priority index/Mermaid source. Startup Ingestor getmany() drops returned batches in source; potential runtime loss remains unverified. No executable checks or runtime behavior changes are claimed.

## Current delivery snapshot

| Area                                     | Current state                                               | Evidence boundary                                                                                                                        |
| ---------------------------------------- | ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Scheduler claim and transactional outbox | Completed through P1-I2                                     | PostgreSQL concurrency and publish-recovery evidence recorded                                                                            |
| Execution identity hard cutover          | P1-I4 completed                                             | Exact-head CI and migration harness were recorded; no production cutover claimed                                                         |
| Canonical sector writer                  | P1-I3 `verification_pending`                                | Source and earlier local/CI evidence exist; current impact-to-test and attributable coverage evidence is incomplete                      |
| Proto contract foundation                | P2-I1 completed; later migration superseded                 | Initial schemas and Buf checks recorded; superseded work requires owner reactivation                                                     |
| Dataset date normalization               | P3-I4 completed                                             | Local project checks and exact-head CI recorded                                                                                          |
| Dependency guard                         | P4-I1/P4-I2 completed; P4-I3 `superseded`                   | Source baseline retained; TD-014 preserves unclosed evidence and P14-I3 owns final planner-integrated safety proof                       |
| Console job operations                   | P7-I1–P7-I3 completed                                       | Platform/Console checks and CI recorded                                                                                                  |
| Notifications                            | P8-I1/P8-I2/P8-I5 `verification_pending`; P8-I4 `pending`   | P8-I4 source/local evidence retained; paused to release active apps/core ownership for P14                                               |
| Intraday EOD                             | P9-I1 `verification_pending`; P9-I4 `pending`               | P9-I4 source/local evidence retained; paused until dependencies and post-P14 ownership permit resumption                                 |
| Realtime foundation                      | P10-I1/P10-I2 `verification_pending`; P10-I0/P10-I3 blocked | Provider-independent local checks exist; live VCI capability/runtime evidence is absent                                                  |
| Cross-service observability              | P11-I1–P11-I5 pending                                       | Deferred, owner-gated under TD-011; historical dependencies retained                                                                     |
| Worker throughput and writer batching    | P12-I1–P12-I4 pending                                       | Deferred under TD-009/TD-011; preserve evidence and proposed dependencies                                                                |
| Operator trust Console                   | P13-I1–P13-I4 pending                                       | Follows completed P14-I3 for truthful final dispatcher stages; calendar/lifecycle evidence or narrowed classifications remain unresolved |
| Static Graph & DispatchPlanner           | P14-I1 completed; P14-I2 `ready`; P14-I3 pending            | P14-I1 local gate/coverage/owner attestation passed; no CI/deployment/runtime claim; P14-I3 retains superseded P4-I3 safety evidence     |

## Release history

| Date          | Scope                       | Result                                      | Durable evidence and limitations                                                                                                                                                                                                                                   |
| ------------- | --------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 2026-08-12    | P0-I1, P0-I2, P1-I0, P1-I1  | Completed                                   | PR #7 and successful CI established due-query, workspace, ADR, and claim foundations.                                                                                                                                                                              |
| 2026-08-13    | P1-I2                       | Completed                                   | PR #8; PostgreSQL scheduler/outbox concurrency and publish-recovery tests passed.                                                                                                                                                                                  |
| 2026-08-14    | P2-I1                       | Completed                                   | PR #9; Buf format, lint, build, breaking, generation, and artifact checks passed.                                                                                                                                                                                  |
| 2026-08-20–23 | P3-I1/P3-I2                 | Historical local evidence; later superseded | Manifest and MinIO checks were recorded, but failed external CI and incomplete gates prevented completion before the MVP deferral.                                                                                                                                 |
| 2026-08-25    | P4-I1/P4-I2 and P7-I1–P7-I3 | Completed                                   | Platform/Console verification and CI run #149 passed.                                                                                                                                                                                                              |
| 2026-08-25    | P3-I4                       | Completed                                   | Date normalization and READY-last rewrite checks passed locally and in exact-head CI run #154.                                                                                                                                                                     |
| 2026-08-29    | P1-I4                       | Completed                                   | Schema-only migration harness, owning-project checks, and exact-head CI run #33095851356 passed; deployment/production cutover was not claimed.                                                                                                                    |
| 2026-09-05–09 | P8-I1/P8-I2                 | Locally verified; `verification_pending`    | Platform test/build evidence and focused regressions were recorded; live Telegram and current attributable coverage evidence remain incomplete.                                                                                                                    |
| 2026-09-06–22 | P8-I4                       | Source present; `in_progress`               | Analyzer, Platform, Query Service, and Console local checks passed; dependency and current coverage gates remain.                                                                                                                                                  |
| 2026-09-10–12 | P9-I1/P9-I4                 | Source present; evidence incomplete         | Owning-project local checks passed; provider, MinIO/runtime, deployment, and production evidence were not established.                                                                                                                                             |
| 2026-09-12–13 | P10-I1/P10-I2               | Locally verified; `verification_pending`    | Strict tick, replay, archive, rebuild, bars, and reconciliation checks passed in py-common; configured publication and runtime evidence remain absent.                                                                                                             |
| 2026-09-19–27 | P8-I5                       | Source present; `verification_pending`      | Durable notification outbox, retry, fencing, and manual handoff source passed shared Platform test/build; migration and live-provider evidence remain incomplete.                                                                                                  |
| 2026-09-26–27 | P4-I3                       | Source present; `verification_pending`      | Dependency-aware outbox source and focused tests passed shared Platform test/build after repairs; attributable coverage and migration/runtime evidence remain incomplete.                                                                                          |
| 2026-09-21    | Active MVP ordering         | Owner-approved ordering                     | P8-I1 → P8-I2 → P8-I4 → P8-I5 → P9-I1 → P9-I4 → P4-I3 → P1-I3. No status was promoted by this ordering decision.                                                                                                                                                   |
| 2026-10-06    | Phase 13 operator trust     | Owner-approved planning scope               | Added pending P13-I1–P13-I4 for truthful stages/daily throughput/outbox publish ETA, manual EOD Data Health, and fixed Console order. P13-I1 now gates P12-I1; no implementation or verification is claimed.                                                       |
| 2026-10-08    | Plan 030 / P14-I1-P14-I3    | Owner-approved active epic                  | Promoted static topology, snapshot planning, and dispatcher integration into separate pending stories. Plan 028 waits only for P14-I1 when using topology; Plan 029 graph presentation waits for P14-I2. No status promotion or runtime implementation is claimed. |
| 2026-10-10    | P14-I1 static topology      | Source present; `verification_pending`      | Added immutable topology, seed-derived mappings/edges, validation, traversal, diagnostics and focused tests. Executable checks and attributable coverage were not run; P14-I2 remained pending.                                                                    |
| 2026-10-11    | P14-I1 verification         | Completed; P14-I2 promoted to `ready`       | Local Platform test/coverage/build passed; per-class coverage exceeded 80% line/branch; owner attestation recorded. No CI/deployment/runtime claim.                                                                                                                |

## Reconciled architecture decisions

### Execution identity

The P1-I4 cutover uses required generic scheduler persistence fields `workType` and
`workKey` without replacing semantic domain fields in commands. Domain fields such as
`symbolKey`, `sectorKey`, and `exchangeKey` remain domain inputs. The cutover requires
drain/snapshot/manual-history handling and does not authorize a compatibility bypass.

### Dataset publication

Immutable version artifacts are published and validated before replacing `READY.json`.
Failures preserve the previous READY pointer, and dependency approval uses exact
`dataVersion` lineage where required.

### Dependency enforcement

P4-I1/P4-I2 established dependency policies and scheduler enforcement. P4-I3 moves the
final READY/WAITING/BLOCKED decision to claimable outbox dispatch while preserving
fencing, no-attempt WAITING behavior, terminal BLOCKED behavior, and approved input
lineage.

### Deferred and superseded work

The MVP audit superseded unfinished Proto migration, expanded manifest/metadata work,
portable deployment hardening, broad Console/query expansion, P8-I3, P9-I2/P9-I3,
and P9-I5. P10 live provider discovery/runtime remains blocked. These items require an
explicit owner decision before reactivation.

## Where to look next

- Current phase/increment status: [`docs/plans/roadmap/implementation-increments.md`](docs/plans/roadmap/implementation-increments.md)
- Compact roadmap: [`docs/plans/roadmap/README.md`](docs/plans/roadmap/README.md)
- Verification and automation policy: [`docs/plans/roadmap/automation-rules.md`](docs/plans/roadmap/automation-rules.md)
- Cross-phase definition of done: [`docs/plans/roadmap/cross-phase-rules.md`](docs/plans/roadmap/cross-phase-rules.md)
- Supporting plan index: [`docs/README.md`](docs/README.md)

## Maintenance rule

Append only meaningful implementation, verification, release, deferral, and owner
policy milestones. Do not duplicate the full increment registry here, and do not use
this file to change status, dependency, or execution order. Update the canonical
registry first, then add a concise historical release note when durable evidence or a
material decision changes.
