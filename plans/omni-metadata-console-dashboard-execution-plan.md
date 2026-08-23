# Omni Metadata, Dataset Explorer, Parquet Viewer, and Dashboard Execution Plan

Status: Canonical execution plan  
Source baseline: `main@19055b3731b45934fd85cb28dda5a8636a2ed4a0`  
Target application: `apps/omni-console`  
Execution order: Query Service → Dataset Explorer/Viewer → SQL Console → Dashboard → Force Precompute date fix

## Goal

Keep the completed metadata foundation, add a private server-side Query Service,
build Omni Console Dataset Explorer/Viewer and SQL Console on that boundary, add
Saved Query-backed dashboards, and finally correct Force Precompute effective-date
semantics.

Partially implemented source is evidence, not proof of completion. Every milestone uses the control loop below and blocks later milestones until its gate passes.

## Outcome

After completion:

- EOD and indicator datasets publish canonical JSON manifests with deterministic identity, exact persisted-byte metadata, READY-last safety, and exact upstream lineage.
- Query Service resolves logical dataset, partition, and version identity through READY manifests and keeps physical Parquet paths server-side.
- `apps/omni-console` provides Dataset Explorer, Parquet Viewer, and Data Health Dashboard features; canonical Phase 7 later adds a Jobs tab on a Platform-owned operational API.
- Native DuckDB performs bounded server-side projection, filtering, sorting, SQL, and row limiting without exposing object-store credentials or physical paths.
- Force Precompute distinguishes requested date from the latest common complete effective data date and preserves truthful execution states.
- Roadmaps, canonical documentation, and repository guidance agree with verified source and test evidence.

## Locked Decisions

1. Persisted dataset metadata remains JSON in S3-compatible object storage.
2. PostgreSQL and Redis do not duplicate dataset statistics in V1.
3. Publication order is `write data → validate → calculate metadata/version → immutable manifest → READY last`.
4. Failed writes preserve the previous valid READY pointer.
5. Readiness checks use manifests rather than full Parquet-prefix scans when a manifest exists.
6. Reusable Python manifest/storage abstractions belong in `libs/py-common`.
7. The product is Omni Console at `apps/omni-console`; Parquet Viewer is a feature.
8. `apps/query-service` is the private analytical read boundary and accepts only logical dataset/partition/version references.
9. The browser never receives object-store credentials or physical object URLs.
10. Native DuckDB runs server-side; JSON is limited to small results and Arrow IPC is used for larger result sets.
11. Unknown additive Parquet columns remain inspectable.
12. Dataset or sector states from different trading dates are never silently mixed.
13. Unrelated local changes are preserved. Dirty worktrees are never destructively reset or rebased.
14. Proto3 migration, dependency-guard expansion, portable deployment, realtime ingestion, and AI/ML are not prerequisites for this sequence.
15. Phase 7 job operations is a follow-on capability: Platform owns definition catalog, trigger validation, scheduler dispatch, idempotency, audit, and execution status; Query Service remains read-only and the Console does not publish directly to Kafka.

## Dataset Outputs

| Milestone | Dataset effect                                                                                          |
| --------- | ------------------------------------------------------------------------------------------------------- |
| M1        | No new analytical dataset; verifies the shared manifest contract.                                       |
| M2        | Verifies canonical metadata publication for the existing EOD dataset.                                   |
| M2A       | Repairs indicator metadata so its READY identity includes exact EOD lineage and persisted output bytes. |
| M3–M6     | No analytical dataset output; read-only application and API behavior only.                              |
| M7        | Existing analytical outputs are keyed by `effectiveDataDate`; no new dataset family is required.        |

## Metadata Outputs

Canonical paths:

```text
_metadata/datasets/<dataset>/<partition_path>/versions/<dataVersion>.json
_metadata/datasets/<dataset>/<partition_path>/READY.json
```

An empty partition uses `_default`. Required identity/readiness fields include manifest version, dataset, normalized partition, status, logical path, deterministic `dataVersion`, persisted object statistics, schema identity, lineage inputs, and generation timestamp. `generatedAt` is excluded from deterministic identity.

M7 records requested date, effective data date, resolution policy, fallback reason, and exact consumed input versions in execution and/or manifest metadata without weakening deterministic identity.

## Algorithm Feature Outputs

- `DIRECT`: canonical schema, row/object/byte statistics, data range, source execution identity, exact upstream lineage.
- `DERIVED`: readiness, freshness, stale/missing classification, lineage currentness, common complete effective date.
- `DIRECT`: requested/effective-date and fallback-resolution observability for Force Precompute.

