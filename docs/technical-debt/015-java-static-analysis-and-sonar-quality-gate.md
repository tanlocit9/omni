# Java Static Analysis and Sonar Quality Gate

## Decision — 2026-10-11

| Field             | Assessment                                                                                                                                        |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| Type              | Code quality / static-analysis tooling                                                                                                            |
| Status            | OPEN / Sonar tooling unavailable; targeted Java warnings confirmed                                                                                |
| Current owner     | Platform Java quality follow-up; not assigned to an active roadmap increment                                                                      |
| Priority          | P2 conditional; promote a bounded tooling or remediation slice when warning growth, review friction, CI policy, or a concrete defect justifies it |
| Immediate blocker | No. This record does not block P14-I2/P14-I3 unless a finding demonstrates incorrect runtime mapping or unsafe planner/dispatcher behavior.       |

## Goal

Track confirmed Java type-safety and tooling gaps without treating IDE warnings as runtime failures or claiming a Sonar result when the repository has no configured Sonar scanner. Establish a bounded path to adopt static analysis and remediate findings without broad suppressions, Java-version drift, or unrelated refactoring.

## Outcome

A future promoted increment should provide:

- one declared Nx target for Platform static analysis;
- a repository-owned SonarQube/SonarCloud or equivalent Java quality configuration;
- an approved quality profile and baseline policy;
- deterministic local and CI invocation with credentials kept outside source;
- triage that separates defects, security findings, maintainability findings, provider/API false positives, and accepted suppressions;
- targeted fixes with focused tests where runtime behavior can change.

This debt record does not install a scanner, select a hosted service, create credentials, or change the Java toolchain.

## Confirmed Findings

### 1. Native JPA unchecked result conversion

