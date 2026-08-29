# AGENTS.md

Canonical repository-wide instructions for coding agents. Architecture and business
rules belong in [`docs`](docs); do not duplicate them in agent files.

## Required workflow

- Use `code-review-graph` before manually scanning unfamiliar implementations.
- Run graph impact analysis before changing shared contracts, public APIs, Kafka
  messages, storage paths, dataset ownership, or shared configuration.
- Run graph change detection after edits; this is analysis, not a build/test/lint/
  format verification command.
- Use [`docs/development/where-to-change.md`](docs/development/where-to-change.md)
  for ownership and [`docs/README.md`](docs/README.md) for canonical documentation.

## Worktree inspection boundary

- Establish worktree state once at the beginning of a task when file edits are
  expected, then cache that baseline for the current task and track files touched
  by the agent in session state.
- Do not repeat repository-wide Git status or diff commands unless an external
  change is detected, the requested task depends on Git state, or a commit, push,
  or pull-request boundary is reached.
- Before modifying an existing file, re-read that file and preserve changes not
  made by the agent.
- Do not inspect branch history, remotes, pull requests, tags, commit identity, or
  CI for ordinary local tasks unless relevant to the user's request.
- Do not create branches, commits, tags, pushes, or pull requests unless the user
  explicitly requests them or explicitly invokes roadmap automation.
- Persistent agent state must use an ignored local cache and is advisory; Git
  remains authoritative at delivery boundaries.

## Nx command boundary

- Run commands from the workspace root.
- Inspect unfamiliar projects with `nx show project <project>` and use only targets
  defined by that project's `project.json`.
- Invoke project operations as `nx run <project>:<target>`. Use an underlying tool
  only when no suitable target exists, and state that exception.
- [`apps/core`](apps/core) is the Nx project `platform`.
- Python Nx targets run with the project as cwd; service imports from `app` are
  intentional.

## Verification approval gate

Do not execute build, test, lint, format, `nx affected`, or equivalent underlying
verification tools unless either:

1. the user's current prompt explicitly requests that verification; or
2. the user approves a concrete proposed command list.

Absent approval, inspect code and configuration statically, report recommended
commands as **not run**, and do not treat missing execution evidence as a pass.
This gate also applies to checks otherwise required by plans or documentation.
After approval, use the matching Nx targets and run only the approved scope.

## Manual verification result handoff

When the user will run verification, ask them to record every required check with
[`tools/check_result.py`](tools/check_result.py) and confirm when the conclusion is
ready. Do not run checks, inspect raw logs, or infer success from source code.

Read the result only with
`python tools/check_result.py conclusion --increment <ID>`. A `PASS` is
owner-supplied evidence, `INCOMPLETE` remains `verification_pending`, and `FAIL`
must not be recorded as completed. Inspect raw logs only when the user explicitly
requests diagnosis. Read only the minimum roadmap sections needed for the update.

## Contract and data guardrails

- Canonical migrated service/Kafka schemas live in
  [`libs/contracts/proto`](libs/contracts/proto); never hand-edit generated output
  in [`libs/contracts/gen`](libs/contracts/gen).
- Review Kafka producers, consumers, shared schemas/configuration, tests, and
  [`docs/data/kafka-contracts.md`](docs/data/kafka-contracts.md) together.
- Kafka business messages carry logical dataset references, never physical object
  paths. Storage builders use [`configs/shared/s3-paths.yaml`](configs/shared/s3-paths.yaml).
- Dataset writers publish validated data, then an immutable version manifest, then
  replace `READY.json` last. Failures preserve the previous READY pointer.
- Dataset dependencies use manifests and `dataVersion` lineage; cron gaps are not
  dependency guarantees.
- Reusable Python behavior belongs in [`libs/py-common`](libs/py-common), canonical
  language-neutral contracts in [`libs/contracts`](libs/contracts), and
  application-specific behavior in its owner.

See canonical details in:

- [`docs/architecture/system-overview.md`](docs/architecture/system-overview.md)
- [`docs/data/kafka-contracts.md`](docs/data/kafka-contracts.md)
- [`docs/data/data-lake.md`](docs/data/data-lake.md)
- [`docs/flows`](docs/flows)

## Repository boundaries

- Do not modify [`externals`](externals) unless explicitly working on that submodule.
- Do not commit secrets or hard-code provider credentials/regions in handlers.
- Do not bypass existing ports/helpers to access Kafka or infrastructure.
- Do not start long-running watchers unless explicitly requested.
- For PowerShell-only syntax, submit the full command as
  `powershell -NoProfile -Command "..."`; do not wrap plain Nx commands.

## Plans and documentation

Implementation plans follow
[`docs/IMPLEMENTATION_PLAN_STANDARD.md`](docs/IMPLEMENTATION_PLAN_STANDARD.md).
When architecture, contracts, workflow, or tooling changes, synchronize applicable
canonical docs and agent guidance. Keep agent files concise and link to canonical
sources rather than copying architecture prose.

For documentation-only changes, use static link/path inspection by default. Any
executable documentation checker remains subject to the verification approval gate.
