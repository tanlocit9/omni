---
name: verify-document-consistency
description: Statically verify Omni documentation and roadmap consistency across IDs, statuses, dependencies, required plan sections, links, contract-impact declarations, cross-document claims, and evidence wording without running verification commands or editing documents unless explicitly requested.
---

# Verify Document Consistency

Use this skill when asked to review, audit, or verify consistency across Omni
Markdown documentation, implementation plans, roadmap records, indexes, canonical
architecture/data/flow documents, or repository guidance.

This is a static, read-only review by default. Invoking this skill does not authorize
editing files or running build, test, lint, format, migration, deployment, provider,
network, or other executable verification commands.

## Governing Sources

Apply these sources in precedence order:

1. [`AGENTS.md`](../../../AGENTS.md) for repository workflow and verification gates;
2. [`docs/governance/001-implementation-plan-standard.md`](../../../docs/governance/001-implementation-plan-standard.md) for required plan sections and definition of done;
3. [`plans/roadmap/README.md`](../../../plans/roadmap/README.md) for canonical phase scheduling;
4. [`plans/roadmap/implementation-increments.md`](../../../plans/roadmap/implementation-increments.md) for canonical increment metadata;
5. [`plans/roadmap/automation-rules.md`](../../../plans/roadmap/automation-rules.md) for statuses, readiness, evidence, and authority;
6. [`plans/roadmap/cross-phase-rules.md`](../../../plans/roadmap/cross-phase-rules.md) for cross-phase gates;
7. canonical architecture, data, flow, deployment, development, and service documents;
8. supporting plans and historical records.

Current verified source may correct a factual baseline, but it must not silently
replace an approved product or architecture decision. Supporting plans must not
become competing schedules.

## Default Boundaries

- Read only the minimum files needed to establish each claim.
- Use code-review-graph before broad manual inspection of unfamiliar source.
- Use graph impact analysis when the documents describe a changed shared contract,
  public API, Kafka message, storage path, dataset owner, or shared configuration.
- Do not edit a document, rename a file, create a repair plan, or change roadmap
  metadata unless the owner explicitly requests modification.
- Do not execute build, test, lint, format, Nx, migration, link-checker, deployment,
  provider, or network commands unless the owner separately approves the exact
  commands and scope under [`AGENTS.md`](../../../AGENTS.md).
- Static reads, searches, path inspection, and graph analysis are not executable
  verification and must not be reported as tests passing.
- Never strengthen evidence from local to CI, merged, deployed, production, or live.
- Never weaken acceptance criteria to remove an inconsistency.

## Required Workflow

### 1. Establish scope and document roles

Identify the files or subject requested. Classify every relevant document as one of:

- canonical roadmap index;
- canonical increment registry;
- canonical phase file;
- canonical architecture/data/flow/deployment/development documentation;
- supporting implementation plan;
- progress or execution log;
- technical-debt or historical record;
- navigation index or repository guidance.

If no explicit scope is supplied, begin with the named document and follow only its
direct canonical links and claims. Expand repository-wide only when needed to test a
cross-document assertion.

### 2. Establish the factual baseline

Use the narrowest reliable evidence:

1. canonical roadmap metadata for scheduling and status;
2. execution logs, PR/commit/CI fields, and recorded check conclusions for evidence;
3. targeted source and configuration inspection for implementation-presence claims;
4. tests as evidence of coverage only when their execution result is recorded;
5. technical-debt records for deferred or superseded scope.

Distinguish these states explicitly:

- planned;
- source present;
- locally verified;
- CI verified;
- merged;
- deployed;
- production or provider verified.

Absence of contradictory evidence is not a pass.

### 3. Check roadmap identities and metadata

For every referenced increment ID:

- verify the ID is unique in the active canonical registry;
- verify the title and scope agree between registry and phase file;
- verify status uses one allowed value: `pending`, `ready`, `in_progress`, `blocked`,
  `verification_pending`, `completed`, or `superseded`;
- verify priority, execution mode, owner-decision flag, owned modules, PR, and verified
  commit do not conflict across canonical surfaces;