## Algorithms Unlocked

- Reproducible downstream computation based on exact input versions.
- Metadata-only freshness and health diagnostics.
- Safe bounded ad hoc inspection of physical Parquet output.
- Deterministic as-of analysis without mixed-sector trading dates.

## Contract Impact

| Contract                          | Impact                                                                                                                                                            |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf | No planned schema change. If implementation discovers one, stop and create a producer/consumer migration plan.                                                    |
| Object-storage JSON manifest      | Audited in M1; EOD verified in M2; indicator lineage and failure semantics repaired in M2A; date observability may be extended additively in M7.                  |
| Storage path/dataset ownership    | Canonical metadata paths are verified; physical data paths remain resolved by shared path configuration.                                                          |
| Public Java/Python API            | Shared manifest APIs and Java reader compatibility are verified; M7 may add a reusable date-resolution abstraction after impact analysis.                         |
| Configuration/environment         | M3 defines Platform/Console metadata and access-resolver URLs; scheduled date policy is explicit in M7.                                                           |
| Platform HTTP API                 | Read-only catalog, manifest, execution-status, and allow-listed short-lived data-access resolution contracts are introduced or verified before Viewer completion. |

## Repository Guidance Updates

Review and synchronize, when applicable:

- `AGENTS.md`
- `CLAUDE.md`
- `.roo/rules/`
- `docs/README.md`
- `docs/data/data-lake.md`
- `docs/flows/job-execution.md`
- `docs/flows/sector-wave.md`
- affected service READMEs
- roadmap and supporting plans listed in M8

Keep guidance concise and link to canonical documentation rather than duplicating this plan.

## Milestone Control Loop

Run before each milestone and before declaring it complete.

### Inspect

1. Capture branch, HEAD, upstream, merge base, status, diff scope, and latest commit.
2. Inspect available Nx projects and each affected project's targets before invoking them.
3. Use code-review-graph `detect_changes` for local changes.
4. Use semantic graph search before manually locating unfamiliar implementations.
5. Use impact-radius analysis before changing manifests, DTOs, storage paths, configuration, or shared/public abstractions.
6. Inspect producer and consumer sides of every affected contract.

### Classify local changes

| Category                 | Action                           |
| ------------------------ | -------------------------------- |
| Complete and correct     | Preserve and build on it.        |
| Correct but incomplete   | Finish in the current milestone. |
| Incorrect or conflicting | Repair before adding behavior.   |
| Unrelated user work      | Preserve; do not edit or stage.  |
| Ambiguous                | Stop for owner decision.         |

Record the inventory in the milestone report; do not create a competing permanent roadmap.

### Establish baseline

Use `nx show project <project>` before checks. Run operations through defined Nx targets. Separate pre-existing failures from failures attributable to the milestone. A check that cannot run is BLOCKED, never PASS.

### Decide

- Continue only when every acceptance criterion and required check passes.
- Repair attributable failures immediately and repeat the gate.
- Stop on ambiguous ownership, contract, path, access, or destructive migration decisions.
- Defer non-critical debt only with explicit owner acceptance and a tracked follow-up.

### Required evidence

- inspected files and local diff scope;
- implemented behavior;
- tests added or updated;
- exact commands and results;
- risks or accepted debt;
- next eligible milestone.

## Temporary Scope Quarantine — Dependency Guard

Until M6 passes:

- do not add dependency conditions, enforcement modes, retries, or database behavior;
- run Platform scheduler tests as regression gates;
- verify DOCUMENTATION_ONLY jobs proceed with warnings;
- verify ENFORCED behavior does not block bootstrap/metadata generation accidentally;
- treat correctness defects as focused blockers, not Phase 4 expansion;
- treat `plans/job-dependency-guard-progress.md` as stale until M8 reconciliation;
- keep BLOCKED distinct from FAILED in APIs and UI.

## M0 — Reconcile Local Work

### Goal

Establish a safe, evidence-based starting point against `main@19055b3`.

### Tasks

- Capture branch, HEAD, upstream, merge base, dirty state, full diff, and Nx projects.
- Fetch/read `origin/main`; if local work predates the baseline, classify overlap before merge or rebase.
- Inventory metadata/storage, Ingestor, Analyzer, Platform, Console/internal-tools, DuckDB, tests, Nx, and docs changes.
- Compare source and WIP with Phase 3, Phase 6, metadata, this plan, repository rules, and the implementation-plan standard.
- Detect `internal-tools` naming drift without blind renames.
- Detect duplicate Python/Java manifest contracts and preserve only canonical ownership after impact analysis.
- Run available unchanged baseline checks, including Platform scheduler regressions.

