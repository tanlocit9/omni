# Bounded local CI repair

Run from the repository root through the declared Nx target:

```text
nx run omni:ci-fix --report=.agent/ci-jobs.json
nx run omni:ci-fix --report=.agent/ci-jobs.json --table
nx run omni:ci-fix --report=.agent/ci-jobs.json --apply
```

The default prints a plan without running verification or changing files.
`--table` changes only the plan's presentation and does not imply `--apply`.
Child processes use the shared hidden-process and Windows-local quiet-Nx defaults;
the existing isolated repair environment and stop-on-first-failure policy remain.
`--apply` requires the authorization in [AGENTS.md](../AGENTS.md). The tool uses
existing Nx targets and [check_result.py](check_result.py); it does not execute
commands copied from logs, invent fixes for arbitrary source failures, commit,
push, restart hosted jobs or change workflow gates.

## Snapshot contract

Use a read-only GitHub connector/API to obtain the workflow run's actual head SHA,
jobs and step conclusions. Save the normalized snapshot in ignored `.agent/`:

```json
{
  "run_id": 123,
  "head_sha": "<full SHA of the workflow source commit>",
  "jobs": [
    {
      "name": "Python / py-common / Verify py-common",
      "conclusion": "failure",
      "steps": [{ "name": "Lint", "conclusion": "failure" }]
    }
  ]
}
```

The snapshot must match current HEAD; dirty source repairs are allowed and must be
reviewed separately. Do not rewrite an old snapshot's SHA to make it pass. Refresh
the snapshot after committing or changing branches. Job IDs/names/steps alone do
not prove which source failed. Keep raw logs, credentials and runner environment
out of the normalized report.

## Supported execution

The initial version handles the four Python leaf jobs declared by python-ci.yml:
Analyzer, Ingestor, Query Service and py-common. It accepts one failed step per
failed project: Sync Python dependencies, Lint, Test or Build. Unknown failed jobs,
setup/infrastructure failures, multiple failed steps, duplicate projects and missing
declared targets fail closed. CI Quality Gate is aggregate evidence, never a
separate shell command. An aggregate-only failure cannot become a local PASS.

For each failed project, run locked `sync-ci`, then lint, test and build. A lint
failure additionally runs the declared full-project `lint-fix` before checking.
Source/test failures require a reviewed code repair; the tool reruns checks, it
does not synthesize behavioral patches. Existing unrelated edits must be preserved;
full-project lint fixes require ownership review before committing.

Each project gets a separate `UV_PROJECT_ENVIRONMENT` inside the ignored result
directory. This prevents another workspace member's dev plugins from masking
missing package-local dependencies. No shared .venv, lockfile or package manifest
is changed. Python comes from .python-version; uv comes from the caller's toolchain,
so local Windows verification is not an Ubuntu/CI-pinned-uv equivalence claim.
Keep UV_LOCKED for sync/lint/test; remove it only for build's frozen exporter, as
the hosted workflow already does. Nx Cloud stays disabled. Stop at the first
failure, retain missing checks as incomplete, and never weaken thresholds.

Recorded evidence lives in `.agent/check-results/CI-REPAIR-<run-id>-<head-prefix>/`.
The input snapshot and every executed command are retained there. Read only:

```text
python tools/check_result.py conclusion --increment CI-REPAIR-<run-id>-<head-prefix>
```

A local PASS covers only the selected repaired Python jobs. It is not a hosted
workflow PASS, deployment, provider, migration, coverage or increment-completion
attestation. The caller remains responsible for refreshing CI after delivery.