- verify historical reuse of an ID does not assign it a second active meaning;
- verify `completed` has acceptance, tests/checks, CI where applicable, documentation,
  PR, and verified commit evidence;
- verify `ready` satisfies every readiness rule;
- verify `in_progress` has an owning branch or draft PR when the roadmap requires it;
- verify `blocked` names the unresolved access, decision, or contradiction;
- verify `superseded` identifies the replacement or recorded reason.

Treat a supporting-plan status that differs from the canonical registry as an error
unless it is clearly labelled historical and points to the canonical owner.

### 4. Check dependency consistency

Build the active increment dependency graph from canonical metadata and verify:

- every `depends_on` and `blocks` reference names an existing increment;
- reciprocal edges agree where both directions are recorded;
- no active dependency cycle exists;
- dependency wording does not embed status text as part of an ID;
- a dependent is not `ready` or `completed` while a required dependency is incomplete;
- manual, blocked, approval-required, and superseded work is not described as
  autonomously selectable;
- phase summaries and selection guidance agree with increment-level edges;
- removed or deferred increments do not remain active blockers unless explicitly
  intended and approved.

Preserve historical dependency statements in execution logs as history; do not treat
an old log row as current scheduling metadata.

### 5. Check mandatory implementation-plan sections

For each active or touched implementation plan, verify the exact presence of:

1. `Goal`
2. `Outcome`
3. `Dataset Outputs`
4. `Metadata Outputs`
5. `Algorithm Feature Outputs`
6. `Algorithms Unlocked`
7. `Contract Impact`
8. `Repository Guidance Updates`
9. `Verification`
10. `Acceptance Criteria`

When an area does not apply, require the explicit no-impact wording prescribed by
[`docs/governance/001-implementation-plan-standard.md`](../../../docs/governance/001-implementation-plan-standard.md).

Verify acceptance criteria are objective, testable, scoped, and not weaker than the
plan's stated goal, migration, compatibility, security, or operational requirements.

### 6. Check contract-impact declarations

Every active plan must explicitly decide whether it changes:

- Kafka or service-to-service protobuf;
- object-storage JSON manifests;
- storage paths or dataset ownership;
- public Java or Python APIs;
- configuration or environment contracts.

For every declared change, verify the plan identifies applicable producers,
consumers, persistence, rollout/compatibility, tests, and canonical documentation.
Also verify that it:

- never directs hand edits to [`libs/contracts/gen`](../../../libs/contracts/gen);
- keeps canonical transport schemas under [`libs/contracts/proto`](../../../libs/contracts/proto);
- keeps physical S3/R2 paths out of Kafka business messages;
- preserves READY-last publication and the previous READY pointer on failure;
- preserves exact `dataVersion` lineage where dependencies require it;
- places reusable Python behavior in [`libs/py-common`](../../../libs/py-common);
- does not claim an unchanged contract while specifying incompatible fields,
  statuses, persistence, configuration, or ownership elsewhere.

A plan may declare an internal persisted DTO as changed while public APIs and Kafka
remain unchanged, but that compatibility boundary must be versioned and tested.

### 7. Check cross-document claims

Compare repeated material claims across the canonical roadmap, increment registry,
phase file, supporting plan, execution log, technical debt, indexes, and affected
canonical docs. Check at least:

- phase name, purpose, and active scope;
- increment title, status, dependency, priority, and ownership;
- current versus deferred or superseded work;
- implementation-presence and runtime-ownership claims;
- test counts, dates, command names, PRs, commits, and CI state;
- migration numbers and active schema ownership;
- Kafka, database, storage, API, configuration, and deployment impact;
- next eligible action and automation eligibility;
- navigation classifications and canonical-owner links.

Do not flag an execution log merely because it records an older state. Flag it only
when it presents historical evidence as current, conflicts internally, or has been
rewritten to overstate what occurred.

### 8. Check links and paths statically

For every directly relevant Markdown link or repository path:

- resolve relative links from the containing file;
- verify the target file or directory exists;
- verify anchors when practical through static heading inspection;
- detect stale filenames after moves or phase renames;
- distinguish source links from illustrative placeholders;
- check that indexes include newly canonical documents and do not classify historical
  documents as active schedules.

