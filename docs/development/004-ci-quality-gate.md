# CI Quality Gate

Status: focused local tests passed; full workflow and GitHub Actions evidence pending.

The workflows are [ci.yml](../../.github/workflows/ci.yml) and
[python-ci.yml](../../.github/workflows/python-ci.yml). CI runs on pushes to `main`
and `multi-phase`, PRs targeting `main`, and manual dispatch. Pushing only to `WIP`
does not trigger it. Manual dispatch requires a previous full commit SHA and selects
all stacks. New pushes cancel older runs for the same PR/ref. Runner jobs have
explicit timeouts, including the job inside the reusable Python workflow.

## Selection and cross-service blast radius

The affected-detection job sets up Python 3.14.5 and runs `omni:tools-test`,
`omni:ci-policy-test` and `omni:format-test` before selecting stack jobs. The
uncached Python tooling target uses standard-library unittest discovery through
`tools/run-python.cjs`. Windows requires the workspace `.venv/Scripts/python.exe`
to bypass pyenv batch shims; other platforms prefer `.venv/bin/python` and fall
back to `python3` from the CI setup. No service dependencies are installed by this
launcher. Local `omni:verify` uses the same launcher and records
all three targets as required checks through `tools/check_result.py`, alongside
its existing service and integration checks. The Windows console flags are
platform-specific; the Ubuntu CI runner executes the portable runner tests.

The Python launcher uses `shell: false` and `windowsHide: true`. Contract generation,
generation determinism, baseline Git queries and formatter-test Git helpers also
request hidden Windows children. These are per-launch settings, not a guarantee
that an outer terminal or third-party subprocess will stay hidden. The 2026-10-19
launcher review passed its owner-approved local gate (`WINDOWS-TOOLING-20261019`):
changed-file format, 14 tooling tests via the direct Python launcher, 7 CI-policy
tests and 9 formatter tests. Contract generation/breaking commands and full verify
orchestration were not executed; the corresponding flags are statically inspected.
Controlled owner observation reproduced the flash with Nx Cloud enabled and found
no flash with it disabled for the same lightweight target and runner settings.
The recorder now supplies Windows-only child-environment defaults:
`NX_NO_CLOUD=true`, `NX_NATIVE_COMMAND_RUNNER=false` and `NX_TUI=false`. Explicit
caller values take precedence. Parent environment, repo cloud settings and
non-Windows CI remain unchanged. Three regression tests exercise defaults,
override preservation and unchanged non-Windows environment. This observation
does not establish that every third-party tool or launch path is flash-free.

The local targets passed on 2026-10-18: 11 tooling tests, 7 CI-policy tests and
9 formatter tests (`TOOLING-WORKFLOW-20261018`). This verifies the individual
targets locally; the updated GitHub Actions job and full `omni:verify` orchestration
have not been executed here.