### Gate

Every local change is classified; unrelated work is untouched; pre-existing failures are separated; local state is safely based on/reconciled with the baseline; no duplicate manifest contract exists; the earliest incomplete milestone is selected from evidence.

## M1 — Audit Dataset Metadata Contract

### Goal

Verify and repair the merged canonical implementation rather than replacing it.

### Tasks

- Verify typed models, canonical dataset/partition/path/schema/input normalization, deterministic schema/data hashes, and `generatedAt` exclusion.
- Verify immutable version and READY paths, `_default`, explicit read errors, READY-last publication, and prior-READY preservation.
- Verify required/nullable field semantics, unsupported versions, additive JSON compatibility, and lineage fixtures.
- Verify Python writer/reader and Java reader use identical fixtures, paths, status, versions, and null/additive-field behavior.
- Reconcile documentation and remove legacy `<partition>.json` assumptions.
- Refactor mixed responsibilities only with preserved coverage and shared ownership.

### Required tests

Model validation/round trip; partition canonicalization; deterministic schema/data hashes; lineage ordering; unsupported/invalid READY rejection; failed rewrite preservation; supported MinIO/S3 read/write behavior; Python/Java fixture compatibility.

### Verification

Inspect targets, then run at minimum `py-common:lint`, `py-common:test`, and `py-common:build` through Nx plus relevant Platform compatibility tests.

### Gate

One deterministic JSON/path contract exists; READY-last safety is tested; no metadata duplicate store exists; docs match implementation; Python and Java agree; fresh evidence is recorded.

## M2 — Verify First Real EOD Publication

### Goal

Prove the merged EOD producer before UI code relies on it.

### Tasks

- Verify producer and physical path ownership.
- Validate partition, row/schema/date range, exact persisted checksums/bytes, schema hash, and data version.
- Verify immutable/READY publication, catalog visibility, retry idempotency, failed-rewrite safety, reusable UI/Java fixtures, and historical-read behavior.

### Required tests

Successful READY-last publication; no READY after failed validation; unchanged retry identity; changed output identity; manifest/Parquet statistic equality; resolvable catalog entry; prior READY preserved after failure.

### Verification

Run `py-common:test`, Ingestor lint/test/integration targets, and a build target when defined.

### Gate

A real EOD partition is discoverable and readable through metadata without prefix scanning; physical statistics, failure behavior, and retries are proven.

## M2A — Repair Indicator Manifest Publication

### Goal

Make derived indicator metadata canonical and truthful before UI consumption.

### Tasks

- Read READY for the exact consumed EOD partition and record its partition/dataVersion in `inputs`.
- Pass exact checksum and byte length from the persisted indicator Parquet write.
- Ensure indicator identity changes with output bytes/schema or EOD input version.
- Do not report full SUCCESS when required READY publication fails; preserve previous READY and return an actionable typed outcome.
- Keep hashing/path/publication behavior in shared abstractions.
- Verify indicator catalog visibility and partition shape.

### Required tests

Exact EOD lineage; exact persisted identity; deterministic same-input output; changed-upstream identity; failure preserves READY and cannot report false success; missing EOD READY maps to typed readiness/BLOCKED where supported; additive consumer compatibility.

### Verification

Run py-common tests, Analyzer lint/test/build, and Platform tests through existing Nx targets.

### Gate

Indicator manifests contain exact EOD lineage and persisted-byte identity; publication failure is truthful; Explorer can show EOD → Indicators lineage.

## M3 — Query Service and Omni Console Foundation

### Goal

Establish the approved private read boundary and a stable application shell.

### Tasks

- Define/test Query Service APIs for catalog, READY partitions, query status,
  cancellation, JSON results, and Arrow IPC results.
- Resolve only authorized logical dataset/partition/version references; keep
  credentials and physical object paths inside Query Service.
- Reject arbitrary URLs, dataset/version substitution, write operations,
  dangerous functions, and unsupported identities.
- Confirm `apps/omni-console`; safely migrate classified legacy WIP.
- Pin React/TypeScript/Vite/Nx-compatible versions from the workspace rather than `latest`.
- Add `/dashboard`, `/datasets`, `/datasets/:dataset`, `/datasets/:dataset/partitions/:partition`, and `/query` routes.
- Add layout, navigation, error boundary, loading/empty states, bounded TanStack Query retries, environment validation, and EOD/indicator fixtures.
- Define thin Console API adapters and query result/error types.

