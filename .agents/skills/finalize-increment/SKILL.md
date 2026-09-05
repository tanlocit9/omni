---
name: finalize-increment
description: Finalize an explicitly requested increment by recording approved checks, formatting only intended files, updating accurate roadmap evidence, and committing only owned changes.
---

# Finalize Increment

Use this skill only when the owner explicitly asks to finalize, verify, format, or
commit an increment. Invoking the skill is not by itself permission to run every
operation: the request must clearly authorize each verification action, its exact
commands and scope, and any commit. Never push or create a pull request unless the
owner requests that separately.

Follow the repository workflow and verification approval gate in
[`AGENTS.md`](../../../AGENTS.md). Use
[`manual-verification-handoff`](../manual-verification-handoff/SKILL.md) instead when
the owner will run the checks.

## Required Inputs

Before acting, obtain all of the following. Ask the owner for every missing or
ambiguous item:

1. increment ID;
2. explicit intended file scope;
3. complete required check list, with one supported `kind/name` pair and exact
   command for each check;
4. commit message;
5. explicit authorization for the exact test, build, lint, format, or equivalent
   commands to run; and
6. explicit authorization to create the commit when committing is requested.

Supported check kinds are `test`, `lint`, `build`, `format`, `integration`, and
`other`. Do not choose, remove, weaken, invent, or silently omit required checks.
The owner's invocation counts as verification authorization only when it clearly
names or approves the exact actions and scope. A request such as "finalize this"
does not authorize unspecified commands. A commit message alone does not authorize
a commit.

## Workflow

### 1. Establish the worktree boundary once

At the beginning, capture one repository-wide baseline from the workspace root:

```text
git status --short
git diff --name-status
git diff --cached --name-status
```

Classify every baseline change as:

- **owned**: already identified by the owner as part of this increment, or created
  by the agent for this increment within the intended scope;
- **unrelated**: pre-existing, out of scope, generated unexpectedly, or otherwise
  not clearly owned by this increment.

Cache this classification. Never stage, restore, format, alter, or commit unrelated
changes, including unrelated changes that were already staged. Re-read an existing
file before modifying it and preserve concurrent/user edits. Do not repeat broad
status or diff inspection until the commit boundary unless an external change is
suspected. Stop immediately and ask the owner if an unexpected change appears or
ownership cannot be determined.

### 2. Resolve checks without guessing

Inspect the relevant Nx configuration before proposing or running project commands:

```text
nx show project <project>
```

Use only targets actually defined for that project and invoke them only as:

```text
nx run <project>:<target>
```

Do not guess target names and do not substitute direct tools when a suitable target
exists. If no lint or format target exists, do not invent one or silently omit a
required check. Propose a justified alternative, including its exact intended-file
scope, and obtain explicit approval. If the check is not required, report it as not
applicable outside the required `check_result` manifest; never add a fake passing or
N/A run to satisfy the manifest.

Before execution, present the complete exact command list. Under the
[`AGENTS.md`](../../../AGENTS.md) gate, run test, build, lint, format, `nx affected`,
or equivalent tools only after the owner authorizes those exact commands and scope.
Run from the workspace root and only within the approved scope.

### 3. Record every approved check

Initialize the manifest once with the complete required list:

```text
python tools/check_result.py init --increment <ID> --require <kind/name> [--require <kind/name> ...]
```

Execute each approved command through the recorder, never beside or before it:

```text
python tools/check_result.py run --increment <ID> --kind <kind> --name <name> -- nx run <project>:<target>
```

For an approved underlying-tool exception where no suitable Nx target exists:

```text
python tools/check_result.py run --increment <ID> --kind <kind> --name <name> -- <approved-command> <approved-explicit-scope>
```

Then read only the compact result:

```text
python tools/check_result.py conclusion --increment <ID>
```

