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

Cross-service transport contracts use canonical proto3 definitions under `contracts/proto` after migration.

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

Define the Nx targets/tests/contract checks needed for the change.

Shared contract changes should include producer/consumer tests and `nx affected` checks.

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

1. Cross-service/Kafka message source of truth: proto3 under `contracts/proto`.
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
- canonical language-neutral contracts: `contracts/`.

Generated protobuf code is derived output; do not treat it as a place for hand-written business abstractions.

## Definition of Done Rule

A plan is not Done until:

```text
implementation complete
+ tests/checks complete
+ contract docs complete
+ feature/metadata docs complete where applicable
+ AGENTS/CLAUDE/Zoo Code guidance synchronized where applicable
```
