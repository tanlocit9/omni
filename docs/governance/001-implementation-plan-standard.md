# Omni Implementation Plan Standard

## Purpose

Every implementation plan must describe what to build, what concrete outcome is produced, what data/metadata/contracts change, which analytical features become available, how the change is verified, and which repository guidance files must be synchronized for coding agents.

## Mandatory Sections

Every implementation plan must include the following sections. Older plans inherit this standard; when an older plan is touched, add the missing sections instead of preserving an outdated format.

### Goal

State the problem and intended boundary.

### Outcome

Describe the concrete capability available after implementation.

### Dataset Outputs

List new/modified persistent analytical datasets and logical paths.

If none:

```text
No analytical dataset output.
```

### Metadata Outputs

For data-producing work, define object-storage manifest paths and readiness/version semantics.

Normal write order:

```text
write data -> validate -> publish READY manifest last
```

Do not add PostgreSQL/Redis only to cache dataset statistics in V1.

If none:

```text
No dataset metadata output.
```

### Algorithm Feature Outputs

List reusable fields/features available to rule-based strategies, backtests, statistical models or ML.

Classify when relevant:

- `DIRECT` — persisted explicitly;
- `DERIVED` — reproducibly computed;
- `CONDITIONAL` — depends on optional provider data.

If none:

```text
No direct algorithm feature output.
```

### Algorithms Unlocked

State which later analytical/research capability becomes possible or safer.

### Contract Impact

Every plan must explicitly state whether it changes:

```text
Kafka/service-to-service protobuf
object-storage JSON manifest
storage path/dataset ownership
public Java/Python API
configuration/environment contract
```

Cross-service transport contracts use canonical proto3 definitions under `libs/contracts/proto` after migration.

Persisted dataset manifests remain JSON in object storage unless a future ADR changes that decision.

Physical S3/R2 paths must not become business-routing fields in Kafka contracts.

### Repository Guidance Updates

Every plan must list the repository guidance files that need updates when implementation changes architecture, contracts, workflows, development rules, or tool usage.

Review at minimum:

```text
AGENTS.md
CLAUDE.md
.roo/rules/          # Zoo Code workspace rules
docs/README.md       # when canonical docs/plans change
the relevant flow/data/service docs
```

Rules:

1. If guidance changes, update it in the same implementation change.
2. Do not mark the plan Done while agent/rule guidance describes the old architecture.
3. Keep `AGENTS.md` as the main repository-wide rule source; avoid duplicating long architecture prose in agent files.
4. Zoo Code rules should be small, actionable and link back to canonical docs/`AGENTS.md` where possible.
5. `CLAUDE.md` should contain Claude/Nx/tool-specific guidance and defer repository architecture rules to `AGENTS.md`.
6. If no guidance update is required, say so explicitly with a short reason.

### Verification

Verification scope is derived from behavior and blast radius, not from commit or pull-request ownership.

Every plan must:

1. identify its intended source files, symbols, contracts, persistence, configuration, and operational surfaces;
2. run code-review-graph impact analysis for the implemented files and reconcile the bounded graph result against the canonical cross-service documents indexed by [`docs/README.md`](../README.md);
3. record an explicit impact matrix covering each applicable service, shared library/contract, persistence store, configuration surface, test project, and operational boundary, including a concrete no-impact reason where applicable;
4. map every impacted production file or safety-critical symbol to executed unit, integration, contract, or migration tests;
5. collect attributable line and branch coverage for increment-owned behavior whenever the project exposes coverage instrumentation; and
6. define focused Nx targets/tests/contract checks that exercise the mapped behavior.

A commit hash, branch, pull request, merge, or broad suite pass is traceability evidence, not proof that the increment is behaviorally verified. It must not substitute for blast-radius reconciliation or attributable coverage.

Coverage requirements are risk-based:

- changed and directly impacted production code must have attributable executed-test evidence;
- safety-critical logic involving concurrency, retries, idempotency, compatibility, authorization, data loss, READY-last publication, lineage, or transactional state transitions requires explicit success, failure, and boundary-branch coverage;
- each plan must declare numeric line and branch thresholds for its critical components; the default minimum is 80% line and 80% branch coverage unless the plan documents a stricter threshold or a justified instrumentation limitation;
- aggregate project coverage cannot hide an uncovered impacted component; and
- when instrumentation is unavailable, a feature/source/test matrix with executed focused tests is required and the limitation remains visible rather than being inferred as a coverage pass.

Shared contract changes must include producer and consumer tests. Cross-language Kafka, storage, HTTP, and generated-contract boundaries must be reconciled explicitly because bounded graph hops do not prove no impact.

