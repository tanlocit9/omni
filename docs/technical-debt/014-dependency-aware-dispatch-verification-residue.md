# Dependency-Aware Dispatch Verification Residue

## Decision — 2026-10-08

| Field                      | Assessment                                                                                                                                                                                       |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Type                       | Superseded implementation/evidence residue                                                                                                                                                       |
| Status                     | DEFERRED / safety semantics absorbed by Plan 030 P14-I3                                                                                                                                          |
| Former roadmap owner       | P4-I3 — Dependency-aware outbox dispatch and terminal blocking                                                                                                                                   |
| Replacement delivery owner | P14-I3 — Dispatcher integration with preserved claim and fencing                                                                                                                                 |
| Reason                     | Static Graph & DispatchPlanner now changes candidate selection and dispatcher integration. Completing the old dispatcher increment first would verify a path that P14-I3 will materially revise. |

P4-I3 source and historical evidence remain valid baseline information, but P4-I3 is no longer an active completion prerequisite. Its unclosed verification package is deferred here rather than reported as completed.

## Preserved Source Baseline

Current source provides:

- scheduled and accepted manual work commit execution plus PENDING scheduler-outbox intent before dependency evaluation;
- `DependencyRegistry` adapts the manifest-backed guard into READY, WAITING, and BLOCKED decisions;
- WAITING remains PENDING and does not consume a publish attempt;
- terminal BLOCKED closes the outbox/execution and cannot be reclaimed;
- READY work is atomically claimed with lease/token/instance fencing;
- publish acknowledgement and retry update the exact fenced claim;
- focused unit and PostgreSQL integration tests exist for parts of this behavior;
- V11 adds dependency reason and BLOCKED visibility while retaining existing rows.

Source presence and historical focused tests do not prove completion. No new verification result is asserted by this debt record.

## Evidence Not Closed Under P4-I3

The former increment did not record a complete package for:

- cross-service blast-radius reconciliation;
- impact-to-test mapping for every changed/directly impacted safety path;
- attributable line and branch coverage;
- complete READY/WAITING/BLOCKED, FIFO/no-starvation, retry, compatibility, concurrency, and migration branches;
- applicable runtime migration and operational evidence;
- final documentation consistency and completion evidence.

These gaps must not be copied forward as a passing result.

## Transfer to P14-I3

P14-I3 must prove the final post-planner dispatcher behavior. The following are mandatory acceptance invariants, not optional technical debt:

1. every planner-selected candidate is evaluated through `DependencyRegistry`;
2. planner/topology state never substitutes for manifest readiness or exact `dataVersion` approval;
3. WAITING does not consume a publish attempt and cannot starve unrelated selected work;
4. terminal BLOCKED is bounded, diagnosable, non-reclaimable, and not fabricated as worker FAILED;
5. atomic claim rechecks status, availability, lease, and fence identity after the advisory snapshot;
6. concurrent dispatchers cannot publish one logical outbox row twice through an unfenced claim;
7. publish retry preserves message/execution identity and requires the exact claim token/owner;
8. legacy PENDING/PUBLISHED/BLOCKED rows remain compatible through rollout and rollback;
9. the fallback path restores bounded FIFO selection without bypassing the guard or deleting state;
10. migration, concurrency, compatibility, impact, coverage, and applicable runtime evidence are recorded against the final P14-I3 implementation.

P14-I3 may reuse or adapt existing P4-I3 source/tests, but completion is judged only against the final integrated path.

## Deferred Follow-ups That Remain Separate

This record does not activate:

- legacy `blocked_jobs` cleanup or optional missing evaluators, owned by TD-013;
- async guard executor/admission/load work, owned by TD-007;
- persisted topology, provider-aware policy, adaptive quotas, or advanced fairness, retained by TD-012/Plan 030 deferred scope;
- empty-output/data-completeness semantics, owned by TD-005;
- broader operator dashboards or observability rollout.

## Contract Impact

| Contract area                     | Decision                                                                                                           |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Kafka/service-to-service protobuf | Unchanged by deferral. Existing command/status transport remains as-is unless P14-I3 separately declares a change. |
| Object-storage JSON manifests     | Unchanged; manifest READY and exact `dataVersion` remain the readiness authority.                                  |
| Storage paths/dataset ownership   | Unchanged.                                                                                                         |
| Public Java/Python API            | No new public API from this debt move. Internal dispatcher/registry APIs may be refactored by P14-I3.              |
| Configuration/environment         | No immediate change. P14-I3 owns planner activation/fallback configuration.                                        |
| PostgreSQL                        | V11 and existing rows are retained. No rollback/deletion migration is authorized by this classification.           |

## Closure

Close this debt when P14-I3 is completed with the transferred invariants and full attributable evidence, or when a later owner decision assigns a different final dispatcher owner. A separate P4-I3 completion is no longer required and must not be reconstructed merely to close historical metadata.

## Verification Boundary

Static source, roadmap, and graph inspection informed this classification. No build, test, lint, format, migration, load, runtime, deployment, or CI command was run.
