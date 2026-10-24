---
name: explore-codebase
description: Locate Omni ownership, entrypoints and execution paths using the code graph and canonical documentation. Use for code navigation and architecture questions without an observed failure or requested refactor.
---

# Explore Codebase

This workflow is read-only. Follow [AGENTS.md](../../../AGENTS.md) and begin with
the [ownership guide](../../../docs/development/001-where-to-change.md) and
[documentation index](../../../docs/README.md) for the requested area.

## Workflow

1. Discover available code-review-graph capabilities and use the smallest relevant
   context/search/query before manually scanning unfamiliar implementation. Graph
   stats or architecture/community views help broad questions; a known symbol or
   file usually needs only targeted caller/callee/import/children queries.
2. Follow the entrypoint through the owning service and directly relevant flows.
   Inspect actual source/configuration to establish behavior; distinguish active
   runtime callers from unused scaffolding and planned capabilities.
3. Reconcile cross-service boundaries against canonical architecture, flow, Kafka,
   data-lake, database and service documentation when relevant. Identify producers,
   consumers, persistence and ownership rather than inferring them from graph hops.
4. Note stale indexes, absent embeddings, unresolved links or truncated output.
   Use targeted text/path inspection as a documented fallback. Prefer compact
   results but expand enough to answer accurately; no fixed call/token quota
   replaces evidence.

Return the responsible files/symbols with locations, the relevant execution path
and any uncertainty. Do not edit files or run build/test/lint/format as part of
navigation. Use debug-issue for an observed failure and refactor-safely for an
authorized behavior-preserving change.
