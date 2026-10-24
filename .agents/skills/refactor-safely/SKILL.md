---
name: refactor-safely
description: Plan or perform an explicitly requested behavior-preserving Omni refactor with caller, contract and impact review. Use for structural changes or renames; do not infer permission to remove code from graph suggestions alone.
---

# Refactor Safely

Follow [AGENTS.md](../../../AGENTS.md), preserve unrelated worktree changes and
keep the refactor within the requested scope. A behavior, architecture, security
or data-model change requires its applicable owner decision rather than being
silently included as refactoring.

## Workflow

1. Discover graph tools and inspect the target's callers, imports, flows and impact
   before unfamiliar implementation scanning. Reconcile reflection, dependency
   injection, scheduled work, generated contracts and cross-language consumers
   against source/configuration and canonical ownership documents.
2. State the observable behavior and compatibility that must remain. Graph
   suggestions, dead-code results and large-function reports are optional discovery
   hints, not authorization to delete code or proof that a symbol has no users.
3. For a rename or structural edit, inspect the proposed locations before applying
   changes. Use a graph refactor preview when supported; re-read affected files and
   preserve edits outside the agent's ownership. Do not apply an expired preview
   or guess at dynamically referenced callers.
4. Update affected tests, documentation and guidance with the refactor. Run graph
   change detection and inspect the bounded owned diff for unintended contract or
   runtime changes. Graph analysis is not behavioral verification.
5. Run declared Nx regression/coverage/build/format targets only within the user's
   approved scope. When execution is not authorized, record checks as not run and
   leave behavioral verification unresolved; never infer success from compilation
   expectations or test-file presence.

Report the structural change, preserved behavior, impact and actual checks or
limitations. Do not commit, push, merge or deploy unless explicitly requested.
