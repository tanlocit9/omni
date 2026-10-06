# AGENTS.md

Canonical repository-wide instructions for coding agents. Architecture and business
rules belong in [`docs`](docs); do not duplicate them in agent files.

## Required workflow

- Use `code-review-graph` before manually scanning unfamiliar implementations.
- Every feature plan must include a concrete cross-service blast-radius assessment.
  Start with graph impact analysis, then reconcile its result against the canonical
  architecture, flow, Kafka, data-lake, database, deployment, service, and ownership
  documents indexed by [`docs/README.md`](docs/README.md). Graph hops are not proof
  that Kafka consumers, Python workers, shared libraries, generated contracts,
  storage writers/readers, migrations, configuration, tests, or operations are
  unaffected; list each applicable service/module explicitly with impact or a
  documented no-impact reason.
- Run graph impact analysis before changing shared contracts, public APIs, Kafka
  messages, storage paths, dataset ownership, or shared configuration.
- Run graph change detection after edits; this is analysis, not a build/test/lint/
  format verification command.
- Use [`docs/development/001-where-to-change.md`](docs/development/001-where-to-change.md)
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
- Always prefer using Nx targets for dependency installation, environment sync, and service execution in Python projects:
  - After editing `pyproject.toml` or dependency group, run:
    - `nx run <project>:install` to install dependencies and set up the environment
    - `nx run <project>:lock` to update and synchronize the lock file
  - This ensures reproducible environments across development, CI/CD, and all contributors.
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

## Blast-radius and coverage verification

Increment verification is behavior-based, not commit-based:

- Run code-review-graph impact analysis for the increment's implemented source files, then reconcile the bounded result against canonical cross-service documentation. Explicitly cover Platform, Analyzer, Ingestor, Query Service, Console, shared contracts/libraries, persistence, configuration, tests, and operations with impacts or concrete no-impact reasons.
- Build an impact-to-test matrix mapping every changed or directly impacted production file/safety-critical symbol and acceptance criterion to executed tests.
- Require attributable coverage for critical components, defaulting to at least 80% line and 80% branch coverage unless the plan declares a stricter threshold. Aggregate project coverage cannot hide uncovered impacted code.
- Explicitly exercise applicable success, failure, retry, compatibility, concurrency, transaction, authorization, READY-last, lineage, and data-loss-prevention branches.
- A branch, commit, pull request, merge, broad suite pass, or CI result is traceability evidence, not proof of behavioral coverage. CI is required only when the increment or delivery workflow explicitly declares it; it never replaces impact and coverage evidence.
- If coverage instrumentation is unavailable, require executed focused tests plus an explicit feature/source/test matrix, record the limitation, and do not infer a numeric coverage pass.

## Manual verification result handoff

When the user will run verification, ask them to record every required check with
[`tools/check_result.py`](tools/check_result.py) and confirm when the conclusion is
ready. Do not run checks, inspect raw logs, or infer success from source code.

Read the result only with
`python tools/check_result.py conclusion --increment <ID>`. A `PASS` is
owner-supplied evidence, `INCOMPLETE` remains `verification_pending`, and `FAIL`
must not be recorded as completed. Inspect raw logs only when the user explicitly
requests diagnosis. Read only the minimum roadmap sections needed for the update.

When verifying an increment or recording an owner-reviewed passing gate, use the
`verify-increment` skill. Owner attestation is allowed only after the complete gate passes,
blast-radius analysis is reconciled across services, and attributable code coverage
proves the increment's changed and directly impacted features, acceptance criteria,
and safety-critical paths were exercised. Require an explicit impact/source/test
coverage matrix and include an available coverage target/report in the recorded gate;
broad suite success or test-file presence alone is insufficient.
The attestation must be bound to the exact summary and required-check manifest through
`tools/check_result.py attest`. It records `owner_verified`; it never substitutes for
missing impact reconciliation, coverage, checks, deployment, provider, runtime,
dependency, documentation, or acceptance evidence and cannot alone mark an increment
completed. Commit, PR, merge, and CI evidence remain optional traceability unless the
increment explicitly requires those delivery gates.

## Contract and data guardrails

- Canonical migrated service/Kafka schemas live in
  [`libs/contracts/proto`](libs/contracts/proto); never hand-edit generated output
  in [`libs/contracts/gen`](libs/contracts/gen).
- Review Kafka producers, consumers, shared schemas/configuration, tests, and
  [`docs/data/001-kafka-contracts.md`](docs/data/001-kafka-contracts.md) together.
- Kafka business messages carry logical dataset references, never physical object
  paths. Storage builders use [`configs/shared/s3-paths.yaml`](configs/shared/s3-paths.yaml).
- Dataset producers write and validate Parquet only. `SYNC_METADATA` is the sole
  writer of the canonical `_metadata/metadata.json` discovery document.
- Dataset dependencies use global metadata and exact `dataVersion` lineage; cron
  gaps are not dependency guarantees.
- Reusable Python behavior belongs in [`libs/py-common`](libs/py-common), canonical
  language-neutral contracts in [`libs/contracts`](libs/contracts), and
  application-specific behavior in its owner.

See canonical details in:

- [`docs/architecture/001-system-overview.md`](docs/architecture/001-system-overview.md)
- [`docs/data/001-kafka-contracts.md`](docs/data/001-kafka-contracts.md)
- [`docs/data/002-data-lake.md`](docs/data/002-data-lake.md)
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
[`docs/governance/001-implementation-plan-standard.md`](docs/governance/001-implementation-plan-standard.md).
When architecture, contracts, workflow, or tooling changes, synchronize applicable
canonical docs and agent guidance. Keep agent files concise and link to canonical
sources rather than copying architecture prose.

Before finalizing any increment, agents must run the documentation-synchronization
workflow even when source implementation is already complete:

1. load the `update-implementation-plans` skill;
2. review the canonical increment registry, numbered implementation plan, root release
   notes, and affected architecture, ADR, flow, data, deployment, development, service,
   index, and repository-guidance documents;
3. update every applicable document in the same change, or record an explicit
   no-update reason for each reviewed documentation area;
4. run `code-review-graph` change detection after documentation edits; and
5. use the `verify-document-consistency` skill for a static consistency audit before
   claiming documentation synchronization or increment completion.

Documentation synchronization and static consistency analysis do not authorize or
replace build, test, lint, format, CI, deployment, or runtime verification. A missing,
inconclusive, or failed documentation audit keeps the increment unresolved and must
not be reported as completed.

For documentation-only changes, use static link/path inspection by default. Any
executable documentation checker remains subject to the verification approval gate.
