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

## Storage Contract Rule

Do not put S3 bucket names or object paths into Kafka messages for routing. Workers derive object paths from shared path builders backed by [`configs/shared/s3-paths.yaml`](configs/shared/s3-paths.yaml).

When changing storage paths or dataset ownership:

1. Update shared config/path builders.
2. Update all dataset producers and consumers.
3. Update tests.
4. Update [Data lake](docs/data/data-lake.md).
5. Run impact analysis for affected storage contracts.

## Shared Module Rule

When introducing or modifying reusable object-oriented abstractions or design patterns, prefer the appropriate shared module instead of duplicating them inside individual applications.

Placement rules:

- Java/JVM reusable abstractions: prefer an appropriate shared Java package/module.
- Python reusable abstractions: prefer [`libs/py-common`](libs/py-common).
- Keep application-specific business logic inside the owning application.
- Do not move code into shared modules only because it might be reusable; there must be a clear cross-module responsibility.
- Before creating a new abstraction, use code-review-graph to check whether an equivalent abstraction already exists.
- When changing shared abstractions, check impact radius and verify all affected implementations, callers, tests, and docs.

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
7. Report any verification command that could not be run or failed.

For docs-only changes, run a lightweight documentation verification such as link/path checks when available and report that code tests were not required.
