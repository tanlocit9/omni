# AGENTS.md

Canonical repository-wide agent policy. Keep architecture and business details in
[docs](docs); editor rules add only mode-specific guidance.

## Required workflow

- Read [ownership](docs/development/001-where-to-change.md) and the relevant
  canonical documents indexed by [docs/README.md](docs/README.md).
- Use code-review-graph before exploring unfamiliar implementations and for impact
  analysis before changing shared contracts, APIs, Kafka messages, storage paths,
  dataset ownership, or shared configuration. Run change detection after edits.
- Every feature plan and increment verification needs graph impact analysis plus
  reconciliation against architecture, flows, Kafka, data-lake, database,
  deployment, service and ownership docs. Explicitly cover Platform, Analyzer,
  Ingestor, Query Service, Console, shared contracts/libraries, persistence,
  configuration, tests and operations with impacts or concrete no-impact reasons.
  Bounded, stale or missing graph results cannot prove absence of impact.
- Shared skills live in [.agents/skills](.agents/skills); load the relevant skill
  instead of copying its workflow into editor rules.

## Agent delegation

- Delegate bounded test-writing, behavior-preserving local refactors and repetitive
  work to the OSS worker when it is worthwhile. The worker is a separate Codex
  process, not a native subagent:

  `codex exec --profile cheap_worker --cd <repo-root> --sandbox workspace-write "<task>"`

- The primary agent owns scope, architecture, decomposition, integration and review;
  resolve decisions requiring owner approval before delegating implementation.
- The worker has no conversation context. The task prompt must be self-contained:
  goal, owned files, files it must not touch, acceptance criteria, and the relevant
  rules from this document (contract/data guardrails, Nx command boundary).
- Parallel workers need independent tasks and non-overlapping write ownership in
  the shared worktree. Do not create branches, worktrees or commits for them
  without an explicit request.
- The worker inherits the verification authority and scope below. Its task prompt
  must identify the verification scope, relevant Nx targets and any restrictions.
  It may run necessary checks within that scope without separate command approval;
  checks that are restricted, unavailable or omitted must be reported as **not run**.
- Never accept worker output unreviewed. Inspect its changes, check them against
  the ownership and contract rules, and distinguish reported results from executed
  checks. Cross-service, contract, Kafka, storage and shared-config changes are not
  delegable; the primary agent makes them.
