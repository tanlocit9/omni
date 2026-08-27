# Roadmap Automation Rules

These rules apply only to explicitly requested roadmap execution or scheduled
roadmap automation; they are not the default workflow for ordinary local tasks.
They make the roadmap executable by a scheduled Codex agent without granting
authority to merge, weaken quality gates, or make material product decisions.

During roadmap execution, establish branch, HEAD, pull-request, and worktree state
once, cache that baseline for the current run, and reuse it until an operation or
external change can invalidate it. Reconcile again only before branch creation,
commit, push, pull-request updates, CI evidence capture, or final reporting. Any
persistent cache must be ignored, advisory, and validated against Git at those
boundaries.

## Source of truth

- Canonical roadmap index: [`README.md`](README.md).
- Canonical increment registry: [`implementation-increments.md`](implementation-increments.md).
- Cross-phase definition of done: [`cross-phase-rules.md`](cross-phase-rules.md).
- Phase detail files: [`phase-0-immediate-correctness.md`](phase-0-immediate-correctness.md) through [`phase-10-realtime-per-tick.md`](phase-10-realtime-per-tick.md).
- Supporting detail plans under [`../../docs`](../../docs) are reconciled references, not competing schedules.

Current source and verified behavior take precedence when recording factual baseline. Do not silently override an agreed architecture decision; mark the contradiction as an owner decision.

## Base branch and reconciliation

- Use `main` as the canonical planning baseline and integration branch.
- Create increment branches from the latest successful `main` commit after reconciling open branches and pull requests.
- Never depend on a temporary planning branch after its pull request has merged or the branch has been deleted.
- Re-read the registry after every merge before selecting new work.

## Increment lifecycle metadata

Every increment uses the metadata table format from [`implementation-increments.md`](implementation-increments.md). Do not mix YAML front matter and tables between phase files.

Allowed status values:

| Status                 | Meaning                                                                                                     |
| ---------------------- | ----------------------------------------------------------------------------------------------------------- |
| `pending`              | Known work, but dependencies are not complete or readiness has not been verified.                           |
| `ready`                | Dependencies are completed, no owner decision blocks implementation, and acceptance criteria are objective. |
| `in_progress`          | A branch or draft PR owns active implementation work.                                                       |
| `blocked`              | Work cannot proceed without owner input, external access, or resolved contradiction.                        |
| `verification_pending` | Implementation exists, but required local checks, CI, or review evidence is incomplete.                     |
| `completed`            | Acceptance criteria, required tests, CI, docs, and evidence are recorded.                                   |
| `superseded`           | Replaced by another increment or plan with a recorded reason.                                               |

Allowed execution modes:

| Mode                | Meaning                                                                                           |
| ------------------- | ------------------------------------------------------------------------------------------------- |
| `autonomous`        | Agent may implement within documented architecture.                                               |
| `approval_required` | Owner must approve a proposed decision before implementation.                                     |
| `manual`            | Requires credentials, production access, destructive operations, or work outside agent authority. |

## Selection rules

1. Continue an `in_progress` increment that has an existing draft PR.
2. Continue a `verification_pending` increment whose implementation exists but CI or review is incomplete.
3. Otherwise select the highest-priority `ready` increment whose dependencies are `completed`.
4. Prefer correctness, CI, test infrastructure, contracts, and data integrity before UI or analytical features.
5. If priority is equal, select the lowest phase/increment number unless the roadmap records a different reason.
6. Never select `blocked`, `approval_required`, `manual`, `superseded`, or dependency-incomplete work.
7. Treat increments touching the same owned module as conflicting unless the roadmap explicitly proves isolation.

## Readiness propagation

After completing or reconciling an increment, evaluate every direct dependent currently marked `pending`.

Promote a dependent to `ready` only when:

- every increment in `depends_on` is `completed`;
- `execution_mode` is `autonomous`;
- `requires_owner_decision` is `false`;
- objective acceptance criteria and verification commands exist;
- no `in_progress` or `verification_pending` increment conflicts with its owned modules.

