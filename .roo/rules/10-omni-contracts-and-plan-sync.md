# Omni Contracts and Plan Sync

Use `AGENTS.md` as the repository-wide coding-agent rule source and `docs/IMPLEMENTATION_PLAN_STANDARD.md` as the implementation-plan standard.

## Cross-Service Contracts

- Canonical Kafka/service-to-service schemas live under `libs/contracts/proto` after a message family is migrated to proto3.
- Generated Java/Python files under `libs/contracts/gen` are ignored build output; never commit or hand-edit them.
- Use Nx `contracts` targets for format/lint/generate/breaking checks; consumer builds must depend on `contracts:generate` before compiling generated types.
- Review producer and consumer together for every contract change.
- Never change/reuse an existing protobuf field number; reserve deleted field numbers/names.
- Dataset manifests under S3/R2 `_metadata/` remain JSON and are separate from protobuf transport contracts.
- Kafka business messages use logical dataset references, not physical bucket/object paths.

## Job Dependencies

- Cron gaps do not guarantee upstream completion.
- Hard dataset dependencies are checked from READY manifests before execution is dispatched.
- A missing/stale dependency is BLOCKED/deferred, not a failed execution.
- Use upstream/downstream `dataVersion` lineage for CURRENT_INPUTS checks.
- Do not scan full Parquet prefixes for normal readiness checks when a manifest exists.

## Plan / Guidance Sync

When implementing or materially changing any implementation plan:

1. Read `docs/IMPLEMENTATION_PLAN_STANDARD.md`.
2. Review the plan's `Contract Impact`.
3. Review/update `AGENTS.md`, `CLAUDE.md`, `.roo/rules/` and canonical docs when architecture/workflow/tool rules changed.
4. Do not mark the plan Done while repository guidance is stale.
5. When touching an older plan missing `Repository Guidance Updates`, add that section.

Keep Zoo Code rules short and actionable; link to canonical docs rather than duplicating full architecture prose.
