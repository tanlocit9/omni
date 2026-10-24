---
name: update-implementation-plans
description: Reconcile Omni implementation plans, roadmap evidence and related documentation against canonical ownership and executed verification, preserving approved scope and distinguishing local evidence from increment completion.
---

# Update Implementation Plans

Use this shared repository entrypoint for plan/evidence reconciliation across
coding agents. Keep the canonical skill under `.agents/skills`; editor-specific
rules link here instead of maintaining another copy. Follow
[AGENTS.md](../../../AGENTS.md) for execution permissions.

## Canonical owners

- [Plan standard](../../../docs/governance/001-implementation-plan-standard.md): mandatory sections, contract declarations and coverage requirements.
- [Roadmap](../../../docs/plans/roadmap/README.md) and [increment registry](../../../docs/plans/roadmap/implementation-increments.md): scheduling, status, dependencies and ownership.
- [Automation rules](../../../docs/plans/roadmap/automation-rules.md) and [cross-phase rules](../../../docs/plans/roadmap/cross-phase-rules.md): authority, readiness and completion.
- The numbered implementation plan: bounded behavior and acceptance evidence.
- [ReleaseNotes.md](../../../ReleaseNotes.md): dated execution and decision history; do not create or require an absent `roadmap/execution-log.md`.
- [Documentation index](../../../docs/README.md): canonical architecture, ADR, flow, data, deployment, development and service references.

## Workflow

1. Identify the requested update and its canonical owner. Inspect the minimum
   relevant plan/registry/source/evidence; preserve historical records and unrelated
   worktree changes. Use graph discovery before unfamiliar source inspection.
2. For source or contract changes, reconcile graph impact against Platform,
   Analyzer, Ingestor, Query Service, Console, shared contracts/libraries,
   persistence, configuration, tests and operations. Record concrete impacts or
   no-impact reasons; bounded graph results cannot prove cross-language isolation.
3. Keep the plan's required sections and explicit contract decisions. Follow
   producers/consumers, compatibility, migrations, READY-last, exact lineage and
   storage ownership when applicable. Do not edit generated contracts.
4. Bind implementation claims to actual source and executed checks. Record a
   feature/source/test matrix and attributable coverage. Use declared Nx targets
   after inspecting the project. Record not-run/failed/incomplete checks honestly;
   this workflow does not grant build/test/lint/format or deployment permission.
5. Reconcile applicable plan, registry, roadmap summary, release notes and direct
   dependent readiness together. Review architecture, ADR, flow, Kafka/data-lake/
   database, deployment, ownership, service guides, indexes, AGENTS, CLAUDE and
   repository rules. Update affected guidance or record a concrete no-update reason
   for each reviewed area. Link to canonical facts instead of duplicating policy.
6. Run graph change detection after edits and apply
   [verify-document-consistency](../verify-document-consistency/SKILL.md) for the
   static audit. Review links, mandatory sections, dependencies, evidence wording
   and owned diffs before claiming documentation synchronization.

## Status and authority

Use registry statuses only. Do not promote an increment merely because source,
a commit, broad tests or an owner attestation exists. Completion needs the full
acceptance, reconciled impact, attributable coverage, required checks, documentation
and applicable runtime/delivery evidence. CI/Git/deployment gates apply only when
the owning increment or delivery workflow requires them.

Keep `owner_verified` separate from semantic completion; record attestation only
through [verify-increment](../verify-increment/SKILL.md) after explicit owner review.
Never infer verifier identity or fabricate attestation.

Resolve stale factual descriptions using canonical precedence. Ask for an owner
decision only when reconciliation would change approved product/architecture,
contract strategy, material phase order, destructive rollout or acceptance scope.
Do not weaken criteria, rewrite historical results or silently enable deferred work.

## Report

State the documents/evidence changed, executed checks and attributable coverage,
static audit outcome, current increment status and any remaining gates. Distinguish
local verification from CI, deployment, providers and live runtime. Do not commit,
push, merge, deploy or change permissions without authorization.
