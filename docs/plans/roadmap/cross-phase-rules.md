# Cross-Phase Engineering Rules and Definition of Done

## Engineering Rules

- One shared dataset has one logical writer for a given partition/version.
- Shared contracts and reusable object-oriented abstractions belong in the appropriate shared module or [`py_common`](../../../libs/py-common/py_common); service-specific domain logic remains in its owning service.
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

- metadata in [`implementation-increments.md`](implementation-increments.md) records `completed`; PR URL and verified commit are recorded when available for traceability but are not substitutes for verification;
- implementation and migration/fallback paths are documented;
- code-review-graph change detection and impact-radius analysis identify the changed and directly impacted source surfaces;
- graph output is reconciled against canonical documentation for Platform, Analyzer, Ingestor, Query Service, Console, shared contracts/libraries, persistence, configuration, tests, and operations, with explicit impacts or concrete no-impact reasons;
- an impact-to-test matrix maps every changed or directly impacted production file/safety-critical symbol and acceptance criterion to executed tests;
- attributable coverage satisfies each plan's critical-component threshold, defaulting to at least 80% line and 80% branch coverage; aggregate project coverage alone is insufficient;
- safety-critical success, failure, retry, compatibility, concurrency, transaction, and data-loss-prevention branches are explicitly exercised when applicable;
- affected contracts have producer/consumer impact review and executed tests on both sides;
- targeted tests pass;
- relevant Nx lint, test, coverage, build, format, and contract targets pass when required by the increment;
- configured CI results are recorded when CI exists for the delivery path, without treating CI as a replacement for attributable coverage;
- operational metrics/logs exist for new runtime behavior;
- configuration and example environment files are updated when contracts change;
- backward compatibility or explicit breaking-change handling is verified;
- [`AGENTS.md`](../../../AGENTS.md), [`CLAUDE.md`](../../../CLAUDE.md), and [`.roo/rules`](../../../.roo/rules) are synchronized when architecture, contracts, or workflows change;
- any command or coverage measurement that could not run is recorded with the reason and keeps the affected gate unresolved;
- obsolete code/config removal is tracked rather than silently deferred.

## Automation quality gates

Before any planning-only PR is complete, verify:

- every active increment has a unique ID;
- every dependency points to an existing increment;
- there are no dependency cycles;
- each pending increment has objective acceptance criteria;
- required impact analysis, focused test, coverage, and applicable CI commands exist or are explicitly planned first;
- completed statuses have source, blast-radius reconciliation, attributable coverage, executed checks, documentation, and applicable runtime evidence; Git metadata is traceability only;
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
