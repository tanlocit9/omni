# Dataset-Component Market Dashboard Implementation Plan

Status: Planned Group C / Phase 6 increment P6-I4
Canonical status owner: [`plans/roadmap/README.md`](../../plans/roadmap/README.md)  
Target application: `apps/omni-console`  
Read boundary: `apps/query-service`

## Goal

Turn Omni Console from a data-operations interface into a useful market-intelligence entry point by making a market dashboard its default page and composing it from reusable components aligned to canonical logical datasets.

Each component owns presentation and bounded query behavior for one dataset. Pages compose those components without coupling the browser to object-storage paths, credentials, raw DuckDB connections, producer services, or scheduler internals.

## Outcome

After implementation:

- Omni Console opens on the Market Dashboard by default;
- the dashboard shows available market, sector, signal, and freshness information instead of only exposing computation and operational tools;
- widgets are organized by logical dataset rather than by producing service;
- every widget independently handles loading, ready, empty, stale, unavailable, and error states;
- every data widget exposes its effective data date and source `dataVersion` where available;
- Market, Symbol, Sector, and Operations pages reuse the same dataset components;
- Query Service provides bounded logical-dataset reads and chart-ready aggregates without exposing physical paths;
- unavailable or not-yet-implemented datasets produce honest component states rather than manufactured values;
- Dataset Explorer, SQL Console, and Jobs remain available as secondary tools.

## Non-Goals

- Do not add broker accounts, order placement, cash/securities ledgers, custody, KYC, or margin workflows.
- Do not make the browser read MinIO/S3 directly or receive credentials and physical paths.
- Do not implement intraday or realtime widgets before reliable Phase 9/10 contracts exist.
- Do not expose Sector Transition research as production investment advice.
- Do not add PostgreSQL or Redis copies of analytical dataset statistics.
- Do not add persisted dashboard layouts, user-owned SQL templates, or dashboard personalization.
- Do not create a generic plugin loader that executes arbitrary remote component or SQL definitions.

## User Journeys

### Market Discovery

```text
Open Omni Console
  -> see freshness and effective trading date
  -> inspect breadth, movers, sector strength, and recent signals
  -> select a sector or symbol
  -> open the corresponding detail page
```

### Symbol Research

```text
Select symbol
  -> identity and latest EOD state
  -> price/volume history
  -> indicator snapshot and overlays
  -> signal history and evaluation
  -> source freshness and versions
```

### Operational Diagnosis

```text
Notice stale/unavailable widget
  -> inspect dataset health
  -> inspect recent job execution
  -> use Dataset Explorer or Jobs without leaving Console
```

## Dataset Component Architecture

### Ownership Rule

Components align with stable logical datasets, not producer services. For example, `SectorHeatmap` belongs to `sector-features`; Analyzer is only the producer.

A dataset package owns:

- typed logical filters and result models;
- Query Service adapter calls;
- transformation into display models;
- dataset-specific components;
- loading, empty, stale, unavailable, and error behavior;
- freshness and provenance presentation;
- focused tests and fixtures.

A dataset package must not own:

- physical object paths or object-store credentials;
- unrestricted SQL entry;
- global page layout or navigation;
- cross-dataset business joins hidden inside visual components;
- producer-service orchestration.

### Target Frontend Structure

```text
apps/omni-console/src/
  dashboard/
    DashboardPage.tsx
    registry.ts
    types.ts
    layout/
    shared/
  datasets/
    metadata/
      api.ts
      types.ts
      components/
    symbols/
      api.ts
      types.ts
      components/
    eod/
      api.ts
      types.ts
      components/
    indicators/
      api.ts
      types.ts
      components/
    signals/
      api.ts
      types.ts
      components/
    signal-evaluations/
      api.ts
      types.ts
      components/
    symbol-features/
      api.ts
      types.ts
      components/
    sector-features/
      api.ts
      types.ts
      components/
    sector-rotation-backtests/
      api.ts
      types.ts
      components/
  pages/
    MarketPage.tsx
    SymbolPage.tsx
    SectorPage.tsx
    OperationsPage.tsx
```

Shared visual primitives may live under `dashboard/shared`, but dataset semantics stay in their dataset package.

### Widget Definition

Use an explicit compile-time registry:

```ts
type DatasetWidgetDefinition = {
  id: string;
  dataset: string;
  title: string;
  requiredPartitions: string[];
  supportedFilters: string[];
  defaultSize: 'small' | 'medium' | 'large';
  component: React.ComponentType<DatasetWidgetProps>;
};
```

