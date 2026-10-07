# Deferred Observability, Capacity, and Realtime

## Decision

Owner decision — 2026-10-07: the active product slice is truthful Job Operations, bounded EOD Data Health with Query Service memory caching, and a small fixed Market Review. Cross-service logging rollout, throughput expansion, and realtime are deferred backlog, not the next automatic phases.

Type: deferred feature / operational hardening. Status: DEFERRED. Priority: conditional; correctness findings are separate from feature expansion.

## Retained design references

| Scope | Reference | Trigger for promotion |
| --- | --- | --- |
| P11 correlation and centralized logging | [Plan 024](../plans/024-polyglot-correlation-structured-logging.md) | Existing logs cannot reconstruct a measured recurring cross-service failure; approve the smallest diagnostic slice. |
| P12 concurrency, bulk status, independent writer | [Plan 027](../plans/027-concurrent-workers-and-writer-batching.md), [TD-009](009-python-kafka-worker-throughput-and-offset-safety.md) | P13-I1 representative baseline identifies a sustained processing/write bottleneck, with offset correctness and writer exclusivity proven first. |
| P10 realtime discovery/live runtime | [Plan 014](../plans/014-realtime-per-tick.md) | Approved realtime user need and actual provider capability; no inferred WebSocket or live access. |
| Raw SQL, broad/custom dashboards, extra providers, HA, tooling | [TD-004](004-post-mvp-roadmap-work.md) | A concrete usage/deployment requirement beyond the fixed operator dashboards. |
| Date-range backfill | [Plan 028](../plans/028-reusable-date-range-backfill.md) | Data Health demonstrates historical gaps worth repairing and a separately approved safe repair increment. |

Existing source, completed milestones, and historical verification remain intact. Phase 10/11/12 registry IDs retain their implementation/evidence states, but their execution mode is owner-gated; they are not autonomous active work. Proposed dependency order is retained as design context only.

## Safety exception

Offset ownership, startup-record discard, false terminal status, provider truncation, and identity risks are correctness/security items. They may be promoted as focused fixes on evidence without first implementing all P11 logging, P12 concurrency, or P10 live transport. Deferral does not authorize unsafe acknowledgments, data loss, or exposure of development identity.

## Promotion and completion

Promote one bounded slice into the canonical registry with owner, evidence, acceptance criteria, rollout/rollback, and cross-service impact. Do not reactivate the whole backlog. No new runtime or completion evidence is claimed by this record.

Refs: [priority index](README.md), [Mermaid source](priority-order.mmd), [increment registry](../plans/roadmap/implementation-increments.md).
