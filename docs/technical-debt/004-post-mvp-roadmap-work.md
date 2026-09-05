# Post-MVP Roadmap Work

## Decision

On 2026-09-05, the owner narrowed the active MVP to the existing daily/EOD pipeline, usable Telegram operational and signal notifications, and basic operator controls.

Work that primarily adds migration machinery, advanced metadata, deployment hardening, Console/query polish, delivery hardening, intraday processing, or realtime processing is deferred. It must not be selected by roadmap automation until the owner explicitly promotes it back into the canonical increment registry.

This is prioritization debt, not a claim that the work has no long-term value. Existing implementations and verification evidence remain valid historical evidence even when their roadmap increment is superseded for MVP scheduling.

## Deferred Increments

| Area                            | Deferred increments        | Reason for deferral                                                                                                                                                                                                                         |
| ------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Proto3 migration                | P2-I2, P2-I3               | Generated adapters, cross-language migration, dual-read operation, and cutover do not add immediate MVP user value while the current daily/EOD boundary remains usable.                                                                     |
| Advanced manifests and metadata | P3-I1, P3-I2, P3-I3, P3-I5 | Generalized manifest infrastructure, migration, and reconciliation are post-MVP platform hardening. P3-I4 remains completed because its date-contract correction already protects the active EOD pipeline.                                  |
| Portable deployment hardening   | P5-I1, P5-I2, P5-I3        | Image hardening, cloud/storage profiles, backup rehearsal, and immutable publication are deferred until an MVP deployment target is selected.                                                                                               |
| Console and query polish        | P6-I1, P6-I2, P6-I3, P6-I4 | Dataset exploration, SQL tooling, Arrow workflows, and dashboard work are outside the basic operator-control MVP. Existing merged source is retained but is not an active completion priority.                                              |
| Telegram delivery hardening     | P8-I3                      | Distributed idempotency, retries, dead-letter outcomes, advanced observability, and controlled live rollout are deferred. Existing cooldown, exception isolation, destination routing, and mocked payload coverage remain the MVP baseline. |
| Intraday EOD                    | P9-I1, P9-I2, P9-I3        | Higher-frequency post-close datasets and features are outside the daily/EOD MVP.                                                                                                                                                            |
| Realtime per tick               | P10-I1, P10-I2             | Tick ingestion and live processing are outside the daily/EOD MVP.                                                                                                                                                                           |

## Retained MVP Scope

The active MVP keeps:

- the existing daily/EOD ingestion and analysis pipeline;
- correctness work already protecting scheduler, execution identity, date contracts, and canonical sector processing;
- Phase 7 basic job catalog, safe trigger, and execution visibility;
- P8-I1 operational/generic Telegram formats;
- P8-I2 immediate/digest signal formats.

Completed increments remain completed. P1-I3, P8-I1, and P8-I2 may finish evidence reconciliation because their implementations directly support the retained MVP. Deferred increments must not block those MVP evidence gates solely because of historical dependency links.

## Existing Safety Baseline

Deferring hardening does not authorize removing existing safeguards. Preserve current validation, transaction boundaries, exception isolation, cooldown deduplication, destination isolation, secret handling, bounded payloads, and tests. A defect affecting correctness, data loss, credentials, or unsafe operation remains MVP work rather than technical debt.

## Reactivation Triggers

Reassess deferred work when one of these becomes true:

- the MVP requires a second independently deployed producer or consumer boundary;
- current JSON contracts cause compatibility or ownership failures;
- multiple replicas make process-local notification admission materially incorrect;
- a concrete deployment target requires image, storage, backup, or recovery guarantees;
- operators need dataset exploration, arbitrary read-only SQL, or dashboard workflows beyond basic job controls;
- intraday or realtime data becomes an approved product requirement;
- observed production failures show that a deferred control is required for safe operation.

Reactivation requires an owner decision, refreshed dependencies and acceptance criteria, and a new or restored canonical roadmap increment. Do not treat this document as authorization to implement deferred work autonomously.