Initial IDs:

```text
metadata.freshness
eod.market-breadth
eod.top-movers
sector-features.heatmap
sector-features.ranking
signals.latest
```

The registry is code-owned and allowlisted. Persisted configuration must not inject component modules, widget definitions, or arbitrary SQL.

## Component Inventory

| Logical source              | Initial components                              | Later components                             |
| --------------------------- | ----------------------------------------------- | -------------------------------------------- |
| Manifest/catalog metadata   | `DataFreshnessBanner`, `DatasetHealthSummary`   | `VersionLineage`, freshness history          |
| `symbols`                   | `SymbolDirectory`, `SymbolIdentity`             | exchange summary, sector membership explorer |
| `eod`                       | `MarketBreadth`, `TopMovers`, `LatestPriceCard` | price/volume chart, return distribution      |
| `indicators`                | `IndicatorSnapshot`                             | overlays, technical-condition table          |
| `signals`                   | `LatestSignalFeed`, `CurrentSignalCard`         | signal history/distribution                  |
| `signal-evaluations`        | none required for first dashboard               | strategy scorecard, forward-return chart     |
| `symbol-features`           | none required for first dashboard               | momentum leaders, contribution table         |
| `sector-features`           | `SectorHeatmap`, `SectorRanking`                | breadth, detail, symbol contributors         |
| `sector-rotation-backtests` | none required for first dashboard               | equity curve, metrics, rotation history      |
| Platform executions         | `PipelineStatusSummary` on Operations page      | blocked-job and failure trends               |

Research-only Sector Transition widgets use a separate experimental registry, an explicit research label, and no BUY/SELL recommendation wording unless separately approved.

## Default Page and Navigation

The immediate navigation change is intentionally small:

- initialize the current Console view to `dashboard` instead of `explorer`;
- mark Dashboard active on first render;
- render an honest dashboard landing state while dataset widgets are implemented;
- retain direct button navigation to Jobs, Dataset Explorer, and SQL Console;
- do not introduce a routing library solely for this change.

Before adding Symbol and Sector deep links, introduce URL-backed navigation with stable routes:

```text
/                         -> Market Dashboard
/symbols/:exchange/:code  -> Symbol detail
/sectors/:level/:code     -> Sector detail
/data                     -> Dataset Explorer
/sql                      -> SQL Console
/jobs                     -> Jobs
/operations               -> Data health and pipeline status
```

Browser refresh and back/forward behavior must be tested when URL routing is introduced.

## Dashboard V1 Composition

```text
DataFreshnessBanner

MarketBreadth          TopMovers
SectorHeatmap          SectorRanking
LatestSignalFeed
```

Responsive behavior:

- desktop uses a deliberate asymmetric grid with the sector map as the visual anchor;
- tablet reduces to two columns;
- mobile presents one ordered column with freshness first and signals after market/sector context;
- wide tables become bounded horizontal regions or mobile-specific cards;
- every chart provides a textual/table alternative for accessibility.

## Query Service Contract

### Existing Boundary

Query Service already provides:

- dataset catalog and partition metadata;
- logical dataset references;
- bounded asynchronous read-only SQL;
- JSON and Arrow results;
- source `dataVersions` where resolved by the existing source contract.

The first implementation reuses this boundary through code-owned query adapters. The browser must not let widget users edit the SQL. Dashboard reads do not require a new logical READY-read contract: explicit dashboard endpoints or server-owned query definitions may read supported existing datasets and must return truthful availability, effective-date, and provenance metadata.

### Dashboard Query Direction

Prefer explicit, bounded dashboard request models or server-owned query definitions when semantics stabilize:

```text
GET /v1/dashboard/market-summary?date=<date>&exchange=<exchange>
GET /v1/dashboard/top-movers?date=<date>&exchange=<exchange>&limit=<n>
GET /v1/dashboard/sectors?date=<date>&timeframe=<timeframe>
GET /v1/dashboard/signals?date=<date>&strategy=<strategy>&limit=<n>
```

Responses include:

```text
effectiveDataDate
generatedAt
dataVersions
rows or metric payload
truncated/unavailable indicators
```

Until those endpoints exist, frontend adapters may submit fixed allowlisted SQL through the existing logical query API for datasets supported by that API. SQL remains colocated with the dataset adapter, uses bounded row limits, references only declared aliases, and has contract tests. A dataset that is not supported by the current logical resolver requires an explicit bounded Query Service endpoint or server-owned query definition; it must not be made available by exposing physical paths to the browser.

