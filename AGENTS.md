# AGENTS.md

This file provides guidance to agents when working with code in this repository.

System architecture and business documentation live under [`docs`](docs); do not duplicate that content here.

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

Examples:

```bash
nx run analyzer:add --name="pandas>=2.2.0"
nx run ingestor:remove --name="requests"
nx run py-common:sync
nx run analyzer:lock
```

Do not run underlying package managers directly when an equivalent Nx target exists:

- Do not run `uv add` directly when `<project>:add` exists.
- Do not run `uv remove` directly when `<project>:remove` exists.
- Do not run `uv sync` directly when `<project>:sync` exists.
- Do not run `uv lock` directly when `<project>:lock` exists.
- Do not run `pip install`, `npm install`, Gradle, Maven, pytest, ruff, or uvicorn directly when a matching Nx target exists.

### Development and verification

Use the corresponding Nx target instead of calling the underlying tool directly:

- Build: `nx run <project_name>:build`
- Test: `nx run <project_name>:test`
- Lint: `nx run <project_name>:lint`
- Format: `nx run <project_name>:format`
- Serve: `nx run <project_name>:serve`
- Serve with HMR: `nx run <project_name>:serve-hmr`
- Debug: `nx run <project_name>:debug`
- Package: `nx run <project_name>:package`
- Deploy: `nx run <project_name>:deploy`

If additional arguments are required, forward them after `--`.

### Project-specific commands and style

- The Java project at [`apps/core`](apps/core) is named `platform`; inspect it with `nx show project platform`, not `core`.
- Run one Python test with `nx run <project>:test -- tests/<file>.py::<test_name>`; each target sets its project as the working directory, so service code intentionally imports its local `app` package. The analyzer and ingestor Ruff targets omit root entry points; query-service explicitly includes `main.py`.
- [`apps/ingestor/tests/integration`](apps/ingestor/tests/integration) is excluded from `ingestor:test`; run it only through `nx run ingestor:test-integration`.
- Run one console test with `nx run omni-console:test -- src/<file>.test.tsx -t "<name>"`; run one Java test with `nx run platform:test -- --tests "<fully.qualified.Test.method>"`.
- Python linting is Ruff `E`, `F`, `UP`, `B`, `SIM`, and `I` at 88 columns. Only FastAPI projects analyzer and query-service ignore `B008` for `Depends()` defaults. TypeScript is ESM, ESLint permits zero warnings, and Prettier uses single quotes.
- Cross-service Python behavior belongs in [`libs/py-common`](libs/py-common); canonical transport schemas belong in [`libs/contracts/proto`](libs/contracts/proto). Never hand-edit generated output in [`libs/contracts/gen`](libs/contracts/gen).
- Protobuf changes require the defined `contracts` targets: `format`, `lint`, `breaking`, `generate-check`, and `test` (which generates before validating artifacts).

Only use the underlying tool directly when no suitable Nx target exists. In that case:

1. State that the Nx target is unavailable.
2. Use the narrowest required command.
3. Consider adding a reusable Nx target if the operation will be repeated.

## PowerShell Execution Rule

When using terminal commands that require PowerShell syntax or PowerShell-only features, wrap the full command with `powershell -NoProfile -Command "..."`.

This applies to PowerShell cmdlets and expressions such as `Get-ChildItem`, `Select-String`, `Test-Path`, `ForEach-Object`, `-ErrorAction`, PowerShell object pipelines, and `.ps1` scripts.

Correct examples:

```text
powershell -NoProfile -Command "Get-ChildItem -Recurse -File -Path docs -Filter *.md"
powershell -NoProfile -Command "Select-String -Path AGENTS.md -Pattern 'PowerShell'"
powershell -NoProfile -Command "Test-Path docs/README.md"
```

Incorrect examples:

```text
Get-ChildItem -Recurse -File -Path docs -Filter *.md
Select-String -Path AGENTS.md -Pattern 'PowerShell'
Test-Path docs/README.md
```

Do not wrap native Nx commands solely because this rule exists. Use `nx run <project>:<target>` directly when no PowerShell syntax is required. If PowerShell syntax is needed around an Nx command, wrap the whole expression:

```text
powershell -NoProfile -Command "nx run analyzer:test; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }"
```

Before every `execute_command` call, check whether the command contains PowerShell syntax. If it does, the submitted command string must start with `powershell -NoProfile -Command`.

## Code Review Graph Rules

This project provides `code-review-graph` MCP tools. Use them before manually scanning the codebase when locating implementations, tracing dependencies, reviewing changes, or assessing impact.

Required workflow:

- Use `semantic_search_nodes` or `query_graph` before Grep/Glob/manual reads when locating implementations, exploring unfamiliar code, or tracing classes, methods, interfaces, dependencies, producers, or consumers.
- Use `get_impact_radius` before changing shared contracts, public methods, DTOs, events, Kafka messages, storage paths, or configuration.
- Use `detect_changes` when reviewing commits, branches, pull requests, or uncommitted changes.
- Use graph results to identify callers, downstream dependents, implementations, references, producers, consumers, tests, and documentation updates.
- Fall back to file search only after graph tools identify relevant files or when graph data is unavailable.

