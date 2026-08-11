# Agent and Development Rules

This file contains repository workflow rules for AI agents and developers. System architecture and business documentation live under [`docs`](docs); do not duplicate that content here.

## Nx Rules

Nx is the canonical entry point for all project operations in this monorepo.

- Run commands from the workspace root.
- Inspect available targets before execution:

```bash
nx show project <project_name>
```

- Never invent a target that is not defined in the project's `project.json`.
- Always invoke project operations through:

```bash
nx run <project_name>:<target>
```

### Dependency management

When adding, removing, installing, syncing, locking, or updating dependencies, use the matching Nx target if it exists.

Do not run underlying package managers directly when an equivalent Nx target exists.

### Development and verification

Use corresponding Nx targets for build/test/lint/format/serve/package/deploy. If additional arguments are required, forward them after `--`.

Only use the underlying tool directly when no suitable Nx target exists. State that the target is unavailable and consider adding a reusable target when the operation will be repeated.

## PowerShell Execution Rule

When terminal work requires PowerShell syntax or PowerShell-only features, wrap the full command with:

```text
powershell -NoProfile -Command "..."
```

Do not wrap native Nx commands solely because this rule exists.

## Code Review Graph Rules

This project provides `code-review-graph` MCP tools. Use them before manually scanning the codebase when locating implementations, tracing dependencies, reviewing changes, or assessing impact.

Required workflow:

- `semantic_search_nodes` or `query_graph` first for unfamiliar implementations/dependencies;
- `get_impact_radius` before changing shared contracts, public methods, DTOs, events, Kafka messages, storage paths or configuration;
- `detect_changes` for commits/branches/PRs/uncommitted changes;
- use graph results to identify callers, downstream dependents, producer/consumer pairs, tests and docs;
- fall back to file search only when graph data is unavailable or after the graph narrows the files.

## Kafka / Cross-Service Contract Rule

When modifying Kafka producer/consumer code, inspect and update both sides of the contract.

Canonical direction after the protobuf migration:

```text
contracts/proto/**/*.proto
  -> generated Java/Python types
  -> service boundary adapters
```

Rules:

1. Cross-service Kafka payload schemas use canonical proto3 definitions under `contracts/proto` once the topic/message family is migrated.
2. Do not maintain handwritten Java and Python DTO copies of a canonical protobuf message.
3. Generated protobuf files must never be edited manually.
4. Use the `contracts` Nx targets for format/lint/generate/breaking checks when available.
5. Never change/reuse an existing protobuf field number.
6. Deleted protobuf fields must reserve their old number and name.
7. Every enum has an `*_UNSPECIFIED = 0` value.
8. Prefer additive optional fields and versioned packages such as `omni.contracts.job.v1`.
9. Producer and consumer migration must be rollout-safe; deploy compatible consumers before switching producers when needed.
10. Update `docs/data/kafka-contracts.md` for transport-contract changes.

Do not force DatasetManifest into protobuf: persisted S3/R2 `_metadata/*.json` remains a separate JSON contract.

See `docs/CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md`.

## Storage Contract Rule

Do not put bucket names or physical object paths into Kafka messages for business routing. Services exchange logical dataset identifiers/partitions; shared path resolvers map them to S3/R2/MinIO paths.

When changing storage paths, dataset ownership, manifest schema, or READY semantics:

1. update shared config/path/manifest builders;
2. update all producers/consumers;
3. update tests;
4. update `docs/data/data-lake.md`;
5. check impact radius;
6. update agent/Zoo rules when the workflow rule itself changes.

Normal readiness checks read dataset manifests rather than scanning full Parquet prefixes.

## Job Dependency Rule

Cron timing gaps are not dependency guarantees.

A due job with hard data dependencies must pass the manifest-based dependency guard before execution is created/dispatched.

Preferred semantics:

```text
dependsOnDatasets -> hard data readiness/currentness
dependsOnJobs     -> operational/traceability dependency
```

A missing/stale upstream dataset is `BLOCKED`, not a failed job execution.

Use current upstream `dataVersion` versus downstream `inputs[].dataVersion` for `CURRENT_INPUTS` checks.

See `docs/JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md`.

## Shared Module Rule

When introducing reusable hand-written object-oriented abstractions or design patterns:

- Java/JVM reusable abstractions: prefer an appropriate shared/common package/module;
- Python reusable abstractions: prefer `libs/py-common`;
- language-neutral service contracts: `contracts/`;
- keep app-specific business logic inside the owning application;
- do not move code into shared merely because it might be reusable;
- check impact radius for shared abstractions.

Generated protobuf code is derived output, not a location for hand-written patterns/business logic.

## Implementation Plan / Agent Guidance Sync Rule

Every implementation plan follows `docs/IMPLEMENTATION_PLAN_STANDARD.md`.

A plan/implementation is not Done if repository guidance still describes the old architecture or workflow.

For every material architecture/contract/workflow/tooling change, review:

```text
AGENTS.md
CLAUDE.md
.roo/rules/          # Zoo Code workspace rules
relevant docs/flows, docs/data and service README files
```

When touching an older implementation plan that lacks `Contract Impact` or `Repository Guidance Updates`, add those sections.

Keep agent rules concise and link to canonical docs rather than duplicating full plan prose.

## Documentation Rules

- Documentation entry point: `docs/README.md`.
- Architecture overview: `docs/architecture/system-overview.md`.
- Developer navigation: `docs/development/where-to-change.md`.
- Flow changes update the relevant `docs/flows/*` file.
- Kafka/Proto changes update `docs/data/kafka-contracts.md`.
- Storage/manifest changes update `docs/data/data-lake.md`.
- Service responsibility changes update the related service README.
- Planning changes follow `docs/IMPLEMENTATION_PLAN_STANDARD.md`.

## Repository Boundaries

- Do not modify `externals` unless explicitly working on the external submodule.
- Do not commit secrets.
- Do not hard-code cloud credentials/provider secrets/regions in business handlers.
- Do not call Kafka brokers directly from business handlers when a port/helper abstraction exists.
- Do not run long-running watchers as an agent unless explicitly requested.

## Verification Rules

After code/contract changes:

1. run `detect_changes` through code-review-graph;
2. check impact radius for shared contracts;
3. verify affected callers/consumers/producers/tests;
4. inspect relevant Nx targets;
5. run targeted build/test/lint/format through Nx;
6. run `nx affected` when multiple projects are impacted;
7. for protobuf changes run contracts lint/breaking/generation checks;
8. verify relevant docs and agent/Zoo guidance are synchronized;
9. report checks that could not run or failed.

For docs-only changes, run lightweight link/path validation when available and report that code tests were not required.