### Cross-Dataset Composition

Pages compose independent widgets rather than requiring one all-data endpoint. A failure in `signals` must not hide valid `eod` or `sector-features` components.

When an atomic cross-dataset comparison is necessary, Query Service must:

- resolve an explicit common effective date;
- expose every input `dataVersion`;
- reject or label mixed-date results;
- keep row, time, memory, and concurrency bounds.

## Date, Freshness, and Empty-State Semantics

Each component distinguishes:

| State       | Meaning                                                         | Presentation                           |
| ----------- | --------------------------------------------------------------- | -------------------------------------- |
| Loading     | Request is active                                               | stable skeleton preserving layout      |
| Ready       | Valid rows for effective date                                   | visualization plus provenance          |
| Empty       | Valid query returned no rows                                    | neutral no-data explanation            |
| Stale       | Data is valid but older than policy                             | visible date/freshness warning         |
| Unavailable | The supported source contract cannot provide the requested data | explain prerequisite; do not show zero |
| Error       | Request or transformation failed                                | bounded error and retry action         |

The dashboard-level date is the latest common complete date for widgets that must be compared. Independent widgets may show a newer date only when the difference is explicit.

## Dataset Outputs

No new analytical dataset output is required for V1. The dashboard reads existing canonical datasets.

If a widget needs an expensive reusable aggregate that cannot meet Query Service bounds, stop and define that aggregate as a separate Analyzer dataset increment with ownership, path, schema, manifest, lineage, and readiness semantics. Do not hide material precomputation inside Query Service.

## Metadata Outputs

No new object-storage metadata contract is required. Components consume metadata exposed by their supported Query Service contracts and display, where available:

- dataset and partition identity;
- status;
- effective/min/max dates where available;
- `generatedAt`;
- `dataVersion`;
- row/object/byte information where relevant.

## Algorithm Feature Outputs

No new algorithm feature is required. V1 visualizes existing EOD, indicator, signal, evaluation, symbol-feature, and sector-feature outputs.

Presentation-only derived values, such as display percentages or rank deltas, must be deterministic and must not be represented as persisted analytical features.

## Algorithms Unlocked

No new trading algorithm is unlocked. The dashboard makes existing analytical outputs inspectable and comparable, which improves human validation, anomaly detection, and prioritization of later feature work.

## Contract Impact

| Contract                          | Impact                                                                                                                 |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf | No change. Dashboard reads persisted datasets and Platform HTTP state.                                                 |
| Object-storage JSON manifest      | No schema change and no new dashboard-specific READY-read contract. Existing metadata may be consumed where available. |
| Storage path/dataset ownership    | No change. Logical dataset references remain canonical; paths remain server-side.                                      |
| Public Java/Python API            | Query Service may gain bounded dashboard response models/endpoints. No producer API change in V1.                      |
| Configuration/environment         | Existing Query Service and Platform origins remain. No new secret is exposed to the browser.                           |
| Frontend navigation               | Dashboard becomes the initial view; URL routes are introduced in a later navigation increment.                         |

## Security and Reliability

- Keep Query Service private or behind the approved identity-aware boundary.
- Never return object-store credentials or unrestricted physical paths.
- Use server-owned or code-owned bounded queries; do not interpolate raw user input into SQL.
- Allowlist filter fields, sort fields, datasets, and maximum result sizes.
- Treat titles, symbol names, reason text, and metadata as untrusted display content.
- Do not present stale, partial, research, or mixed-date outputs as current production advice.
- A failed widget must remain isolated from other widgets and navigation.
- Avoid identifiers such as symbol or data version as unbounded metric labels.

## Implementation Increments

Implementation status: Increment 1 source complete; Increment 2 foundation complete except reusable fixtures; Increment 3 source complete pending approved automated and manual verification.

### Increment 1 - Default Dashboard Shell

1. [x] Change the initial Console view from Dataset Explorer to Dashboard.
2. [x] Move Dashboard before operator tools in visual navigation.
3. [x] Replace the persistence-dependent placeholder wording with the fixed dataset-component delivery direction.
4. [x] Add an accessible heading and explicit current navigation state.
5. [x] Add tests for first render and switching back to Dataset Explorer, SQL Console, and Jobs.

Exit criterion: opening Console shows Dashboard as the active section without breaking existing tools.

