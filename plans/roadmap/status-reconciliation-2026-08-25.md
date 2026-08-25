# Roadmap Status Reconciliation — 2026-08-25

This note reconciles plan/status drift discovered on `feature/parquet-date-normalization` after Phase 7 completion, Parquet date-contract implementation, and the canonical Sector Transition writer cleanup.

Until the canonical roadmap files are folded back into `main`, this note overrides stale status/semantics in older plan text where they conflict with the verified source state below.

## Verified source state

- `main` baseline for PR #16: `bd377c3022fa4561e28fd70d84754ff59a3781eb`.
- Date-normalization implementation commit: `215b41a016924a5a1328b6c9f7b43ca22ee3f16f`.
- Draft PR #16 targets `main` directly; it is no longer stacked on `feature/phase-7`.
- CI run #153 completed successfully for implementation commit `215b41a`.
- Phase 7 P7-I1 through P7-I3 were previously completed with passing CI evidence and are not prerequisites that need to be reimplemented on this branch.

## Stale plan corrections

### P1-I3 — canonical sector writer

Older roadmap text that says P1-I3 is uncommitted, has no verified commit, or has no CI evidence is outdated.

Current state:

- implementation is committed in `215b41a`;
- Platform/Analyzer local verification passed before commit;
- CI run #153 passed for `215b41a`;
- the change is carried by draft PR #16, although it does not have a dedicated increment-only PR.

Keep P1-I3 below `completed` if the roadmap requires an increment-specific PR as a hard completion rule, but do not describe it as uncommitted or CI-unverified.

### P3-I4 — Parquet date normalization

Older text that says CI is pending is outdated.

Verified implementation state:

- business-date columns use Arrow/Parquet `date32` and DuckDB `DATE`;
- event timestamps use UTC microsecond timestamps / DuckDB `TIMESTAMPTZ`;
- shared Python storage normalizes legacy reads;
- Query Service casts legacy Parquet schemas at the read boundary;
- safe versioned READY-last backfill support is present;
- CI run #153 passed for implementation commit `215b41a`.

The branch contains subsequent documentation-only commits, so keep final-branch completion semantics aligned with the repository's exact-head CI rule if that rule is enforced.

### Phase 4 dependency guard

Any plan text saying dependency metadata is still documentation-only is outdated. P4-I1 and P4-I2 were re-baselined as completed with passing PostgreSQL/Platform evidence before this branch.

### Phase 7

Any plan text treating Phase 7 as pending, prerequisite-blocked, or not implemented is outdated. P7-I1, P7-I2, and P7-I3 are completed according to the canonical execution evidence recorded on 2026-08-25.

### P1-I4 — child execution semantics

The older `workType/workKey` migration proposal is no longer authoritative as written.

Do **not** perform a repository-wide replacement of domain keys such as `symbolKey`, `sectorKey`, or `exchangeKey` with a generic `workKey`.

The intended boundary is:

```text
Scheduler / execution persistence
  executionKey   // generic execution identity when a generic key is needed

Domain-specific contracts
  SymbolJobMessage.symbolKey     // keep semantic domain key
  SectorJobMessage.sectorKey     // keep semantic domain key
  ExchangeJobMessage.exchangeKey // keep semantic domain key
```

`SYMBOL`, `SECTOR`, and `EXCHANGE` describe domain scope; `STRATEGY` describes processing behavior and must not be treated as the same dimension in one `workType` enum.

Therefore, for future P1-I4 work:

- keep scheduler orchestration domain-neutral;
- preserve explicit domain keys in Kafka/service contracts;
- do not remove `symbolKey` from domain-specific messages merely to satisfy generic child-execution storage;
- if migration compatibility is needed, limit it to the execution persistence/metadata boundary instead of rewriting downstream domain contracts;
- notification event ownership remains part of P1-I4, independent from this naming correction.

## Files known to contain stale wording

The following files should be interpreted with this reconciliation note when their statements conflict with the verified state above:

- `plans/roadmap/README.md`
- `plans/roadmap/implementation-increments.md`
- `plans/roadmap/phase-1-backend-core-stabilization.md`
- `plans/roadmap/execution-log.md`
- `plans/consolidated-numbered-implementation-phases.md`
- older supporting Phase 1 / manifest plans that still refer to `workType/workKey` as a repository-wide migration

## Next documentation fold-in

When this branch is integrated, fold these corrections into the canonical roadmap files rather than keeping two competing sources of truth. Preserve historical execution-log rows as historical evidence; correct current-selection/status summaries and future-work semantics only.
