# Normalize Parquet Date Contracts Increment

## Status

`verification_pending`

Implementation is present on `feature/parquet-date-normalization`. Targeted local
acceptance checks pass in implementation commit `2ecb6ba`. The increment is on
[stacked draft PR #16](https://github.com/tanlocit9/omni/pull/16); completion
requires CI to pass for its final head.

## Goal

Remove cross-dataset ambiguity between calendar business dates and event
timestamps without renaming semantic fields or changing dataset ownership.

## Baseline and dependency

- Base: stable `feature/phase-7` head `7b3c98f` with passing CI run `#150`.
- Requires the shared Parquet and READY-last manifest abstractions already in
  `libs/py-common`.
- Independent from Phase 7 runtime/deployment code; delivered as a stacked draft
  PR whose base remains `feature/phase-7` until that PR is merged.

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

Use the repository CI workflow as final evidence. Mark this increment `completed`
only after its own branch head is green.

## Migration and rollback

`ParquetDateBackfill` accepts only a READY partition that references one exact
Parquet object. It writes a deterministic sibling under
`_versions/date-contract-v1/`, validates the persisted candidate, writes the
immutable manifest, and replaces READY last. Wildcard/multi-object partitions are
rejected so an owner-specific partition rewrite can be supplied. On failure the
old READY pointer and object remain valid; rollback is republishing the previous
immutable manifest as READY.

After Phase 7 merges, rebase this branch onto `main` and retarget the draft PR;
do not merge automatically.