Do not promote approval-required or manual work. Record every status promotion in the registry and execution log during the same roadmap update.

## Branch and pull request ownership

Use one branch and one draft pull request per increment.

Branch format:

```text
codex/<increment-id>-<short-slug>
```

Examples:

```text
codex/p1-i1-scheduler-claiming
codex/p2-i1-proto-build
```

Rules:

- Search the current branch list and open local/remote PR metadata once during
  reconciliation; repeat it only if the cached baseline may be stale or before
  creating a branch.
- Continue an existing draft PR when it owns the selected increment.
- Do not create multiple PRs for the same increment.
- Do not mix unrelated increments in one PR.
- Do not merge to `main` automatically.
- Record the PR URL and current head commit in increment metadata.
- Keep the PR draft until acceptance criteria and CI pass.

## Implementation, test, and CI repair loop

For each scheduled run:

1. Reconcile the selected increment with the latest branch state once and cache
   the result for the run.
2. Implement only its documented scope.
3. Add or update tests that prove the acceptance criteria.
4. Run targeted checks first.
5. Run affected lint, formatting, test, and build targets through Nx.
6. Push the branch and inspect GitHub CI when a draft PR exists.
7. If CI fails because of the change, diagnose and repair it.
8. Repeat for at most three implementation/CI attempts in one scheduled run.
9. If still failing, preserve the branch and draft PR, mark the increment `in_progress` or `blocked`, and record the exact failure.

The agent must never make CI pass by deleting meaningful tests, weakening assertions or acceptance criteria, adding unjustified ignores/skips/sleeps/retries, disabling security or quality checks, hiding failures with unconditional success handling, or changing unrelated production behavior.

Classify CI failures as:

- attributable implementation defect;
- flaky or infrastructure failure;
- pre-existing failure;
- missing secret or external dependency;
- unclear and requiring owner review.

## Plan-update authority

The executing agent may:

- update factual implementation status;
- attach branch, PR, commit, test, and CI evidence;
- clarify non-material wording;
- add concrete downstream test, migration, observability, compatibility, or operational follow-up discovered during implementation;
- mark stale items completed when verified by source and tests.

The executing agent must not autonomously:

- weaken or remove acceptance criteria;
- redefine the product goal;
- introduce a breaking contract or destructive migration;
- reorder major phases without evidence;
- silently expand the selected increment;
- approve its own material architecture proposal;
- mark work completed without verification evidence.

## Agent-added follow-up format

```text
Agent-added follow-up
Discovered during: <increment/PR>
Reason: <concrete evidence>
Impact: <scope, dependency, risk>
Approval: not-required | owner-required
```

Material changes remain Proposed until the owner approves them.

## Daily report contract

Every scheduled execution must produce:

```markdown
# Omni Daily Implementation Report

## Status

COMPLETED | IN_PROGRESS | BLOCKED | NO_ELIGIBLE_PLAN

## Selected work

- Phase:
- Increment:
- Selection reason:
- Plan source:

## Delivery

- Branch:
- Draft PR:
- Commits:
- Main files changed:

## Verification

- Targeted tests:
- Integration/contract tests:
- Build and formatting:
- GitHub CI:
- CI attempts:

## Decisions

- Decisions made within existing architecture:
- Owner decisions required:

## Risks and blockers

- ...

## Roadmap changes

- Progress updates:
- Follow-ups added or proposed:
- Reason and impact:

## Recommended next action

- ...
```

## Stop conditions

Stop and request owner input instead of guessing when:

- two plans encode materially conflicting architecture decisions;
- correct phase order changes product or data-contract strategy;
- an acceptance criterion implies a destructive migration;
- source behavior contradicts a deliberate documented decision;
- a plan depends on unavailable infrastructure or credentials;
- preserving or dropping backward compatibility materially changes scope;
- plan consolidation would require implementing product code to determine the answer.