### Increment 2 - Dataset Component Foundation

1. [x] Add shared widget types and explicit registry.
2. [x] Add shared frame, provenance, loading, empty, stale, unavailable, and error components.
3. [x] Create initial `metadata`, `eod`, `sector-features`, and `signals` component packages.
4. [x] Add request cancellation, manual per-widget refresh, and per-widget error isolation for connected adapters.
5. [-] Add focused ready, stale, empty, unavailable, and error state coverage; reusable data fixtures remain pending.

Exit criterion: registered dataset widgets render independently with consistent state semantics.

### Increment 3 - Freshness and Market Components

1. [x] Implement `DataFreshnessBanner` from Query Service metadata responses.
2. [x] Implement `MarketBreadth` from EOD data with explicit exchange/date scope.
3. [x] Implement `TopMovers` with uppercase symbols, separate deterministic gainers/losers lists, and allowlisted HOSE/HNX/UPCOM plus Top 5/10/20 selectors.
4. [x] Expose effective date and `dataVersions` on every result.
5. [x] Add semantic/query contract tests; execution remains pending approval.

Exit criterion: the default page provides useful, truthful EOD market context.

### Increment 4 - Sector Components

1. Implement `SectorHeatmap` from `sector-features`.
2. Implement `SectorRanking` with rank, breadth, coverage, and relative strength.
3. Add sector selection and contributor drill-down only where source contracts support it.
4. Clearly label unavailable and research-only fields.

Exit criterion: users can identify strong/weak sectors and inspect the source date/version.

### Increment 5 - Signal Components

1. Implement `LatestSignalFeed` from `signals`.
2. Show symbol, strategy, timeframe, transition, score, reason codes, and effective date when present.
3. Add filters with allowlisted values and bounded result sizes.
4. Keep signal outputs descriptive and link them to source/evaluation context.

Exit criterion: users can inspect recent signal transitions without reading raw Parquet or SQL.

### Increment 6 - Detail Pages and URL Navigation

1. Add stable URL-backed Market, Symbol, Sector, Data, SQL, Jobs, and Operations routes.
2. Compose Symbol page from `symbols`, `eod`, `indicators`, `signals`, and evaluations.
3. Compose Sector page from sector features, contributors, and approved backtest context.
4. Preserve filter/deep-link state across refresh and back/forward navigation.
5. Add route-level loading/error boundaries and not-found behavior.

Exit criterion: dashboard drill-down produces shareable, refresh-safe detail views.

### Increment 7 - Integration and Hardening

1. Validate all registered widget definitions and configurations at construction time.
2. Reject unknown widgets, arbitrary SQL, invalid filters, and oversized requests.
3. Keep the dashboard composition and defaults code-owned.
4. Complete cross-widget accessibility, responsive, partial-failure, and performance checks.

Exit criterion: the fixed dashboard is reliable and cannot bypass query, security, or component boundaries.

## Testing Strategy

### Frontend Unit and Component Tests

- Dashboard is the initial active view.
- Existing navigation remains usable.
- Registry rejects duplicate/unknown IDs through construction and validation.
- Every widget covers loading, ready, empty, stale, unavailable, and error states.
- Provenance displays effective date and source version.
- Filters are validated and encoded without SQL interpolation.
- Independent widget failure does not hide sibling widgets.
- Every widget frame exposes an accessible refresh action; connected widgets cancel and reissue their request, while refresh is disabled during loading or before an adapter exists.
- Desktop and mobile layouts retain logical reading order.

### Query Contract Tests

- Generic query API requests use logical dataset aliases only; explicit dashboard endpoints keep physical resolution server-side.
- Date, exchange, strategy, timeframe, sorting, and limit values are allowlisted/bounded; Top Movers accepts only the UI's fixed 5/10/20 choices even though the server retains a hard defensive maximum.
- Results expose `dataVersions` and truthful truncation state.
- Mixed-date inputs are rejected or explicitly labeled.
- Missing or unsupported source data returns unavailable semantics rather than fabricated zero values.
- Timeout/cancellation and memory/row limits remain enforced.

### End-to-End Checks

- Opening `/` shows Dashboard.
- Dataset Explorer, SQL Console, and Jobs remain reachable.
- Market widgets render from representative supported-source fixtures.
- Clicking supported symbol/sector entries reaches the correct detail route after routing is introduced.
- Refresh and browser back/forward retain the selected page.
- One failed data source leaves other widgets usable.