Correct sequence for unfamiliar code:

```text
semantic_search_nodes or query_graph
→ targeted read_file/search_files
→ impact radius when changing contracts
→ detect_changes after edits
```

Incorrect sequence:

```text
search_files first for unfamiliar implementation
→ edit shared contract
→ skip impact radius
→ skip detect_changes
```

## Kafka Contract Rule

When modifying Kafka producer or consumer code, inspect and update both sides of the contract.

This includes changes to:

- topic names;
- message schemas and required fields;
- serialization and deserialization;
- validation rules;
- metadata semantics;
- error handling and status responses;
- tests, configuration, and documentation.

Required workflow:

1. Identify the producer.
2. Identify the consumer.
3. Check shared payload/config abstractions.
4. Run impact analysis for the changed contract.
5. Update both sides in the same change.
6. Update tests for both sides.
7. Update [Kafka contracts](docs/data/kafka-contracts.md).
8. Run `detect_changes` after edits.

Never assume a producer-only or consumer-only change is safe without checking its impact through code-review-graph.

## Cross-Service Proto3 Contract Rule

For cross-service Kafka/service-to-service payloads migrated to protobuf, the canonical source is:

```text
libs/contracts/proto/**/*.proto
```

Rules:

1. Java and Python use generated types from the same proto source; do not maintain handwritten mirror DTOs for canonical protobuf messages.
2. Generated protobuf files under `libs/contracts/gen` are ignored build output; never commit or edit them manually.
3. Use the `contracts` Nx targets for format/lint/generate/breaking checks when available; consumer build/package targets must depend on `contracts:generate` before compiling generated types.
4. Never change or reuse an existing protobuf field number.
5. Deleted fields must reserve their old number and name.
6. Every enum must have an `*_UNSPECIFIED = 0` value.
7. Prefer additive optional fields and versioned packages such as `omni.contracts.job.v1`.
8. Migrate producer/consumer pairs with a rollout-safe sequence; compatible consumers should be deployed before producers switch format when needed.
9. DatasetManifest remains a persisted JSON contract in object storage; do not force it into protobuf merely for consistency.
10. Kafka business messages use logical dataset identifiers/partitions rather than physical S3/R2 paths.

See [Cross-Service Proto3 Contracts plan](docs/CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md).

## Storage Contract Rule

Do not put S3 bucket names or object paths into Kafka messages for routing. Workers derive object paths from shared path builders backed by [`configs/shared/s3-paths.yaml`](configs/shared/s3-paths.yaml).

When changing storage paths or dataset ownership:

1. Update shared config/path builders.
2. Update all dataset producers and consumers.
3. Update tests.
4. Update [Data lake](docs/data/data-lake.md).
5. Run impact analysis for affected storage contracts.

Normal dataset readiness checks must read the corresponding metadata manifest instead of scanning the full Parquet prefix when a manifest exists.

## Dataset Manifest Rule

Every canonical dataset partition publishes an immutable JSON version manifest at `_metadata/datasets/<dataset>/<partition_path>/versions/<dataVersion>.json`, then replaces `_metadata/datasets/<dataset>/<partition_path>/READY.json` after successful Parquet write and validation. Empty partitions use the reserved `_default` path segment.

Manifests provide:

- `status`: READY, PROCESSING, or FAILED
- `dataVersion`: Deterministic content-based fingerprint for lineage tracking
- `rowCount`, `columnCount`: Dataset statistics
- `columns`: Schema metadata with types and nullability
- `minTimestamp`, `maxTimestamp`: Time range when applicable
- `inputs[]`: Upstream dataset versions consumed (for dependency tracking)
- `schemaHash`: Deterministic schema fingerprint
- `generatedAt`: ISO 8601 UTC timestamp

### READY-Last Semantics

The immutable version manifest and mutable READY pointer must be published after Parquet data is written and validated. The immutable version is written first and READY is replaced last, so `status: "READY"` guarantees data validity.

Never publish READY before completing the Parquet write, validation, and immutable-version publication. If any step fails, do not perform a compensating READY write; preserve the prior READY object.

### Deterministic Data Versioning

`dataVersion` is calculated from canonical, sorted identity inputs:

```text
SHA256(dataset + normalized_partition + schemaHash + object_checksums + inputs)
```

Object checksums and byte lengths come from the exact persisted Parquet bytes. `generatedAt` is excluded from the fingerprint.

This enables:

- Exact dependency tracking via `inputs[].dataVersion`
- Change detection without scanning Parquet files
- Lineage verification across pipeline stages

### Readiness Checks

When checking if a dataset partition is ready:

1. Read `_metadata/datasets/<dataset>/<partition_path>/READY.json`.
2. Check `status === "READY"`.
3. Verify `dataVersion` matches the expected upstream version when needed.