### Required tests

API read-only/version/limit/cancel tests; route/navigation states; fixture parsing;
unsupported-version presentation; environment validation; frontend bundle
inspection for credentials and physical paths.

### Verification

Inspect and run Platform checks plus `omni-console` lint/test/build through actual Nx targets.

### Gate

Query Service private read boundary is tested; Console is in the Nx graph;
production build and routes pass; no object-store credential or physical path is
bundled; later features do not require shell/access redesign.

## M4 — Dataset Explorer

### Goal

Browse canonical datasets and partitions using metadata only.

### Tasks

- Add catalog search/filter, dataset summaries, partition selection, manifest detail, schema, freshness, lineage, source execution identity, and status warnings.
- Distinguish missing, invalid, unsupported, non-READY, stale, and blocked states.
- Link to Viewer by dataset/partition/version identity, never an arbitrary URL.
- Avoid prefix scans and dataset-specific generic-column assumptions.

### Required tests

Catalog navigation/filtering; partition selection; lineage; value/date formatting; all error states; additive-field tolerance; identity-preserving drill-down.

### Gate

Explorer loads the real EOD/indicator metadata through Platform; summaries require no Parquet scan; errors are actionable; Viewer receives canonical identity.

## M5 — Parquet Viewer and SQL Console

### Goal

Run bounded server-side native DuckDB queries through logical READY dataset views.

### Tasks

- Register logical views from READY manifests immediately before execution.
- Enforce projection, typed filters, sorting, default ~200 rows, and a hard 5,000-row result limit in DuckDB.
- Compare physical and manifest schemas; distinguish missing, additive, type, version, and freshness mismatches.
- Allow only read-only SQL, reject arbitrary URLs/functions, cancel stale queries,
  release connections/results, and audit consumed data versions.
- Use JSON for small Viewer results and Arrow IPC for SQL Console results.

### Required tests

DuckDB initialization; logical resolution; arbitrary URL/write SQL rejection; safe
SQL generation; hard limits; schema warnings; unknown columns;
cancellation/cleanup; JSON and Arrow contracts.

### Gate

A real READY dataset opens; query controls hold; no credentials or physical paths
leak; schema drift is visible; production assets load.

## M6 — Data Health Dashboard V1

### Goal

Provide metadata- and execution-backed operational health without a duplicate statistics store.

### Tasks

- Aggregate READY, STALE, MISSING/BLOCKED, and invalid states; freshness distribution; stale datasets; recent updates; lineage currentness; rows/bytes/objects.
- Show recent execution SUCCESS/BLOCKED/FAILED distinctly when Platform data is available.
- Link every diagnostic to dataset, partition, Viewer, or execution evidence.
- Degrade usefully during partial API failure and on empty installations.
- Keep market analytics widgets outside V1 unless their manifests passed all gates.

### Required tests

Fixture aggregation; status distinction; Asia/Ho_Chi_Minh freshness; links; partial failure; empty state; responsive breakpoints.

### Gate

Dashboard uses canonical sources, keeps BLOCKED distinct, drills into evidence, and remains useful with partial source availability. M7 cannot begin before this gate passes.

## M7 — Force Precompute Effective Data Date

### Goal

Resolve morning force-runs to one latest common complete session without false dates, mixed sectors, or false errors.

### Semantics

```text
requestedDate     = user/scheduler request
effectiveDataDate = latest common complete trading session consumed
generatedAt       = operational timestamp
```

Policies:

- `STRICT`: require the requested session or return BLOCKED.
- `LATEST_COMPLETE_SESSION`: latest common READY session at or before requested date.
- Manual force-run defaults to `LATEST_COMPLETE_SESSION`; scheduled behavior is explicit configuration.

### Tasks

- Add a reusable shared as-of resolver after graph search and impact analysis.
- Resolve from READY manifests and trading sessions, never wall clock alone or latest row per sector.
- Distinguish not-yet-ready, partial universe, no common complete session, invalid data, and storage failure.
- Produce explicit SUCCESS, fallback-success, NO_OP, BLOCKED, and FAILED semantics.
- Record requested/effective date, policy, reason, and input versions; key analytical outputs by effective date.
- Preserve READY on BLOCKED/FAILED; suppress ERROR notifications for expected BLOCKED/fallback/no-op; show details in Dashboard.
- Make retries idempotent by effective date plus input-version identity.

