---
name: manual-verification-handoff
description: Use owner-run lint, test, build, or other verification results to update Omni roadmap and documentation with minimal model tokens. Records commands through tools/check_result.py, reads only its compact conclusion, and never treats missing or inconclusive evidence as a pass.
---

# Manual Verification Handoff

Use this skill when the owner wants to run verification manually and asks the agent
to consume the result, conclude an increment check, or update roadmap documentation.

## Boundaries

- Follow the verification approval gate in [`AGENTS.md`](../../../AGENTS.md).
- Do not run lint, test, build, format, or equivalent checks unless the owner's
  current prompt explicitly authorizes them.
- Do not inspect raw logs or implementation code merely to determine whether checks
  passed.
- Treat results as owner-supplied evidence, never agent-verified evidence.
- Never infer success from source presence, missing failure text, or an unrecognized
  log.

## Required Workflow

### 1. Identify the verification gate

Obtain the increment ID and complete list of required checks. If either is missing,
ask the owner for it. Required checks use `kind/name`, where supported kinds are
`test`, `lint`, `build`, `format`, `integration`, and `other`.

Ask the owner to initialize the local manifest when needed:

```text
python tools/check_result.py init --increment <ID> --require <kind/name> [--require <kind/name> ...]
```

Do not choose, remove, or weaken required checks merely to obtain a pass.

### 2. Ask the owner to record manual checks

Prefer wrapper mode because its exit code is authoritative:

```text
python tools/check_result.py run --increment <ID> --kind <kind> --name <name> -- nx run <project>:<target>
```

For a command already run, ask the owner to place its log under
`.agent/check-results/<ID>/logs/` and import it. Include `--exit-code` whenever the
original exit code is known:

```text
python tools/check_result.py import-log --increment <ID> --kind <kind> --name <name> --format <nx|pytest|eslint|generic> --exit-code <code> --log <path>
```

Log parsing without an exit code is fallback evidence and may remain `unknown`.

### 3. Wait for owner confirmation

Ask the owner to confirm that all checks have been recorded and the compact result is
ready. Do not repeatedly poll files, inspect logs, or perform independent phase
checking while waiting.

### 4. Read only the compact conclusion

Run exactly:

```text
python tools/check_result.py conclusion --increment <ID>
```

Normally consume only its single output line:

- `PASS`: every required check passed.
- `FAIL`: at least one required check failed.
- `INCOMPLETE`: a required check is missing or unknown.
- `INVALID`: verification metadata or artifacts are invalid.

Do not open `runs.jsonl`, `summary.json`, or raw logs unless the owner explicitly asks
for diagnosis. If diagnosis is requested, read only the named failing check's log.

### 5. Apply the result conservatively

- `PASS` may be recorded as owner-supplied verification evidence. Mark work
  `completed` only if all other acceptance, CI, documentation, and roadmap completion
  requirements are also satisfied.
- `FAIL` must not be recorded as completed. Preserve `in_progress` or use `blocked`
  only when the documented blocker semantics apply.
- `INCOMPLETE` maps to `verification_pending`.
- `INVALID` requires corrected evidence and must not change status to completed.

### 6. Update only minimum documentation

Read only the relevant entries in:

- the selected phase document;
- [`plans/roadmap/implementation-increments.md`](../../../plans/roadmap/implementation-increments.md);
- [`plans/roadmap/execution-log.md`](../../../plans/roadmap/execution-log.md);
- directly affected supporting documents or dependent statuses.

Follow [`plans/roadmap/automation-rules.md`](../../../plans/roadmap/automation-rules.md)
and do not weaken acceptance criteria or overstate evidence.

Record compact evidence such as:

```text
Owner-supplied verification: PASS for <ID>; required=<n>, pass=<n>, fail=0,
unknown=0, missing=0. Result read through tools/check_result.py.
```

Do not copy raw command output into roadmap documents.

## Tool Verification

The owner can manually test the verification recorder with:

```text
nx run omni:verify
```

Do not invoke this target unless explicitly requested or approved.

## Completion Report

Report only:

1. the compact conclusion and that it is owner-supplied;
2. roadmap or documentation files updated;
3. resulting status;
4. missing evidence or blockers, if any.

Keep the response concise and do not reproduce raw logs.