Do not scan the Parquet prefix for existence checks when a manifest exists. Use the manifest as the source of truth.

### Integration Points

Dataset producers must call `publish_dataset_manifest()` from `py_common.storage.manifest` after successful Parquet writes:

```python
from py_common.storage.manifest import publish_dataset_manifest, ManifestWriter

# After writing Parquet data
await publish_dataset_manifest(
    writer=manifest_writer,
    dataset='eod',
    partition={'exchange': 'hose'},
    data_path='eod/hose/*.parquet',
    dataframe=eod_df,
    object_checksums=[('eod/hose/data.parquet', 'sha256:<exact-byte-hash>')],
    object_count=1,
    total_bytes=parquet_write_result.byte_length,
    inputs=[],  # Upstream dependencies if applicable
)
```

When modifying dataset producers or consumers, check if manifests need updates:

1. Run impact analysis for the dataset contract
2. Update manifest schema if column types/names change
3. Update `inputs[]` if upstream dependencies change
4. Update tests to verify manifest publication
5. Update [Data lake](docs/data/data-lake.md)

See [Dataset Metadata Manifest plan](docs/DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md).

## Job Dependency Rule

Cron timing gaps are scheduling hints, not dependency guarantees.

Hard data dependencies use centralized dataset manifests:

```text
dependsOnDatasets -> hard readiness/currentness
dependsOnJobs     -> operational/traceability dependency
```

A due job whose dataset dependency is missing/stale is `BLOCKED`/deferred, not a failed job execution.

When a downstream dataset must reflect the current upstream version, compare its recorded `inputs[].dataVersion` with the current upstream manifest `dataVersion`.

See [Job Dependency Guard plan](docs/JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md).

## Shared Module Rule

When introducing or modifying reusable object-oriented abstractions or design patterns, prefer the appropriate shared module instead of duplicating them inside individual applications.

Placement rules:

- Java/JVM reusable abstractions: prefer an appropriate shared Java package/module.
- Python reusable abstractions: prefer [`libs/py-common`](libs/py-common).
- Language-neutral cross-service schemas: prefer [`libs/contracts`](libs/contracts).
- Keep application-specific business logic inside the owning application.
- Do not move code into shared modules only because it might be reusable; there must be a clear cross-module responsibility.
- Before creating a new abstraction, use code-review-graph to check whether an equivalent abstraction already exists.
- When changing shared abstractions, check impact radius and verify all affected implementations, callers, tests, and docs.

Generated protobuf code is derived output and is not a location for hand-written design patterns/business logic.

## Implementation Plan / Repository Guidance Sync Rule

Every implementation plan follows [Omni Implementation Plan Standard](docs/IMPLEMENTATION_PLAN_STANDARD.md).

A plan/implementation is not Done if repository guidance still describes the old architecture, contract, workflow, or tooling.

For material changes, review and update when relevant:

```text
AGENTS.md
CLAUDE.md
.roo/rules/          # Zoo Code workspace rules
relevant docs/flows, docs/data and service README files
```

When touching an older plan that lacks `Contract Impact` or `Repository Guidance Updates`, add those sections.

Keep agent/Zoo rules concise and link to canonical docs rather than copying full plan prose.

## Documentation Rules

- Documentation entry point: [docs/README.md](docs/README.md).
- Architecture overview: [docs/architecture/system-overview.md](docs/architecture/system-overview.md).
- Developer navigation: [docs/development/where-to-change.md](docs/development/where-to-change.md).
- Flow changes: update the relevant document under [docs/flows](docs/flows).
- Kafka contract changes: update [docs/data/kafka-contracts.md](docs/data/kafka-contracts.md).
- Storage contract changes: update [docs/data/data-lake.md](docs/data/data-lake.md).
- Service responsibility changes: update the related service README.
- Prefer Mermaid diagrams and tables over long prose.

## Repository Boundaries

- Do not modify files inside [`externals`](externals) unless explicitly working on the external submodule.
- Do not commit secrets. Local credentials in compose/env examples are development defaults only.
- Do not hard-code cloud SDK credentials, provider secrets, or regions in business handlers.
- Do not call Kafka brokers directly from business handlers when a port/helper abstraction exists.
- Do not run long-running watcher commands as an agent unless explicitly requested.

## Verification Rules

After making code or contract changes:

1. Run `detect_changes` through code-review-graph.
2. Check impact radius for modified shared contracts.
3. Verify affected callers, consumers, producers, and tests.
4. Inspect relevant Nx targets.
5. Run targeted build, test, lint, and formatting checks through Nx.
6. Run affected checks when changes impact multiple projects.
7. For protobuf changes, run contract format/lint/breaking/generation checks through Nx when available.
8. Verify relevant docs and AGENTS/CLAUDE/Zoo Code guidance are synchronized.
9. Report any verification command that could not be run or failed.

For docs-only changes, run a lightweight documentation verification such as link/path checks when available and report that code tests were not required.