Do not inspect `.agent/check-results/<ID>/runs.jsonl`, summaries, or raw logs unless
the owner explicitly requests diagnosis; then inspect only the relevant failing
check. `.agent/` evidence is ignored local state and must never be staged or
committed.

Interpret the result conservatively:

- `PASS`: continue to scope review and minimum evidence updates;
- `FAIL`: stop and do not commit;
- `INCOMPLETE`: stop, report `verification_pending`, and do not commit;
- `INVALID`: stop, request corrected evidence, and do not commit.

### 4. Format only intended files

When format is authorized, prefer a defined project format target:

```text
python tools/check_result.py run --increment <ID> --kind format --name <name> -- nx run <project>:<format-target>
```

Use it only if its effective scope cannot touch unrelated changes. If no project
format target exists, use the workspace formatter only after approval and pass an
explicit list of intended files, for example:

```text
python tools/check_result.py run --increment <ID> --kind format --name <name> -- npx prettier --write <intended-file-1> <intended-file-2>
```

Record format through `check_result` whenever it is required. Immediately compare
format effects with the cached baseline and intended scope. Stop without staging or
committing if formatting changes any unrelated or out-of-scope file; do not restore
or rewrite that file without owner direction.

### 5. Update minimum roadmap evidence after PASS

Only after `PASS`, read and update the smallest relevant sections of the selected
phase document, [`implementation-increments.md`](../../../plans/roadmap/implementation-increments.md),
and [`execution-log.md`](../../../plans/roadmap/execution-log.md), following
[`automation-rules.md`](../../../plans/roadmap/automation-rules.md).

Record only evidence actually present. An agent-run local `PASS` is local check
evidence; it is not CI, manual, deployed, or live-environment evidence. Never claim
CI/manual/live evidence, acceptance completion, or full increment completion unless
that evidence and all applicable requirements are present.

### 6. Stage and commit only owned changes

At the commit boundary, compare the worktree with the cached baseline and ownership
classification. Stop on unexpected, ambiguous, generated, secret-bearing, or
out-of-scope changes. Stage each owned path explicitly; never use `git add .`,
`git add -A`, directory-wide staging, or broad pathspecs:

```text
git add -- <owned-file-1> <owned-file-2>
git diff --cached --name-status
git diff --cached
git diff --cached --check
```

If unrelated files were staged at baseline, do not unstage or alter them and do not
commit until the owner separates them or explicitly establishes a safe ownership
boundary. Inspect the complete staged diff and confirm it contains only owned,
intended files, no secrets or credentials, and no unintended generated artifacts.
Do not commit if `git diff --cached --check` fails.

Only with explicit commit authorization, create a normal commit using the supplied
message:

```text
git commit -m "<commit-message>"
```

Never amend. Never push, create a tag, open a pull request, or modify remote state
unless separately requested.

## Stop Conditions

Stop, preserve the worktree, and report the blocker when any of these occurs:

- required inputs or exact command authorization are missing;
- `check_result` concludes `FAIL`, `INCOMPLETE`, or `INVALID`;
- an Nx target is absent and no explicitly approved alternative exists;
- formatting touches a file outside the intended scope;
- unexpected or ambiguously owned changes appear;
- staged content includes unrelated files, secrets, credentials, or unintended
  generated artifacts; or
- the cached baseline cannot support a safe owned-only commit.

Do not convert a blocker into success by changing the manifest, acceptance criteria,
required checks, or evidence wording.

## Completion Report

Keep the final report concise:

```text
Increment: <ID>
Conclusion: <PASS|FAIL|INCOMPLETE|INVALID> (<local or owner-supplied evidence>)
Checks: <kind/name=result; ...>
Formatted: <explicit paths, not applicable, or not run>
Roadmap evidence: <updated paths or none>
Commit: <hash and message, not authorized, or not created>
Excluded/unrelated changes: <paths preserved or none>
Blockers: <none or concise reason>
```

Do not reproduce raw logs. State explicitly that no push or pull request was created
unless one was separately requested and completed.
