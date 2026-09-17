---
name: install-python-dependency
description: Add a Python package to one specific Omni Python project through its declared Nx add target, then synchronize that project's environment through its declared sync target. Ask the owner to select the target app or project whenever it was not explicitly named.
---

# Install Python Dependency

Use this skill when the owner asks to install, add, or upgrade a Python package for
an Omni application or Python library.

## Required Inputs

Obtain these values before changing dependencies:

1. The exact package requirement, such as `vnstock>=4.0.2` or `pandas`.
2. The exact Nx project that owns the dependency.

If the package was not specified, ask the owner for it.

If the target app or project was not explicitly specified, stop and ask the owner to
select the specific Python project. Do not infer a project from the visible file,
active editor, current terminal, package name, or previous unrelated work. Offer only
Python projects confirmed by their `project.json`, normally including `ingestor`,
`analyzer`, `query-service`, and `py-common` when applicable.

If the owner names a filesystem directory instead of an Nx project, inspect that
project's `project.json` and resolve its declared `name`. Remember that `apps/core`
is the Nx project `platform`, but it is not a Python dependency target.

## Boundaries

- Run commands from the workspace root.
- Use only targets declared by the selected project's `project.json`.
- Never install with bare `pip`, `uv pip`, `uv add`, or another package-manager
  command when the project has suitable Nx targets.
- Do not install a package globally or into another project's environment.
- Do not edit generated files under `libs/contracts/gen`.
- Do not guess a version, extras, dependency group, or source index. Preserve the
  owner's exact package requirement.
- Do not move reusable behavior into an application merely because a package is
  being installed; dependency ownership must match the code that uses it.
- Adding and synchronizing a dependency does not authorize build, test, lint,
  format, or other verification commands.

## Workflow

### 1. Resolve the owning project

Read the selected project's `project.json` and confirm:

- its declared Nx project name;
- it is a Python project;
- an `add` target exists;
- a `sync` target exists.

If either required target is absent, stop and report the missing target. Do not fall
back to a direct package-manager command unless the owner explicitly approves that
exception after seeing it explained.

Read the project's `pyproject.toml` to determine whether the requested requirement is
already declared and whether the request changes its existing constraint.

### 2. Confirm the intended change

Before mutation, summarize:

```text
Project: <Nx project name>
Package: <exact requirement>
Add target: nx run <project>:add --name="<exact requirement>"
Sync target: nx run <project>:sync
```

If the owner supplied both project and package in the current request, that request is
sufficient authorization for these two dependency operations. Ask only when either
required input is missing or ambiguous.

### 3. Add through Nx

Run the project's declared add target from the workspace root:

```text
nx run <project>:add --name="<exact requirement>"
```

Wait for the result. If it fails, stop, report the sanitized error, and do not run
sync as though the add succeeded.

Do not manually duplicate the dependency declaration when the add target has already
updated `pyproject.toml`.

### 4. Synchronize through Nx

After a successful add, run the project's declared sync target from the workspace
root:

```text
nx run <project>:sync
```

Wait for the result. If synchronization fails, report that the dependency declaration
may have changed but environment synchronization is incomplete. Do not claim the
installation succeeded completely.

Use a separate `lock` target only when the selected project's declared workflow or
tool output requires it, or when the owner explicitly requests it. Do not substitute
`lock` for `sync`.

### 5. Inspect the result statically

Read the selected project's `pyproject.toml` and relevant lockfile after the commands
to confirm which files changed. Do not run import checks, tests, lint, format, builds,
or equivalent verification unless separately requested or approved under
`AGENTS.md`.

Run code-review-graph change detection after edits as required by repository policy;
this is analysis, not executable verification.

### 6. Report conservatively

Report:

```text
Project: <Nx project name>
Package: <resolved declaration>
Add: <succeeded or failed>
Sync: <succeeded, failed, or not run>
Changed files: <dependency files only>
Verification: not run unless separately approved
```

Never claim runtime compatibility merely because dependency addition and environment
synchronization completed.

## Examples

When the owner says "install vnstock for ingestor", resolve `ingestor`, confirm its
`add` and `sync` targets, then use:

```text
nx run ingestor:add --name="vnstock"
nx run ingestor:sync
```

When the owner says only "install vnstock", ask which specific Python app or project
should own it before running or editing anything.
