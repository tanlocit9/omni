# Contracts and Plan Sync

Use [`AGENTS.md`](../../AGENTS.md) for repository policy and
[`docs/IMPLEMENTATION_PLAN_STANDARD.md`](../../docs/IMPLEMENTATION_PLAN_STANDARD.md)
for plan requirements.

- For contract changes, review producers, consumers, schemas/configuration, tests,
  compatibility, and canonical contract docs together; run graph impact analysis.
- Never edit [`libs/contracts/gen`](../../libs/contracts/gen), reuse protobuf field
  numbers/names, or put physical storage paths in Kafka business messages.
- Preserve manifest-based readiness, `dataVersion` lineage, and READY-last writes.
- Manual jobs must reuse Platform's private API, claims, dependency guards,
  registered producers, and outbox; never add force/bypass or browser-to-Kafka.
- When implementation changes architecture, contracts, workflow, or tooling,
  synchronize [`AGENTS.md`](../../AGENTS.md), [`CLAUDE.md`](../../CLAUDE.md),
  applicable rules, and canonical docs before marking work done.
- Keep rules short and link to canonical documentation instead of copying it.
- Required build, test, lint, format, or contract checks remain subject to the
  verification approval gate in [`AGENTS.md`](../../AGENTS.md).