Use file listing, search, and reads. Do not run an executable link checker without
explicit command approval.

### 9. Check evidence wording

Flag wording that:

- says `completed`, `verified`, `passed`, `working`, `production-ready`, `deployed`,
  or `live` without matching evidence;
- treats source presence as verification;
- treats static graph analysis as build/test/lint/format evidence;
- treats mocked HTTP as live provider evidence;
- treats local checks as CI or merge evidence;
- carries an old test count/date forward as a current run;
- omits required checks or describes them as not applicable without justification;
- converts missing, failed, unknown, or not-run evidence into success;
- claims no contract impact while describing a changed persisted or transport shape.

Prefer precise wording such as `planned`, `source present`, `locally verified`,
`verification_pending`, `not run`, `blocked`, and `historical evidence`.

### 10. Classify findings

Use these severities:

- **Critical**: destructive or security-sensitive instruction, false completion/live
  claim, broken canonical ownership, duplicate active increment identity, or a plan
  that would bypass contract/data safety rules.
- **High**: contradictory active status/dependency, dependency cycle, unsupported
  readiness/completion, missing mandatory contract impact, or active canonical link to
  the wrong plan.
- **Medium**: missing mandatory section, stale implementation claim, incomplete
  producer/consumer or migration documentation, broken relevant link/path, or evidence
  wording that can mislead scheduling.
- **Low**: non-material terminology, navigation, formatting, or explanatory drift that
  does not alter execution or evidence meaning.

Do not manufacture a finding to fill every severity.

### 11. Report without mutation

Default report format:

```markdown
# Document Consistency Review

## Scope

- Documents inspected: ...
- Canonical owners used: ...
- Static limitations: ...

## Conclusion

CONSISTENT | INCONSISTENT | INCOMPLETE

## Findings

### [Severity] Short title

- Location: path:line
- Conflicting source: path:line
- Issue: precise contradiction or omission
- Impact: scheduling, contract, evidence, navigation, or implementation risk
- Recommended correction: minimal non-destructive action

## Checks by category

- Roadmap IDs/status/dependencies: PASS | FAIL | INCOMPLETE
- Mandatory plan sections: PASS | FAIL | INCOMPLETE
- Cross-document claims: PASS | FAIL | INCOMPLETE
- Links/paths: PASS | FAIL | INCOMPLETE
- Contract impact: PASS | FAIL | INCOMPLETE
- Evidence wording: PASS | FAIL | INCOMPLETE

## Verification boundary

- Static inspection performed: ...
- Executable checks: NOT RUN
- Documents edited: none
```

Use `INCOMPLETE` when required canonical context is missing, unreadable, ambiguous,
or depends on unavailable external evidence. A category passes only when every
in-scope item was checked and no inconsistency was found.

## Optional Repair Mode

Enter repair mode only when the owner explicitly asks to edit documents. Before
editing:

1. identify the canonical owner of each fact;
2. establish the worktree boundary required by [`AGENTS.md`](../../../AGENTS.md);
3. propose the exact files and corrections;
4. stop for owner input on material product, architecture, contract, phase-order, or
   evidence conflicts;
5. preserve historical evidence and unrelated user changes;
6. update all applicable roadmap surfaces atomically;
7. run only static post-edit inspection unless exact executable commands are
   separately approved;
8. run code-review-graph change detection after edits when available.

Never silently change statuses, dependencies, acceptance criteria, product scope,
contract strategy, historical logs, or evidence strength.

## Stop Conditions

Stop and request owner input when:

- canonical sources materially disagree and precedence does not resolve the conflict;
- fixing the issue would reorder phases or redefine a product goal;
- an increment ID has two plausible active meanings;
- the correct status depends on unprovided CI, PR, deployment, production, or provider
  evidence;
- a repair would introduce a breaking contract, destructive migration, or weakened
  acceptance criterion;
- a path rename would make historical evidence ambiguous;
- source inspection reveals unexpected changes whose ownership is unclear.

Report the contradiction, affected files, impact, and exact decision needed. Do not
guess through a stop condition.
