# Omni Release Notes

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

| Area                                     | Current state                                                 | Evidence boundary                                                                                                                    |
| ---------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Scheduler claim and transactional outbox | Completed through P1-I2                                       | PostgreSQL concurrency and publish-recovery evidence recorded                                                                        |
| Execution identity hard cutover          | P1-I4 completed                                               | Exact-head CI and migration harness were recorded; no production cutover claimed                                                     |
| Canonical sector writer                  | P1-I3 `verification_pending`                                  | Source and earlier local/CI evidence exist; current impact-to-test and attributable coverage evidence is incomplete                  |
| Proto contract foundation                | P2-I1 completed; later migration superseded                   | Initial schemas and Buf checks recorded; superseded work requires owner reactivation                                                 |
| Dataset date normalization               | P3-I4 completed                                               | Local project checks and exact-head CI recorded                                                                                      |
| Dependency guard                         | P4-I1/P4-I2 completed; P4-I3 `verification_pending`           | P4-I3 source and shared Platform test/build evidence exist; impact/coverage and runtime migration evidence remain incomplete         |
| Console job operations                   | P7-I1–P7-I3 completed                                         | Platform/Console checks and CI recorded                                                                                              |
| Notifications                            | P8-I1/P8-I2/P8-I5 `verification_pending`; P8-I4 `in_progress` | Platform and owning-service local checks exist; increment-specific impact/coverage and applicable runtime evidence remain incomplete |
| Intraday EOD                             | P9-I1 `verification_pending`; P9-I4 `in_progress`             | Local owning-project checks exist; provider/storage/runtime evidence remains incomplete                                              |
| Realtime foundation                      | P10-I1/P10-I2 `verification_pending`; P10-I0/P10-I3 blocked   | Provider-independent local checks exist; live VCI capability/runtime evidence is absent                                              |
| Cross-service observability              | P11-I1–P11-I5 pending                                         | Deferred, owner-gated under TD-011; historical dependencies retained                                                                                               |
| Worker throughput and writer batching    | P12-I1–P12-I4 pending                                         | Deferred under TD-009/TD-011; preserve evidence and proposed dependencies                                                              |
| Operator trust Console                   | P13-I1–P13-I4 pending                                         | Owner-approved scope; Query Service memory caching approved; calendar/lifecycle evidence or narrowed classifications remain unresolved                                |

## Release history

| Date          | Scope                       | Result                                      | Durable evidence and limitations                                                                                                                                                                             |
| ------------- | --------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 2026-08-12    | P0-I1, P0-I2, P1-I0, P1-I1  | Completed                                   | PR #7 and successful CI established due-query, workspace, ADR, and claim foundations.                                                                                                                        |
| 2026-08-13    | P1-I2                       | Completed                                   | PR #8; PostgreSQL scheduler/outbox concurrency and publish-recovery tests passed.                                                                                                                            |
| 2026-08-14    | P2-I1                       | Completed                                   | PR #9; Buf format, lint, build, breaking, generation, and artifact checks passed.                                                                                                                            |
| 2026-08-20–23 | P3-I1/P3-I2                 | Historical local evidence; later superseded | Manifest and MinIO checks were recorded, but failed external CI and incomplete gates prevented completion before the MVP deferral.                                                                           |
| 2026-08-25    | P4-I1/P4-I2 and P7-I1–P7-I3 | Completed                                   | Platform/Console verification and CI run #149 passed.                                                                                                                                                        |
| 2026-08-25    | P3-I4                       | Completed                                   | Date normalization and READY-last rewrite checks passed locally and in exact-head CI run #154.                                                                                                               |
| 2026-08-29    | P1-I4                       | Completed                                   | Schema-only migration harness, owning-project checks, and exact-head CI run #33095851356 passed; deployment/production cutover was not claimed.                                                              |
| 2026-09-05–09 | P8-I1/P8-I2                 | Locally verified; `verification_pending`    | Platform test/build evidence and focused regressions were recorded; live Telegram and current attributable coverage evidence remain incomplete.                                                              |
| 2026-09-06–22 | P8-I4                       | Source present; `in_progress`               | Analyzer, Platform, Query Service, and Console local checks passed; dependency and current coverage gates remain.                                                                                            |
| 2026-09-10–12 | P9-I1/P9-I4                 | Source present; evidence incomplete         | Owning-project local checks passed; provider, MinIO/runtime, deployment, and production evidence were not established.                                                                                       |
| 2026-09-12–13 | P10-I1/P10-I2               | Locally verified; `verification_pending`    | Strict tick, replay, archive, rebuild, bars, and reconciliation checks passed in py-common; configured publication and runtime evidence remain absent.                                                       |
| 2026-09-19–27 | P8-I5                       | Source present; `verification_pending`      | Durable notification outbox, retry, fencing, and manual handoff source passed shared Platform test/build; migration and live-provider evidence remain incomplete.                                            |
| 2026-09-26–27 | P4-I3                       | Source present; `verification_pending`      | Dependency-aware outbox source and focused tests passed shared Platform test/build after repairs; attributable coverage and migration/runtime evidence remain incomplete.                                    |
| 2026-09-21    | Active MVP ordering         | Owner-approved ordering                     | P8-I1 → P8-I2 → P8-I4 → P8-I5 → P9-I1 → P9-I4 → P4-I3 → P1-I3. No status was promoted by this ordering decision.                                                                                             |
| 2026-10-06    | Phase 13 operator trust     | Owner-approved planning scope               | Added pending P13-I1–P13-I4 for truthful stages/daily throughput/outbox publish ETA, manual EOD Data Health, and fixed Console order. P13-I1 now gates P12-I1; no implementation or verification is claimed. |

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
