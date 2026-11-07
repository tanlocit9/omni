# Runtime fixes - 2026-11-07

This scoped P14-I10 commit contains recent execution-lifecycle, date-merge and
weekend-envelope repairs. It excludes pending backfill API, guard/catalog changes,
migrations, backlog tooling and their documentation. P14-I10 remains in_progress.

## Defects and behavior

- Mixed Parquet calendar dates and pandas Timestamp results previously failed
  sorting. Normalize the sort key while preserving historical rows and versions.
- Prepared members previously appeared RUNNING before a worker received them.
  Prepare as PENDING; acknowledge RUNNING before invoking the handler.
- A failed target previously closed its request while healthy siblings remained
  unfinished, revoking their dispatch admission. Wait for every member to settle
  before resolving the request to SUCCESS or FAILED. Keep terminal requests immutable.
- Request inspection now reports member status counts, unfinished members,
  settlement, failure presence, timestamps and outbox status.
- Current Symbols snapshots now accept Saturday/Sunday, matching existing Platform
  expansion. EOD/Indicators weekday, canonical identity and exact-date guards remain.

## Source and regression evidence

| Boundary | Regression coverage |
| --- | --- |
| Shared storage write_day; Analyzer | Timestamp/calendar-date merge, historical preservation, actual Parquet round trips |
| Platform member preparation and reconciliation | PENDING without start time; worker RUNNING receipt; terminal immutability; healthy sibling and unfinished ancestor settlement |
| Shared messaging and Kafka; Ingestor | RUNNING receipt and terminal timestamp contracts; no command commit for RUNNING-only acknowledgements |
| Shared atomic envelope; Ingestor message model | Both weekend snapshot days accepted; wrong target/hash and weekend market work rejected |

Existing local runs on the combined working tree: lifecycle Platform 744 passed/5
skipped; latest py-common 420 passed/5 skipped, Analyzer 268 passed, Ingestor 100
passed; forced Kafka integration 2/2 passed. Atomic-envelope coverage was 94.32%
line/90.38% branch; Ingestor message models 97.30%/83.33%. These results include
pending changes excluded from this commit. The isolated commit has not been tested;
the owner explicitly requested no additional verification. No complete I10 gate,
numeric coverage for the whole increment, CI or deployed-runtime pass is claimed.
Backfill-only fixture assertions remain with the pending feature.

## Impact and documentation review

Platform changes member lifecycle and inspection; Ingestor and shared Python change
worker receipts, status validation and date handling. Analyzer uses shared behavior
and gains merge regressions. Query Service and Console have no source changes in
this commit. Existing contracts already define RUNNING; no protobuf generation or
new Kafka field is required. Persistence keeps its current schema; no migration,
storage-path, dataset-ownership, configuration or deployment change is included.
READY-last, exact frozen lineage and metadata ownership remain unchanged.

Plan 030, the increment registry, release notes and architecture/ADR/flow/data/
deployment/development/service/index/agent guidance were reviewed for this bounded
commit. No additional scheduling, ownership, activation or completion decision is
introduced, so their pending changes remain outside this commit. This note records
the scoped behavior and evidence without folding those unrelated edits into it.
Graph analysis is bounded and covers a larger dirty tree; it does not establish
isolation or executed coverage. Static source/document review is not a test run.

## Operations and limitations

Restart the relevant services to load fixes. Failed business executions do not
automatically retry; a new backfill request key is required. Infrastructure replay
can execute a message again. The earlier Platform DevTools startup failure was not
reproduced or fixed. No live restart, replay, cleanup, migration or push was performed.