- If `cheap_worker` is unavailable (missing profile, proxy down, missing API key),
  continue locally and report it. Keep model/provider settings in host
  configuration; see [tooling](docs/development/003-codex-control-and-tooling.md#agent-delegation).

## Worktree inspection boundary

- Establish Git state once when edits are expected; cache that baseline and track
  owned files. Repeat repository-wide status/diff only for detected external changes,
  Git-dependent tasks, or commit/push/PR boundaries.
- Re-read files before editing; preserve unrelated user and agent changes.
- Do not inspect history, remotes, PRs, tags, commit identity or CI unless relevant.
- Do not create branches, commits, tags, pushes or PRs without an explicit request
  or explicit roadmap-automation invocation. Never merge automatically.
- Keep persistent agent state in ignored local caches; it is advisory, not Git authority.

## Nx command boundary

- Run from the workspace root. Inspect unfamiliar projects with
  `nx show project <project>`; use only targets defined in their `project.json`.
- Invoke operations as `nx run <project>:<target>`. Use underlying tools only
  when no suitable target exists, and state the exception.
- Use Python Nx targets for dependencies, environment sync and execution. After
  dependency edits, run the project's `install` then `lock` targets.
- `apps/core` is project `platform`. Python targets use the project as cwd;
  imports from `app` are intentional.
- For PowerShell-only syntax, submit the full command as
  `powershell -NoProfile -Command "..."`; do not wrap plain Nx commands.

<a id="verification-approval-gate"></a>

## Verification authority and scope

- Authorized work includes the verification needed to implement, diagnose, review
  or complete it. Run relevant build, test, coverage, lint, format, `nx affected`,
  executable documentation checks and isolated integration/E2E checks without
  asking for separate approval of each command or command list. Use declared Nx
  targets, start with focused checks and expand when failures, impact or the
  increment's required gate justify it.
- Respect explicit user limits such as read-only, no tests, documentation-only
  or owner-run verification. Format only intended files and preserve unrelated
  dirty work. Repository skills and editor rules must follow this policy rather
  than add a separate routine-verification approval requirement.
- Verification may create and clean up its own disposable fixtures, containers
  and build outputs. It does not authorize destructive operations on retained
  data, retained database migrations, business-job triggers, Kafka offset resets,
  schedule/service activation, deployment, Git publication or other actions that
  require separate authorization under the requested task. Do not silently aim
  test fixtures at retained services or bypass sandbox/security approvals.
- Record commands, scope, results and material limitations. Report omitted or
  unavailable checks as **not run**; never infer a pass. Graph analysis and static
  path/link inspection are not executed test evidence. Owner attestation remains
  a separate explicit confirmation and is never inferred from permission to test.

## Blast-radius and coverage verification

- Verify behavior against the cross-service impact assessment above. Map every
  changed/directly impacted production file, safety-critical symbol and acceptance
  criterion to executed tests.
- Critical components require attributable coverage of at least 80% lines and
  80% branches, or stricter plan thresholds. Aggregate coverage cannot hide gaps.
- Exercise applicable success, failure, retry, compatibility, concurrency,
  transaction, authorization, READY-last, lineage and data-loss-prevention branches.
- Without instrumentation, require focused executed tests and an explicit
  feature/source/test matrix; record the limitation without claiming numeric coverage.
- Branches, commits, PRs, merges and CI are traceability, not behavioral coverage.
  CI is required only when the increment or delivery workflow declares it.

## Manual verification result handoff

- Record agent-run required checks through [tools/check_result.py](tools/check_result.py)
  under the verification policy above. When the owner explicitly chooses manual
  verification, consume their recorded results without rerunning their checks.
  Prefer `python tools/check_result.py conclusion --increment <ID>` for status;
  inspect logs when necessary to diagnose failures within the authorized task.
- Distinguish agent-executed results from owner-supplied evidence. `PASS` does not
  imply owner attestation; `INCOMPLETE` stays `verification_pending`;
  `FAIL` cannot be recorded as completed. Read only relevant roadmap sections.
- Use [verify-increment](.agents/skills/verify-increment/SKILL.md) for increment
  verification or owner-reviewed gates. Attestation requires the complete passing
  gate, reconciled blast radius, attributable acceptance/safety coverage, an explicit
  impact/source/test matrix and an available coverage target/report in the gate.
- Bind attestation to the exact summary and required-check manifest using
  `tools/check_result.py attest`. It records `owner_verified`, cannot alone complete
  an increment, and replaces no missing checks, coverage, documentation, dependency,
  acceptance, deployment, provider or runtime evidence.
- Explicit owner review is required for attestation; a verifier ID is optional and
  must not block it. Omit attribution unless the owner supplies it. Existing named
  attestations remain valid; never fabricate a name or owner confirmation.

<a id="dataset-manifest-rule"></a>

## Contract and data guardrails

- Canonical migrated transport schemas: [libs/contracts/proto](libs/contracts/proto).
  Never hand-edit [generated output](libs/contracts/gen), reuse protobuf field
  numbers, or leave removed numbers/names unreserved.
- Review producers, consumers, schemas/configuration, tests and
  [Kafka contracts](docs/data/001-kafka-contracts.md) together.
- Kafka business messages carry logical dataset references, never physical paths;
  storage builders use [s3-paths.yaml](configs/shared/s3-paths.yaml).
- Producers write/validate Parquet; committed work proofs and eligible READY remain
  READY-last and preserve the previous accepted version on failure. The active
  PostgreSQL path does not read/publish retired `_metadata/metadata.json`; historical
  manifest publication code is compatibility/test evidence, not active authority.
- Dependencies require authoritative metadata and exact `dataVersion` lineage.
  `dependsOnDatasets` governs readiness/currentness; `dependsOnJobs` is traceability.
  Cron gaps never guarantee readiness; missing/stale inputs wait or defer dispatch.
- Owner-approved [PostgreSQL-only defaults](docs/reference/020-i10-postgres-only-cutover.md)
  retire legacy JSON/shadow comparison as active readiness authority. Runtime
  factories reject legacy/shadow selection and disabling the PostgreSQL reader. Missing
  PostgreSQL proof/API/bytes must fail or wait without JSON fallback. For current
  session-based operation, [TD-020](docs/technical-debt/020-session-runtime-operations.md)
  defers fleet-wide continuous rollout/recovery qualification and 12-hour schedule
  activation. Affected-scope inventory/proof/byte validation, matched services,
  write safety and bounded recovery remain required under [ADR-008](docs/adr/008-postgres-output-readiness.md).
  Follow the [identity handoff](docs/reference/007-p14-i10-work-identity.md)
  for approved NULL-provenance baselines and unresolved gates; additional durable
  worker recovery and tracing remain deferred.
- I10 READY covers exact registered canonical Parquet rows, not whole files.
  Whole-file snapshots are not enrollment prerequisites. Use canonical atomicWorkId
  including day/parameters, frozen upstream attempts and approved versions;
  physical dataset/partition identity cannot substitute for work identity.
  Missing-date EOD is not no-trade; registration/snapshot acceptance is not READY.
- Reusable Python belongs in [py-common](libs/py-common), language-neutral contracts
  in [contracts](libs/contracts), and app behavior in its owner. See
  [architecture](docs/architecture/001-system-overview.md),
  [data lake](docs/data/002-data-lake.md) and [flows](docs/flows).

## Repository boundaries

- Modify [externals](externals) only when explicitly working on that submodule.
- Never commit secrets or hard-code provider credentials/regions in handlers.
- Use existing Kafka/infrastructure ports and helpers; do not bypass them.
- Do not start long-running watchers without an explicit request.
- Operator job triggering remains deferred under
  [TD-017](docs/technical-debt/017-manual-request-rejection-audit.md); future approved
  entrypoints must reuse Platform claims, dependency guards, registered producers
  and outbox, without force/bypass or browser-to-Kafka paths.

## Plans and documentation

Follow the [plan standard](docs/governance/001-implementation-plan-standard.md).
Synchronize applicable canonical docs and agent guidance when architecture,
contracts, workflows or tooling change.

Before finalizing any increment, load
[update-implementation-plans](.agents/skills/update-implementation-plans/SKILL.md).
Review the canonical registry, numbered plan, root release notes, and affected
architecture, ADR, flow, data, deployment, development, service, index and guidance
docs. Update each applicable area or record an explicit no-update reason.

After documentation edits, run graph change detection and use
[verify-document-consistency](.agents/skills/verify-document-consistency/SKILL.md)
for a static audit. Missing, inconclusive or failed audits block documentation-sync
and increment-completion claims. Necessary document verification follows the
verification policy above; documentation work does not authorize unrelated suites,
deployment or retained-runtime changes.