[`SchedulerOutboxRepositoryImpl.findPendingCandidateIds()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/SchedulerOutboxRepositoryImpl.java) declares `List<UUID>` but returns `getResultList()` from a native JPA query. The IDE resolves the query result as a raw `List`, producing:

```text
Type safety: The expression of type List needs unchecked conversion to conform to List<UUID>
```

The query selects PostgreSQL UUID `id` values and passes `UUID.class`, so the current provider is expected to return UUID values. The generic JPA/native-query API and IDE type inference still cannot prove the element type statically. This is a warning, not evidence of a current runtime failure.

Required remediation assessment:

1. prefer a typed Hibernate/JPA query API when it is supported consistently by the project stack;
2. otherwise receive `List<?>` and perform one explicit checked conversion with an actionable error;
3. allow a narrow suppression only when provider behavior is covered by PostgreSQL integration tests and the suppression explains the boundary;
4. do not apply a repository-wide unchecked suppression.

### 2. Native-query mapping inventory

Static search found native-query usage in:

- `SchedulerOutboxRepositoryImpl`;
- `JobDefinitionRepositoryImpl`;
- `NotificationOutboxRepositoryImpl`.

Seven native-query paths call `getResultList()`. Most multi-column paths already use `List<?>` and explicit `Object[]`/`Number` conversion. A future analysis slice must inspect each scalar and row mapping for database-provider types, nullability, enum conversion, numeric narrowing, and actionable failure messages rather than assuming all native results are type safe.

### 3. Explicit unchecked suppressions

Static search found targeted unchecked suppressions in:

- `DatasetDependency.fromConfig()`;
- `JobExecutionContext.getDependsOnDatasets()`;
- `CurrentInputsEvaluator` parameter handling.

These suppressions cross untyped map/list configuration boundaries. They require schema/shape validation review before removal; presence alone is not proof of a defect.

### 4. Java 21 and Lombok constructor diagnostics

The Platform toolchain is Java 21. The IDE previously reported:

```text
The Java feature 'Flexible Constructor Bodies' is only available with source level 25 and above
```

for Lombok-generated constructors in `PlatformDependencyRegistry` and `JobDependencyContextFactory`. Those two classes now use explicit Java 21 constructors. Other Lombok constructor annotations remain in the codebase and have compiled successfully in prior Java 21 Platform gates; they must not be mass-rewritten without a reproduced diagnostic or an approved Lombok-removal policy.

The repository must remain on its declared Java 21 toolchain unless a separate upgrade decision changes that contract. Do not silence IDE diagnostics by enabling Java 25 preview/source behavior.

## Sonar Tooling Gap

Static repository inspection found no:

- SonarQube or SonarCloud Gradle plugin;
- `sonar-project.properties` or equivalent project configuration;
- Sonar server/project identifiers;
- Nx `platform:sonar` or repository-wide Sonar target;
- documented quality gate, baseline, exclusions, or CI integration.

Therefore no valid repository-declared Sonar check was available to run. Sonar status is **NOT RUN / TOOLING UNAVAILABLE**. Build, test, coverage, lint, compiler warnings, and ad hoc IDE diagnostics must not be relabeled as a Sonar pass.

## Bounded Adoption Plan

A promoted Sonar/static-analysis increment should be split into reviewable tasks:

1. choose SonarQube, SonarCloud, or an approved equivalent and define ownership/cost/privacy boundaries;
2. add the scanner plugin and one declared Nx target without embedding credentials;
3. import existing JaCoCo XML and configure Java 21 source/binary paths;
4. run an initial baseline without failing unrelated delivery;
5. classify baseline findings by severity, rule, component, and false-positive rationale;
6. define a new-code quality gate before deciding whether legacy debt becomes blocking;
7. remediate one bounded category at a time, starting with correctness/security and unsafe native mappings;
8. add CI only after local target reproducibility and owner approval.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No analytical algorithm is unlocked. The value is safer Java maintenance, reproducible code-quality review, and earlier detection of correctness/security regressions.

## Contract Impact

| Contract area                        | Decision                                                                                                                                         |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| Kafka or service-to-service protobuf | Unchanged. Static-analysis adoption must not alter transport schemas by itself.                                                                  |
| Object-storage JSON manifest         | Unchanged.                                                                                                                                       |
| Storage paths or dataset ownership   | Unchanged.                                                                                                                                       |
| Public Java or Python API            | No current change. Individual remediation must declare API impact separately.                                                                    |
| Configuration or environment         | No current change. Future Sonar adoption requires non-secret scanner/server/project configuration and external credentials.                      |
| PostgreSQL                           | No schema change. Native-query remediation may change internal mapping code but must preserve SQL semantics and PostgreSQL integration coverage. |

## Repository Guidance Updates

Reviewed:

- `AGENTS.md`: no update required; Nx and verification approval rules already apply.
- `CLAUDE.md`: no update required until a Sonar target exists.
- `.roo/rules`: no update required until tooling usage changes.
- `docs/README.md` and `docs/INDEX.md`: register this technical-debt record.
- `docs/development/001-where-to-change.md`: no update required until the scanner target and workflow exist.

When a Sonar target is introduced, synchronize repository guidance, CI/deployment documentation where applicable, and the project target inventory in the same change.

## Verification

Performed:

- static search for Sonar configuration, plugins, targets, and documentation;
- static inventory of native-query result-list paths, unchecked suppressions, and Lombok constructor annotations;
- confirmation that Platform declares Java 21 in `apps/core/build.gradle.kts`;
- confirmation that `apps/core/project.json` has no Sonar target.

Not run:

- Sonar analysis, because no scanner/configuration/declared Nx target exists;
- build, test, coverage, lint, or format for this documentation classification;
- runtime/database/provider checks.

## Acceptance Criteria

This debt may be closed only when:

- [ ] one repository-declared Nx static-analysis target exists for Platform;
- [ ] Java 21, generated-source, test, JaCoCo, and exclusion paths are configured reproducibly;
- [ ] no credentials or private server tokens are committed;
- [ ] the initial baseline and quality-profile/version are recorded;
- [ ] new-code versus legacy-code gate behavior is approved and documented;
- [ ] the native JPA unchecked conversion is fixed or narrowly justified with PostgreSQL executed-test evidence;
- [ ] all remaining unchecked suppressions have a validated boundary or a tracked remediation owner;
- [ ] Java/Lombok diagnostics are reproduced and fixed narrowly rather than hidden by source-level drift;
- [ ] affected production behavior is mapped to executed tests and attributable coverage when remediation changes runtime code;
- [ ] applicable documentation and repository guidance are synchronized;
- [ ] the final static-analysis gate passes without overstating CI, deployment, or production evidence.

## Activation Triggers

Promote a bounded slice when any of the following occurs:

- Sonar/static analysis becomes a delivery or CI requirement;
- warning count materially increases or creates repeated review friction;
- a native-query mapping causes or plausibly masks a runtime type failure;
- a correctness/security rule identifies an actionable defect;
- a Java/Lombok/toolchain upgrade reintroduces incompatible constructor diagnostics;
- the owner approves a dedicated Platform quality-gate increment.

## Non-Goals

- upgrading Java to 25 merely to silence an IDE diagnostic;
- mass removal of Lombok without measured benefit and coverage;
- blanket `@SuppressWarnings("unchecked")` additions;
- treating all maintainability findings as release blockers on first adoption;
- changing Kafka, storage, manifest, scheduler claim, or planner semantics as part of scanner setup.
