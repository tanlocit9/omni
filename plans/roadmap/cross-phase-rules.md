# Cross-Phase Engineering Rules and Definition of Done

## Engineering Rules

- One shared dataset has one logical writer for a given partition/version.
- Shared contracts and reusable object-oriented abstractions belong in the appropriate shared module or [`py_common`](../../libs/py-common/py_common); service-specific domain logic remains in its owning service.
- Generated Proto code is a boundary type, not a domain model.
- Dataset data is not considered consumable until its READY manifest is published.
- Missing dependencies block/defer a job; they do not create a fake worker failure.
- All scheduler state transitions must be transactionally safe and idempotent.
- Every contract change must check producers, consumers, persistence, tests, configuration, and documentation.
- Prefer forward-compatible additive changes unless an increment records an
  explicit owner-approved breaking cutover. P1-I4 is such an exception: snapshot,
  drain, manually clear execution history, validate, deploy all participants
  together, and remove legacy execution/status formats without a dual-read window.
- All timestamps are UTC at rest and on the wire; presentation may use Asia/Ho_Chi_Minh.
- Secrets, raw object-store credentials, and unrestricted object keys must never be exposed to the browser.
- CI and automated tests are quality gates, not deferred cleanup.
- Portable deployment must not silently become production deployment.

## Definition of Done for Every Increment

An increment is complete only when:

- metadata in [`implementation-increments.md`](implementation-increments.md) records `completed` status, PR URL, and verified commit;
- implementation and migration/fallback paths are documented;
- affected contracts have producer/consumer impact review;
- targeted tests pass;
- relevant Nx lint, test, build, format, and affected targets pass;
- GitHub CI result is recorded when a draft PR exists;
- operational metrics/logs exist for new runtime behavior;
- configuration and example environment files are updated when contracts change;
- backward compatibility or explicit breaking-change handling is verified;
- source graph `detect_changes` and impact-radius checks are reviewed;
- [`AGENTS.md`](../../AGENTS.md), [`CLAUDE.md`](../../CLAUDE.md), and [`.roo/rules`](../../.roo/rules) are synchronized when architecture, contracts, or workflows change;
- any command that could not run is recorded with the reason;
- obsolete code/config removal is tracked rather than silently deferred.

## Automation quality gates

Before any planning-only PR is complete, verify:

- every active increment has a unique ID;
- every dependency points to an existing increment;
- there are no dependency cycles;
- each pending increment has objective acceptance criteria;
- required tests and CI commands exist or are explicitly planned first;
- completed statuses have source, PR, commit, and CI evidence or are clearly marked as current-branch evidence pending CI;
- only eligible increments are marked `ready`;
- approval-required decisions are clearly surfaced;
- terminology is consistent across roadmap files;
- links between plans resolve;
- the final diff contains no accidental product-code changes for planning-only tasks.

## Parallel execution rules

- Parallel increments are disabled by default for scheduled automation.
- Treat increments touching the same owned module as conflicting unless the roadmap explicitly documents isolation.
- Never run scheduler concurrency, Kafka contract migration, deployment authority, or UI scaffolding increments in parallel with changes to their shared modules.

## Plan-update authority

Use [`automation-rules.md`](automation-rules.md) for detailed authority rules. Material changes remain Proposed until owner approval.

## Explicitly Deferred Work

The following should not block early phases unless a current implementation requires them:

- public/customer-facing Omni Console;
- write/edit operations from Dataset Explorer;
- a general SQL warehouse or distributed query cluster;
- automatic dataset garbage collection before version/reference retention rules exist;
- protobuf persistence for dataset manifests;
- AI-generated signals or AI orchestration inside the scheduler;
- multi-tenant authorization and billing;
- advanced notification preference UI.
