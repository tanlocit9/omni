# Omni — Next Phase Implementation Plan

## Direction

The next phase has two parallel goals:

1. make the current backend/data pipeline trustworthy;
2. add visibility into Parquet datasets through a lightweight Internal Tools web app.

Do not add a backend JSON data-serving layer for Parquet in this phase.

## Execution Order

### Phase 1 — Backend correctness

- [ ] Fix `findJobsDue` active-job precedence.
- [ ] Align stock sync, symbol features, sector features, and Sector Transition to one configured sector universe.
- [ ] Collapse Sector Transition into one logical writer per shared Parquet output.
- [ ] Add regression tests for the above.

See: `BACKEND_CORE_STABILIZATION_IMPLEMENTATION_PLAN.md`.

### Phase 2 — Scheduler/data dependency hardening

- [ ] Add atomic scheduled-job claiming.
- [ ] Add lightweight dataset readiness/freshness checks using existing dependency metadata.
- [ ] Clean `workKey`/`workType` execution metadata.
- [ ] Tighten notification policy types.

### Phase 3 — Internal Tools foundation

Create:

```text
apps/internal-tools
```

Initial feature:

```text
Simple Parquet Viewer
```

Data flow:

```text
logical path
   -> public/presigned read URL
   -> DuckDB-Wasm
   -> HTTP range reads from Parquet
   -> Arrow batches
   -> React table
```

Frontend knows expected fields and paths, but physical Parquet schema remains discoverable at runtime.

See: `INTERNAL_TOOLS_PARQUET_VIEWER_IMPLEMENTATION_PLAN.md`.

### Phase 4 — Dataset catalog

After the viewer works with manually supplied paths:

- [ ] expose an allow-listed dataset catalog/path resolver;
- [ ] return metadata + readable URL only;
- [ ] include known dataset type, path, optional expected fields, last modified/size when cheap to retrieve;
- [ ] do not return dataset rows.

### Phase 5 — Internal Data Tool modules

Build dedicated views on top of the same Parquet query infrastructure:

- EOD;
- indicators;
- signals;
- symbol features;
- sector features;
- Sector Transition predictions/outcomes;
- job execution history.

## Architectural Rule

Parquet remains the analytical data contract.

```text
Storage       = Parquet
Query engine  = DuckDB / DuckDB-Wasm
UI transport  = URL/path + Arrow result batches
Core API      = metadata/auth/path resolution, not row serialization
```

Only introduce a server-side analytical query API later if browser limits, authorization granularity, joins across protected datasets, or dataset size prove that it is necessary.
