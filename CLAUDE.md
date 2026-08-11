<!-- nx configuration start-->
<!-- Leave the start & end comments to automatically receive updates. -->

# General Guidelines for working with Nx

- When running tasks (build, lint, test, e2e, etc.), always prefer `nx` (`nx run`, `nx run-many`, `nx affected`) instead of the underlying tooling directly.
- Use Nx MCP/workspace/project tools first when available to understand workspace architecture and targets.
- For Nx configuration/best practices, use current Nx docs/tools instead of assumptions.
- For plugin-specific guidance, inspect `node_modules/@nx/<plugin>/PLUGIN.md` when present.

<!-- nx configuration end-->

---

# Codebase Architecture & Guidelines

Repository architecture and development rules are canonical in **AGENTS.md**. Do not duplicate the full architecture here.

## MCP Tools: code-review-graph

Use code-review-graph before Grep/Glob/Read for unfamiliar code and contract-impact work:

- `semantic_search_nodes` / `query_graph` for discovery;
- `get_impact_radius` before shared contracts/events/Kafka/config/storage changes;
- `detect_changes` for review;
- `get_affected_flows` for execution-path impact;
- graph test relationships before manual scanning.

Fall back to direct file search/read only when graph coverage is insufficient or after graph results narrow the relevant files.

## Cross-Service Contracts

Follow the canonical rules in `AGENTS.md` and:

```text
docs/CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md
```

When protobuf contracts are implemented:

- use `nx run contracts:<target>` rather than direct Buf/protoc commands when the Nx target exists;
- never edit generated protobuf sources;
- review producer and consumer together;
- run protobuf breaking checks before completing the change;
- keep DatasetManifest JSON separate from Kafka protobuf contracts.

## Implementation Plan Guidance Sync

Every plan follows:

```text
docs/IMPLEMENTATION_PLAN_STANDARD.md
```

When implementing or materially editing a plan, inspect its `Contract Impact` and `Repository Guidance Updates` sections.

If architecture/contracts/workflows/tool usage changed, synchronize:

```text
AGENTS.md
CLAUDE.md
.roo/rules/
relevant canonical docs
```

Do not mark an implementation complete while agent guidance describes the previous architecture.
