---
name: review-changes
description: Review scoped Omni code changes for concrete correctness, compatibility and safety regressions using graph impact and source evidence. Use for code review; documentation-only audits and increment verification have separate skills.
---

# Review Changes

Review is read-only unless fixes are requested. Follow [AGENTS.md](../../../AGENTS.md)
for worktree boundaries and verification permissions; do not edit, stage or create
Git delivery actions merely because a review was requested.

## Workflow

1. Establish the requested files or comparison once. Discover code-review-graph
   capabilities and use change detection plus targeted caller/flow/impact queries
   before broad unfamiliar source inspection. Prefer compact results, expanding
   for unresolved high-risk paths; do not impose a call/token quota on evidence.
2. Review changed and directly impacted behavior against source/configuration and
   canonical ownership/contracts. Explicitly follow applicable HTTP/Kafka/storage,
   persistence, retry, transaction, authorization and concurrency boundaries;
   bounded graph hops cannot prove other services unaffected.
3. Investigate concrete risks and inspect relevant tests. Graph tests_for links,
   test-file presence and test names are discovery evidence only. Distinguish
   executed results/attributable coverage from unexecuted expectations. Run checks
   only when authorized and use declared Nx targets.
4. Report actionable findings in severity order with file/line, trigger, impact
   and evidence. Recommend focused regression cases where a risk lacks proof.
   Avoid speculative defects and generic cleanup unrelated to the requested scope.

If no actionable defect is found, say so and state material review/verification
limits. Give a merge recommendation only when requested; never merge automatically.
Use [verify-document-consistency](../verify-document-consistency/SKILL.md) for a
documentation audit and [verify-increment](../verify-increment/SKILL.md) for the
complete increment acceptance/coverage gate. A code review does not replace either.
