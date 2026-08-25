# Normalize Parquet Date Contracts Increment

## Status

`verification_pending`

Implementation is present on `feature/parquet-date-normalization`. Targeted local
acceptance checks pass. The latest implementation commit is `215b41a`, and the
increment is on [draft PR #16](https://github.com/tanlocit9/omni/pull/16), now based
directly on `main`. CI run `#153` is in progress for implementation commit
`215b41a`; completion still requires a green CI result for the final branch state.

The same implementation commit also contains the P1-I3 canonical Sector
Transition writer cleanup: the previous per-sector competing writers are replaced
by one logical writer per shared output family. That change is committed but
remains verification-pending until branch CI completes successfully.

## Goal

Remove cross-dataset ambiguity between calendar business dates and event
timestamps without renaming semantic fields or changing dataset ownership.

## Baseline and dependency

- Base: current `main` at `bd377c3`; before this documentation-only status update,
  the implementation branch was 3 commits ahead and 0 behind.
- Phase 7 prerequisite work has already been integrated into the current `main`
  baseline; PR #16 no longer needs to remain stacked on `feature/phase-7`.
- Requires the shared Parquet and READY-last manifest abstractions already in
  `libs/py-common`.
- Runtime/deployment ownership remains independent from the date-contract
  normalization itself.

## Contract

- `date`, `signal_date`, `evaluation_date`, `target_date`, `resolved_date`, and
  `generated_from_date`: Arrow/Parquet `date32`, DuckDB `DATE`.
- `generated_at`, `calculated_at`, `updated_at`, `last_recalculated_at`,
  `actual_updated_at`, and indicator-specific `*_calculated_at`: Arrow timestamp
  microseconds with UTC timezone; DuckDB `TIMESTAMPTZ`.
- Semantic field names remain unchanged.

## Scope

- Shared encode/decode and legacy compatibility in `py_common.storage`.
- EOD, Indicators, Signals, Sector Wave, and Sector Transition producers and
  consumers through the shared storage boundary.
- Canonical manifest column metadata and Query Service legacy casts.
- Idempotent, versioned, read-back-validated backfill with immutable manifest then
  READY pointer publication. The previous READY object is never overwritten.
- Contract, join, manifest, Query Service, and backfill failure-safety tests.
- P1-I3 scheduler cleanup now uses one canonical-universe Sector Transition writer
  for analysis and one for outcome evaluation instead of one writer per sector.

## Acceptance checks

```bash
npx nx run py-common:test
npx nx run ingestor:test
npx nx run analyzer:test
npx nx run query-service:test
npx nx run-many -t lint build -p py-common,ingestor,analyzer,query-service
npx nx format:write
npx nx format:check
```

Latest recorded local evidence for the implementation:

- `py-common:test`: 144 passed
- `ingestor:test`: 18 passed
- `analyzer:test`: 79 passed
- `query-service:test`: 17 passed
- lint/build passed for all four owning projects
- `nx format:write`, `nx format:check`, and `git diff --check` passed

Use repository CI run `#153` as evidence for implementation commit `215b41a`.
Because this status update is documentation-only, keep the increment
`verification_pending` until the branch's final CI state is green; do not promote
completion from local evidence alone.

## Migration and rollback

`ParquetDateBackfill` accepts only a READY partition that references one exact
Parquet object. It writes a deterministic sibling under
`_versions/date-contract-v1/`, validates the persisted candidate, writes the
immutable manifest, and replaces READY last. Wildcard/multi-object partitions are
rejected so an owner-specific partition rewrite can be supplied. On failure the
old READY pointer and object remain valid; rollback is republishing the previous
immutable manifest as READY.

PR #16 is already targeted to `main`; no further Phase 7 retarget step is required.
Do not merge automatically. Keep status `verification_pending` until CI is green.