### Required tests

20 Aug request resolving to 19 Aug; partial 20 Aug resolving to latest common date; no mixed dates; no common date BLOCKED; STRICT incomplete BLOCKED; truthful evaluation date/metadata; idempotent identity; READY preservation; notification semantics; Dashboard rendering.

### Gate

Resolution is deterministic; all consumers use one common effective date; dates and statuses remain truthful end to end.

## M8 — Integration, Documentation, and Roadmap Reconciliation

### Goal

Prove the complete slice and remove contradictory guidance.

### End-to-end scenarios

1. Ingestor writes Parquet and publishes READY metadata.
2. Explorer discovers the dataset/partition.
3. Viewer runs a bounded query via a short-lived resolved URL.
4. Dashboard reports READY and links to evidence.
5. Broken/stale metadata appears as a health issue.
6. Morning force-run resolves to the previous common complete session.
7. Missing prerequisites produce BLOCKED without READY replacement or ERROR notification.

### Documentation reconciliation

Review/update:

- `plans/roadmap/README.md`
- `plans/roadmap/implementation-increments.md`
- `plans/roadmap/phase-3-dataset-manifests.md`
- `plans/roadmap/phase-6-omni-console.md`
- `plans/consolidated-numbered-implementation-phases.md`
- `plans/job-dependency-guard-progress.md`
- `docs/DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`
- the superseded internal-tools Viewer plans, as compatibility pointers
- `docs/NEXT_PHASE_IMPLEMENTATION_PLAN.md`
- `docs/data/data-lake.md`
- `docs/flows/job-execution.md`
- `docs/flows/sector-wave.md`
- `docs/README.md`
- `AGENTS.md`, `CLAUDE.md`, and `.roo/rules/`

### Verification

- Run lint/test/build/format through actual Nx targets for every changed project.
- Run contract/schema validation and relevant end-to-end tests.
- Run affected checks against `origin/main` after target inspection.
- Run code-review-graph `detect_changes`; recheck impact radius for changed shared contracts.
- Inspect final status/diff and confirm unrelated files were neither modified nor staged.
- Do not commit, push, or create a PR without separate authorization.

### Gate

All gates pass or have explicit accepted tracked debt; canonical plans/docs/guidance agree; exact blockers are reported; final capability, risk, and next milestone are identified.

## Milestone Report Template

```markdown
## Milestone <ID> — <Name>

Status: PASS | FIXING | BLOCKED | ACCEPTED_DEBT

### Local state reviewed

- Base/HEAD:
- Changed files:
- Unrelated files preserved:

### Completed

- ...

### Verification

| Command | Result            | Evidence/notes |
| ------- | ----------------- | -------------- |
| `...`   | PASS/FAIL/BLOCKED | ...            |

### Acceptance criteria

- [x] ...
- [ ] ...

### Risks/debt

- ...

### Decision

- Continue to Mx / repeat current gate / stop for owner decision.
```

## Stop Conditions

Stop for owner decision when local ownership is ambiguous; competing contracts cannot be resolved; destructive migration/history rewrite is required; public access/authentication assumptions must change; arbitrary SQL/URL exposure would be needed; a cross-service change lacks migration planning; credentials/authority are unavailable; or the next milestone would start while the current gate fails.

## Verification Summary

Each milestone defines targeted Nx and contract checks. M8 adds affected and end-to-end checks. Source presence never substitutes for fresh local/CI verification, and unavailable checks are reported as blockers.

## Acceptance Criteria

- [ ] Local WIP is reconciled safely against the baseline.
- [ ] Canonical metadata and EOD READY publication are freshly verified.
- [ ] Indicator metadata has exact EOD lineage and truthful publication status.
- [ ] Query Service logical READY resolution and server-side read-only SQL boundary are verified.
- [ ] Dataset Explorer, Parquet Viewer, and Dashboard gates pass.
- [ ] Force Precompute effective-date behavior is deterministic and truthful.
- [ ] Cross-project checks and end-to-end scenarios pass or exact blockers are accepted.
- [ ] Canonical docs, roadmaps, and repository guidance are synchronized.

## Definition of Done

```text
local WIP reconciled
+ canonical metadata verified
+ EOD publication verified
+ indicator lineage/status repaired
+ private Platform access boundary verified
+ Dataset Explorer verified
+ Parquet Viewer verified
+ Data Health Dashboard verified
+ Force Precompute date semantics verified
+ cross-project checks verified
+ canonical plans/docs/guidance synchronized
```
