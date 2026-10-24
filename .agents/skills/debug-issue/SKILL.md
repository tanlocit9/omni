---
name: debug-issue
description: Diagnose a concrete Omni defect or failure using graph-assisted caller tracing, source evidence and a bounded reproduction. Use for debugging an observed issue; general code navigation belongs to explore-codebase.
---

# Debug Issue

Follow [AGENTS.md](../../../AGENTS.md) for scope, worktree ownership, Nx commands
and verification permissions. This skill does not authorize running checks or
changing product, architecture, contracts or production data.

## Workflow

1. Establish the observed behavior, expected result and affected boundary from the
   supplied evidence. Discover available code-review-graph tools before unfamiliar
   source inspection; locate the relevant symbols, then trace callers/callees and
   applicable flows. Prefer compact output and expand when evidence is insufficient.
2. Check index freshness and unresolved/truncated relationships. Reconcile relevant
   HTTP, Kafka, worker, persistence and storage boundaries against canonical docs;
   graph reachability alone cannot prove no impact. If graph access is unavailable,
   report the limitation and inspect the smallest relevant source/configuration.
3. Form a concrete hypothesis and distinguish it from a reproduced cause. Use a
   bounded reproduction only within the user's authorized verification scope;
   follow project Nx targets and avoid destructive data or provider actions.
4. When a fix is requested, preserve unrelated changes and existing safety
   contracts. Analyze impact before shared-boundary edits, then run graph change
   detection. Execute relevant regression checks only when authorized; otherwise
   report them as not run. Do not treat a static graph result as a passing test.

Report the cause and evidence, changes if any, verification outcome and remaining
uncertainty. Do not assume a recent change caused the issue without evidence.
