---
name: fix-python-ruff
description: Diagnose and fix Ruff lint or formatting failures in one or all Omni Python Nx projects, including repository-wide E501 line-length failures, using only declared Nx lint and lint-fix targets while preserving behavior and respecting the verification approval gate.
---

# Fix Python Ruff

Use this skill when the owner asks to diagnose, fix, or comprehensively scan Ruff
lint or formatting failures in Omni Python projects. It is especially appropriate
when one project reports errors but the owner suspects the same problem exists in
multiple files or projects.

## Scope

The normal Python Nx projects are `ingestor`, `analyzer`, `query-service`, and
`py-common`. Never assume this list is current: inspect declared Nx projects and
targets before running commands.

This skill handles:

- Ruff lint failures, including `E501` line-length errors;
- Ruff formatting failures;
- safe auto-fixes exposed through declared Nx `lint-fix` targets;
- small manual repairs for findings Ruff cannot auto-fix;
- a final comprehensive rerun across all applicable Python projects.

It does not authorize tests, builds, coverage, integration checks, dependency
changes, commits, or documentation/increment finalization.

## Required Boundaries

- Run commands from the workspace root.
- Establish worktree state once before editing and preserve unrelated owner changes.
- Use code-review-graph before manually inspecting unfamiliar implementations.
- Inspect unfamiliar projects with `nx show project <project>`.
- Use only targets declared by each project's `project.json`.
- Prefer `nx run <project>:lint-fix` for one project.
- For a repository-wide scan, use `nx run-many -t lint-fix --all` only after confirming
  the target exists on the applicable Python projects.
- Lint and format commands remain subject to the verification approval gate in
  `AGENTS.md`. The current request must explicitly ask to run/fix lint or approve a
  concrete command list.
- Do not edit generated files under `libs/contracts/gen`.
- Do not change runtime behavior merely to silence a style finding.
- Do not rewrite intentional Python imports from `app`; Nx Python targets run with
  the project as their working directory.
- Do not run a bare Ruff command when a suitable Nx target exists.

## Workflow

### 1. Resolve requested scope

Classify the request as either:

- **single project**: the owner names a project or supplies output from one project;
- **repository-wide**: the owner asks for a comprehensive scan, says many files are
  affected, or explicitly asks to check all Python projects.

If the requested project is ambiguous and repository-wide execution was not
requested, ask the owner to select the project. Do not infer it solely from the open
editor.

### 2. Confirm Nx targets

For each applicable project, inspect its Nx configuration with:

```text
nx show project <project>
```

Confirm the project declares `lint-fix`. Also record `lint` if present for an optional
non-mutating confirmation run. Do not invent missing targets or substitute direct
Ruff commands without explicit owner approval.

### 3. Run the narrowest authorized fix

For a single project, run:

```text
nx run <project>:lint-fix
```

For an explicitly requested comprehensive scan, run:

```text
nx run-many -t lint-fix --all
```

Treat exit code zero as successful command evidence. If the command fails, inspect
only the reported findings and affected source files.

### 4. Repair non-auto-fixable findings

Before modifying an existing file, read the relevant lines again. Apply the smallest
behavior-preserving edit.

For `E501` in comments:

- wrap prose across multiple comment lines;
- preserve the TODO identifier and intent;
- prefer a short reference such as `See the TD-009 technical-debt record` when the
  full repository path itself exceeds the line limit;
- do not add `# noqa: E501` merely to retain an overlong comment;
- keep every resulting line at or below 88 characters.

For `E501` in code:

- use normal Python parenthesized wrapping;
- split arguments, conditions, comprehensions, or string construction naturally;
- preserve evaluation order, exceptions, logging parameters, and return values;
- do not change public APIs, Kafka contracts, storage paths, or configuration merely
  for formatting.

For other Ruff rules, prefer the rule's semantics-preserving correction. If a fix may
alter behavior, stop and explain the risk instead of applying it automatically.

### 5. Repeat until clean

After manual repairs, rerun the exact same authorized Nx command. Continue only for
new findings produced by that scope. Stop when:

- the command exits successfully; or
- a remaining finding requires a behavioral/product decision.

Do not claim a comprehensive pass from a single-project result. A repository-wide
claim requires the repository-wide target to finish successfully.

### 6. Analyze changes

Run code-review-graph change detection after edits, listing only files changed during
this task. This is static analysis, not lint/test/build evidence.

Because style-only edits can appear inside important functions, distinguish graph
risk from actual semantic impact. Explicitly state when changes only wrap comments or
format code without changing behavior.

### 7. Report results

Report:

```text
Scope: <project or all Python lint-fix projects>
Command: <exact Nx command>
Result: <passed or failed>
Files manually repaired: <paths and findings>
Auto-formatted files: <known count or "reported by Nx/Ruff">
Behavior changes: none, or an explicit description
Tests/builds/coverage: not run unless separately authorized
```

Do not describe lint success as test, build, runtime, deployment, or CI success.

## Failure Handling

- If `nx show project` fails, stop and report that project discovery is incomplete.
- If a target is absent, report the missing target and do not bypass Nx automatically.
- If `lint-fix` partially modifies files and then fails, preserve those modifications,
  inspect the remaining findings, and continue with minimal manual repairs.
- If unrelated pre-existing worktree changes exist, do not revert, stage, or overwrite
  them.
- If command output is unavailable, do not infer a pass; report the result as
  inconclusive.