Agents must not execute build, test, lint, format, coverage, affected checks, or equivalent underlying tools unless the current user prompt explicitly requests them or the user approves a concrete command list. Until approved, record required checks as **not run**; do not treat them as passed or waive them from acceptance criteria.

### Acceptance Criteria

Include functional completion plus documentation/guidance synchronization.

## Data Plan Rules

1. Prefer reusable canonical features over strategy-specific scores.
2. Keep raw/reusable data separate from final strategy decisions.
3. Make time/evaluation semantics explicit.
4. Use object-storage manifests as the default dataset readiness/freshness contract.
5. Do not scan a full object prefix merely to decide whether a known partition is READY when a manifest exists.
6. Failed writes must not publish a new READY manifest.
7. Record upstream `dataVersion` lineage when downstream freshness depends on the exact upstream dataset version.

## Contract Rules

1. Cross-service/Kafka message source of truth: proto3 under `libs/contracts/proto`.
2. Generated Java/Python protobuf code must never be hand-edited.
3. Run protobuf lint + breaking checks before merging contract changes.
4. Producer and consumer sides must be updated/reviewed together.
5. Do not reuse protobuf field numbers; reserve deleted field numbers/names.
6. Use versioned packages such as `omni.contracts.job.v1`.
7. Object-storage DatasetManifest remains JSON and is a separate persisted contract.

See:

- `CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md`
- `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`
- `JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md`

## Feature Naming

Use stable `snake_case` names and include timeframe/window when meaning depends on it.

The same semantic feature must use the same name in EOD, intraday, backtest and realtime pipelines.

## Provider-Dependent Features

Never assume provider fields that are not guaranteed. Mark dependent outputs `CONDITIONAL`, especially aggressor side, bid/ask depth, order IDs, trade conditions and sequence IDs.

## Shared Placement Rule

Reusable hand-written abstractions/patterns belong in shared locations when responsibility is genuinely cross-module:

- Java: appropriate shared/common package/module;
- Python: `libs/py-common`;
- canonical language-neutral contracts: `libs/contracts/`.

Generated protobuf code is derived output; do not treat it as a place for hand-written business abstractions.

## Definition of Done Rule

A plan is not Done until:

```text
implementation complete
+ blast radius reconciled across graph and canonical cross-service docs
+ every impacted production path mapped to executed tests
+ critical-component line/branch coverage thresholds satisfied or an explicit instrumentation limitation remains unresolved
+ required tests/checks complete
+ contract docs complete
+ feature/metadata docs complete where applicable
+ AGENTS/CLAUDE/Zoo Code guidance synchronized where applicable
```

Commit, pull-request, merge, and CI identifiers improve traceability but are not semantic completion gates unless an increment explicitly requires delivery through that channel. They never replace impact or coverage evidence.

## Field/DTO Inventory and Bounded Delivery

Every new or touched delivery-bearing plan includes a Field/DTO Inventory and Bounded Delivery section. See the [reviewed inventory](../reference/002-planned-field-dto-impact.md). Preserve historical evidence and distinguish proposed schema from source presence.

| Surface/owner                            | Field or DTO with type, nullability and units             | Change kind                                   | Impact                          | Behavior/compatibility                                                                     | Delivery task    |
| ---------------------------------------- | --------------------------------------------------------- | --------------------------------------------- | ------------------------------- | ------------------------------------------------------------------------------------------ | ---------------- |
| Producer → consumer / persistence or API | Exact field or candidate name; mark unresolved explicitly | REUSE / ADD / SEMANTIC / DERIVED / UNRESOLVED | LOW / MEDIUM / HIGH with reason | Defaults, legacy handling, authorization, retries/identity, rollout/rollback as applicable | One bounded task |

LOW is presentation/local read behavior; MEDIUM is bounded additive API/configuration behavior; HIGH includes persisted semantics, shared contracts, algorithm decisions, transaction/offset/identity or writer ownership. These levels describe blast radius, not priority.

A small number of plans does not imply small delivery. Break large increments into reviewable tasks with one primary behavior change and explicit acceptance/rollback boundaries. Keep a shared producer/consumer migration coherent; never split deployment in a way that breaks compatibility. Separate read projection from semantic migration, offset safety from concurrency, scanner from repair, and UI composition from new service ownership.

Task suffixes do not create independently scheduled increments, change dependencies, or waive original acceptance/coverage gates. Add new canonical increments only through an explicit scheduling decision. Do not add all candidate fields as DB columns: first inventory existing evidence and decide which facts need persistence.

Exact idle duration, unapproved transport DTOs, provider guarantees and calendar classifications must remain unavailable/UNRESOLVED until evidence and contract decisions are frozen. No invented timestamp or zero default may replace unknown evidence.