Java formatting is available locally through `omni:format` / `platform:format`;
the existing `omni:format-test` CI step also covers Java routing regression cases.
This addition does not enable whole-repository Java format enforcement or alter
the root Prettier lint command. See [formatting scope](001-where-to-change.md#formatting-scope).

[ci-policy.cjs](../../tools/ci-policy.cjs) supplements Nx affected selection.
The graph currently does not index workflow/configuration files; its empty radius
is not evidence of isolation. Reconciliation uses the canonical
[system boundaries](../architecture/001-system-overview.md),
[ownership](001-where-to-change.md), [Kafka contracts](../data/001-kafka-contracts.md),
[data lake](../data/002-data-lake.md), [database](../data/003-database.md),
[job execution](../flows/001-job-execution.md) and
[deployment boundary](../deployment/002-cloudflare-low-cost-deployment.md).

- Any backend application change (`apps/core`, `apps/analyzer`, `apps/ingestor`,
  `apps/query-service`) selects all stacks. Active JSON payloads and HTTP/storage
  boundaries can live in service-owned code, without an inferred Nx edge.
- Changes under `libs`, `configs`, `database`, `tools`, `.github`, root Compose
  files, or listed root dependency/Nx/TypeScript/format configuration select all
  stacks. This covers shared protobuf, Python helpers, topics, paths, migrations,
  environment settings, lockfiles and CI tooling.
- Nx reporting the root `omni` project also selects all stacks. Otherwise its
  selected projects are preserved, including Console-only selection. Documentation
  does not independently force every stack; Nx may still select them.
- This intentionally favors extra checks over missed consumers. Narrowing the
  policy requires evidence for the cross-service boundary being excluded.

Platform receives its existing build/test lifecycle; Analyzer, Ingestor, Query
Service and py-common receive lint/test/build; Console receives lint/typecheck/
test/build; contracts receive format/lint/breaking/generation/test checks.
Persistence and configuration changes select all stacks but do not independently
prove migration rollout, live storage compatibility or deployment safety.
No production service, Kafka schema, dataset owner/path, manifest, database model,
runtime setting or operation is modified by this CI change.

## Immutable contract baseline

PRs use `pull_request.base.sha`; pushes use `before`; dispatch uses its explicit
`baseline` input. The selected SHA must exist, be nonzero and differ from checkout
HEAD. It is independent of the Nx last-successful-run diff base.
[contracts-breaking.cjs](../../tools/contracts-breaking.cjs) compares against that
exact SHA, accepting the canonical or historical module location. Missing baseline
or schema fails rather than silently running `buf build`. There is no implicit
bootstrap exemption. Branch creation with a zero `before` SHA requires manual
dispatch using an existing baseline. No compatibility pass is available when no
previous contract module exists.

## Python dependencies and integration

Each Python project declares an uncached `sync-ci` target using `uv sync --locked`
with its package name. `UV_LOCKED=true` also applies to subsequent workflow steps,
so implicit uv resolution must not update the lockfile. `--frozen` alone would skip
the manifest freshness check; it is insufficient for detecting lockfile drift.
Development sync targets are unchanged. Dependencies and `uv.lock` are unchanged.

The separate `ingestor-integration` job follows Ingestor selection and invokes the
existing `ingestor:test-integration` target. Current tests compose the handler and
immutable publisher with memory storage and provider doubles, covering READY-last
success and manifest failure preserving the prior READY pointer. They require no
external Kafka/MinIO/provider services. This is component integration evidence when
executed, not a live infrastructure or provider test. The unit job still excludes
this suite. Separate artifact names preserve both report sets.

## Required check and reports

`CI Quality Gate` always evaluates its dependencies. It requires affected detection
to succeed, validates selection outputs, requires every selected job (including
Ingestor integration) to succeed, and accepts skipped jobs only when unselected.
Failure, cancellation, missing outputs or unexpected skipping cannot produce a
successful gate. Branch protection must separately require this check in GitHub;
repository workflow source cannot establish that setting. It has not been inspected
or changed here.

Python and Java jobs attempt report upload even after failure, with 14-day retention.
Python preserves pytest HTML/JUnit and coverage outputs; Java preserves test results,
test HTML and JaCoCo reports. Missing artifacts warn and never prove coverage.
Console currently has no declared coverage/report target; this change does not
invent one. Aggregate coverage thresholds and attributable impacted-component
coverage enforcement remain follow-up work. This gate does not replace the
increment verification impact/source/test matrix or its 80% line/branch requirement.

## Verification and documentation evidence

[ci-policy.test.cjs](../../tools/tests/ci-policy.test.cjs) contains focused cases for
invalid/missing baselines, current/historical module lookup, shared/backend selection,
Console-only and docs-only selection, manual full selection, valid skips, detection
failure, malformed outputs, and required-job failure/cancellation/skip. The workflow
invokes them through `nx run omni:ci-policy-test`. These cases supplement the
existing publication integration suite.

On 2026-10-17, with owner approval, `npx.cmd nx run omni:ci-policy-test`
passed all 7 tests and `npx.cmd nx run ingestor:test-integration` passed both
publication tests (zero failures/skips). Windows used the `.cmd` shim because
PowerShell blocks `npx.ps1`. The successful policy rerun used `NX_NO_CLOUD=true`
after the initial process lingered on a blocked Nx Cloud connection. Integration
used a workspace-local `UV_CACHE_DIR` after access to the default cache was denied,
and `UV_LOCKED=true` to prevent lockfile updates; its command exited zero despite
an Nx Cloud connectivity warning. Pytest also reported one cache-write warning;
JUnit and HTML reports were generated. No numeric coverage was measured because
the integration target uses `--no-cov`. Workflow syntax, full stack checks and runner
behavior remain unverified; an actual Actions run is still required.

This is a bounded CI maintenance fix, not finalization of a roadmap increment.
The increment registry and numbered plans keep their current status and evidence.
Development navigation, documentation index, contract usage and release notes are
updated. Architecture, ADR, flows, Kafka, lake, database, deployment and service
responsibility documents need no edits because their contracts and behavior do not
change. `AGENTS.md`, `CLAUDE.md` and `.roo/rules` retain the existing Nx/approval
and evidence rules; CI source changes do not require new agent policy. Only the
focused local test results above are claimed. No full verification, CI, merge,
deployment, provider, production or increment-completion pass is claimed.