## Verification

Status: not run. Increments 1-3 are implemented locally as described above, but the required approved automated and manual checks have not run.

Required checks during implementation, subject to explicit user approval under repository policy:

```text
nx run omni-console:lint
nx run omni-console:typecheck
nx run omni-console:test
nx run omni-console:build
nx run query-service:lint
nx run query-service:test
nx affected -t lint,test,build
```

Confirm actual target names with `nx show project` before execution. Query Service checks are required only when its code/contracts change.

Manual checks:

- desktop, tablet, and mobile layout;
- first-load Dashboard selection;
- keyboard navigation and visible focus;
- chart textual alternatives and color contrast;
- stale/unavailable/error behavior;
- provenance and effective-date clarity.

## Repository Guidance Updates

Review during implementation:

- `docs/README.md` - index this plan and dashboard ownership.
- `plans/roadmap/README.md` - identify this plan as canonical supporting detail for P6-I4.
- `plans/roadmap/phase-6-omni-console.md` - keep the fixed Market Dashboard scope and status synchronized.
- `plans/omni-metadata-console-dashboard-execution-plan.md` - update dashboard sequencing and gates.
- `apps/omni-console/README.md` - document default page, component ownership, and navigation.
- `apps/query-service/README.md` - document dashboard endpoints/query definitions when introduced.
- `docs/architecture/001-system-overview.md` - update only if service boundaries change.
- `AGENTS.md`, `CLAUDE.md`, and workspace rules - review; no change is expected for the default-view-only increment because architecture and tool rules remain unchanged.

## Risks and Mitigations

| Risk                                      | Impact                           | Mitigation                                                                                                       |
| ----------------------------------------- | -------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Dashboard shows inconsistent dates        | Misleading comparisons           | Resolve common effective dates and display widget dates explicitly                                               |
| Widget SQL spreads through UI             | Contract drift and security risk | Keep fixed queries in typed dataset adapters; migrate stable semantics server-side                               |
| Large Parquet scans make landing slow     | Poor first-load experience       | Bounded aggregates, cancellation, Arrow where justified, and precompute only through explicit dataset increments |
| One source breaks the whole page          | Dashboard unavailable            | Per-widget loading/error boundaries and independent requests                                                     |
| Missing data displayed as zero            | False market signal              | Distinct unavailable and empty states                                                                            |
| Research outputs appear authoritative     | Product/legal risk               | Separate experimental registry and explicit labels                                                               |
| Saved layouts become a plugin/SQL bypass  | Security and reliability risk    | Allowlisted widget IDs and validated configuration only                                                          |
| Operator tools dominate investor workflow | Weak product experience          | Make Market Dashboard default; keep operations as secondary navigation                                           |

## Acceptance Criteria

- [x] Dashboard is the default Omni Console page and is marked as the active navigation section on first render.
- [x] Dataset Explorer, SQL Console, and Jobs remain reachable in component coverage and retain their existing source behavior.
- [x] Initial widgets are organized by canonical logical dataset, not producer service.
- [x] An explicit allowlisted widget registry defines supported components.
- [-] Dashboard V1 includes implemented freshness, market breadth, and top movers; sector heatmap/ranking and latest signals remain unavailable pending their increments.
- [ ] Every widget handles loading, ready, empty, stale, unavailable, and error states independently.
- [-] Effective date and source versions are visible for implemented metadata/EOD widgets; later analytical widgets remain pending.
- [ ] The browser receives neither object-store credentials nor unrestricted physical paths.
- [-] Implemented metadata/EOD reads are fixed, allowlisted, bounded, cancellable, and covered by source tests; approved test execution remains pending.
- [ ] Mixed-date or partial data is rejected or visibly labeled.
- [ ] Sector Transition research is not presented as production investment advice.
- [ ] Mobile and desktop layouts are usable and accessible.
- [ ] Required approved checks pass before the implementation is marked Done.
- [ ] No persisted layout, user-owned SQL template, personalization store, or dashboard-specific READY-read contract is required.
- [ ] Roadmap, focused execution plan, service READMEs, and repository guidance are synchronized.

## Definition of Done

```text
default dashboard navigation complete
+ dataset component architecture complete
+ initial market/sector/signal widgets complete
+ truthful freshness and provenance complete
+ bounded Query Service contracts complete
+ responsive/accessibility checks complete
+ approved automated and manual verification complete
+ roadmap/docs/guidance synchronized
```
