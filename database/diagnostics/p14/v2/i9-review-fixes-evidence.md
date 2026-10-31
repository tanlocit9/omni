# I9 preset mapping and UUID lock-order repairs

Owner: [Plan 030](../../../../docs/plans/030-static-graph-dispatch-planner.md).
Bounded repair plus the [shared-worker scenario](i9-atomic-sharing-evidence.md),
not final I9 acceptance or live activation.

## Changes and impact

GraphWorkExpansion preserves ancestor preset `parameters`, then overrides only
the fields projected by the existing graph mapping. Strategy qualification uses
the effective WorkExpansion configuration, so nested preset strategy overrides
are respected. Invalid non-object parameters still fail explicitly. The I8 identity
algorithm, static edge ownership, request selection and consumer envelope remain
unchanged; workers receive the correctly resolved source/strategy/other parameters.

DefinitionConsolidationService.rollback locks definitions in canonical UUID text
order, matching PostgreSQL UUID order used by graph preparation/consolidation.
Java UUID natural order compares signed longs and could lock high-bit UUIDs first.
Rollback still refuses configuration/identity/active-lease conflicts and does not
rewind cadence or alter execution history.

Platform graph preparation, rollback and their tests are directly impacted. Existing
Analyzer/Ingestor workers, shared contracts/libraries, Query Service reads and MinIO
paths are exercised without application changes; Console has no changed API/UI.
Persistence uses existing V17/V18 and transactions, with no migration or data cleanup.
Configuration changes only add the opt-in test target. Existing fixture clock/ACK
helpers are included because the new worker test depends on them. No live flags,
Kafka offsets, dataset ownership or deployment behavior changes.

## Regression matrix

| Criterion                                                                                        | Source                                                           | Executed check                                                                                                                                                                  |
| ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Preserve preset source/lookback/indicatorSource; project only supplied timeframe/source mappings | GraphWorkExpansion.project                                       | ancestorProjectionRetainsPresetParametersAndOnlyOverridesMappedFields                                                                                                           |
| Match nested strategy overrides, reject a genuine mismatch                                       | GraphWorkExpansion.strategyMatches; WorkExpansion.resolve        | strategyMatchingUsesNestedPresetOverridesInsteadOfRawDefinitionDefaults                                                                                                         |
| Reject malformed preset parameters instead of hiding them                                        | GraphWorkExpansion.project                                       | invalidAncestorPresetParametersCannotBeHiddenByProjection                                                                                                                       |
| Compatible inventory/rollback/reuse/barriers                                                     | DefinitionConsolidationService; GraphWorkRequestService; V17/V18 | DefinitionConsolidationTest and GraphWorkRequestsPostgresTest                                                                                                                   |
| Same lock order across the UUID signed boundary                                                  | DefinitionConsolidationService.rollback                          | DefinitionConsolidationLockPostgresTest: hold low UUID, observe rollback's actual pg_stat_activity lock wait, acquire high UUID NOWAIT, release and require successful rollback |
| Actual shared attempts, ownership, approval, replay and query; legacy compatibility              | I9 and many-step worker fixtures                                 | JobRunAtomicSharingIntegrationTest and JobRunCrossServiceIntegrationTest, OMNI_P14_E2E=1, zero skips required                                                                   |

The PostgreSQL lock test uses two deterministic UUIDs and a real database wait,
not a sleep-based guess about thread ordering. It does not claim that every possible
deadlock or retained-environment rollback has been tested.

## Verification

Gate `P14-I9-review-fixes-20261031` requires scoped Java format, Analyzer fixture
Ruff, Ingestor fixture Ruff, combined regression/worker execution and scoped docs
format. **PASS P14-I9-review-fixes-20261031, required=5/pass=5**, read through
`python tools/check_result.py conclusion --increment P14-I9-review-fixes-20261031`.
The final combined run passed **38 tests, zero failures/errors/skips**: 15 graph
expansion, 4 inventory, 16 graph PostgreSQL, 1 real lock-contention and 2 actual-worker
stories. All 13 source bindings were unchanged during that final run. JUnit,
JaCoCo, source bindings, workflow output and executed logs are saved under the
ignored gate directory. PostgreSQL diagnostics fixtures also select the named
`jsonMapper` bean, avoiding ambiguous-by-type lookup in a clean checkout.
Two initial combined runs had one failing new fixture assertion: indicator subsets
(`ema20`, then `MA20`) normalize to the full canonical indicator set under the
existing mapper policy. The regression now asserts preset `indicatorSource=ad_high`,
alongside source/lookback preservation and mapped timeframe override. The entire
combined scope is rerun, without changing production indicator policy or waiving
the failures.

| Critical component             | Line coverage   | Branch coverage |
| ------------------------------ | --------------- | --------------- |
| GraphWorkExpansion             | 93.8% (137/146) | 83.5% (81/97)   |
| DefinitionConsolidationService | 97.0% (161/166) | 72.0% (72/100)  |
| GraphWorkRequestService        | 96.2% (151/157) | 73.3% (63/86)   |

These numbers are attributable to the final combined run, not an aggregate project
substitute. Consolidation/request branch coverage remains below the 80% increment
threshold; bounded repair verification does not close the full I9 coverage gate.
The combined test command selects GraphWorkExpansionTest,
DefinitionConsolidationTest, DefinitionConsolidationLockPostgresTest,
GraphWorkRequestsPostgresTest, JobRunAtomicSharingIntegrationTest and
JobRunCrossServiceIntegrationTest. It runs on disposable infrastructure only.

Documentation: Plan 030, this report, shared-worker report, release notes and
diagnostics/Platform navigation are reconciled. Registry and roadmap status remain
verification_pending for I8/I9. Architecture, ADR, flows, Kafka/data-lake/database,
deployment, ownership, service contracts, indexes and agent guidance require no new
semantic decision: these changes restore approved preset ownership and transaction
ordering. Retained migration/consolidation, provider verification, complete critical
coverage and rollout remain separate gates. No I10/writer activation is implied.
