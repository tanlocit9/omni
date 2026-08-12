# Phase 0 — Immediate Correctness Hotfixes

## Goal

Remove known correctness and portability defects without mixing in architecture migrations.

## Phase status

Completed on `main` with merged source in [PR #7](https://github.com/tanlocit9/omni/pull/7) and successful [CI evidence](https://github.com/tanlocit9/omni/actions/runs/31606526578).

## Increment P0-I1 — Due-query predicate and repository matrix tests

### Metadata

| Field                   | Value                                           |
| ----------------------- | ----------------------------------------------- |
| id                      | P0-I1                                           |
| title                   | Due-query predicate and repository matrix tests |
| status                  | completed                                       |
| priority                | critical                                        |
| depends_on              | []                                              |
| blocks                  | [P1-I1]                                         |
| owned_modules           | [apps/core]                                     |
| execution_mode          | autonomous                                      |
| requires_owner_decision | false                                           |
| pr                      | https://github.com/tanlocit9/omni/pull/7        |
| last_verified_commit    | 8efc965b2084a16af9c733a9631e4e4729c23be4        |

### Goal

Ensure inactive jobs are never returned by [`JobDefinitionRepository.findJobsDue()`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobDefinitionRepository.java) for any `nextRun` value.

### Current verified baseline

Due-query source and repository matrix tests are merged on `main`; project graph, formatting, platform tests, and platform build passed in the recorded CI run.

### Dependencies and eligibility conditions

No dependencies.

### In scope

- Apply active predicate to both due conditions.
- Preserve `nextRun = NULL` as immediately eligible for active jobs.
- Add matrix coverage for active/inactive and before/equal/after/null `nextRun` values.

### Out of scope

- Scheduler claiming.
- Outbox integration.
- Dependency guard behavior.

### Expected implementation approach

Use the predicate shape `isActive = true AND (nextRun <= now OR nextRun IS NULL)` and deterministic ordering.

### Files or modules likely to be touched

- [`apps/core`](../../apps/core)
- [`JobDefinitionRepository`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/JobDefinitionRepository.java)

### Acceptance criteria

- Active jobs with `nextRun <= now` are selected.
- Active jobs with `nextRun = NULL` are selected.
- Inactive jobs are not selected for `before`, `equal`, `after`, or `NULL` `nextRun` values.
- Equal-to-now boundary is covered.
- Repository order is deterministic when multiple jobs are due.

### Required unit tests

Repository matrix tests for active status and `nextRun` values.

### Required integration or contract tests

Repository persistence test against the configured Core test database setup.

### Required Nx/build/CI commands

Inspect targets with `nx show project core`, then run Core test/lint/build targets through `nx run core:<target>` where available.

### Data migration or backward-compatibility considerations

No migration. Runtime behavior only removes incorrect inactive-job eligibility.

### Security, concurrency, data-quality, and operational risks

Low risk; ensures scheduler data quality before concurrency changes.

### Stop conditions requiring owner input

Stop if `nextRun = NULL` is no longer intended to mean immediately eligible for active jobs.

### Completion and rollback notes

Rollback restores the prior unsafe due predicate and must be avoided unless replacement behavior is approved.

## Increment P0-I2 — Workspace path normalization and Linux Nx verification

### Metadata

| Field                   | Value                                                  |
| ----------------------- | ------------------------------------------------------ |
| id                      | P0-I2                                                  |
| title                   | Workspace path normalization and Linux Nx verification |
| status                  | completed                                              |
| priority                | critical                                               |
| depends_on              | []                                                     |
| blocks                  | [P1-I1, P2-I1, P6-I3]                                  |
| owned_modules           | [workspace]                                            |
| execution_mode          | autonomous                                             |
| requires_owner_decision | false                                                  |
| pr                      | https://github.com/tanlocit9/omni/pull/7               |
| last_verified_commit    | 8efc965b2084a16af9c733a9631e4e4729c23be4               |

### Goal

Normalize workspace paths so project discovery is portable across Windows and Linux.

### Current verified baseline

Workspace portability changes are merged on `main`; Linux CI confirmed Nx project discovery, formatting, platform tests, and platform build.

### Dependencies and eligibility conditions

No dependencies.

### In scope

- Replace Windows-style workspace patterns with forward-slash patterns.
- Verify current Java/Python projects remain discoverable.
- Keep project names and target behavior unchanged.

### Out of scope

- Adding new Nx projects.
- Changing target implementations.
- Product feature implementation.

### Expected implementation approach

Inspect root workspace configuration, Nx plugin configuration, scripts, and CI path filters; normalize only path separators.

### Files or modules likely to be touched

- [`nx.json`](../../nx.json)
- root workspace configuration and CI path filters when present

### Acceptance criteria

- `nx show projects` lists existing Core, Analyzer, Ingestor, and py-common projects.
- Non-interactive Nx graph/project discovery works on Linux CI.
- Formatting check passes.
- No project target behavior changes are included.

### Required unit tests

No product unit tests required unless path normalization touches executable scripts.

### Required integration or contract tests

Nx project discovery and graph generation checks.

### Required Nx/build/CI commands

Run `nx show projects`, non-interactive graph generation if available, `nx format:check`, and touched project checks through `nx run <project>:<target>`.

### Data migration or backward-compatibility considerations

No data migration.

### Security, concurrency, data-quality, and operational risks

Low risk; CI portability is a quality gate for later automation.

### Stop conditions requiring owner input

Stop if path normalization would require renaming projects or changing target behavior.

### Completion and rollback notes

Rollback is only acceptable if an equivalent portable discovery mechanism replaces the normalized paths.
