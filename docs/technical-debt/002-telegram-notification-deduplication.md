# Telegram Notification Deduplication Technical Debt

## MVP Status

This debt is part of the owner-approved post-MVP deferral recorded in [`004-post-mvp-roadmap-work.md`](004-post-mvp-roadmap-work.md). The existing in-memory cooldown remains the MVP baseline. Distributed admission, durable counters, delivery retries, dead-letter outcomes, and rollout hardening must not be selected without a new owner decision or a safety-triggering production defect.

## Current Decision

The platform applies an in-memory cooldown before Telegram delivery. The key combines notification type, severity, and a normalized title. Retained messages are sent with Telegram's `disable_notification=true`, and the next retained message reports how many repeats were suppressed during the previous cooldown interval.

Silent delivery only suppresses client-side notification sound. It does not reduce Telegram Bot API request volume or prevent HTTP 429 responses; cooldown deduplication provides that request reduction.

## Known Limitations

| Limitation                                                       | Consequence                                                                                                                  |
| ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| State is local to one platform process                           | Replicas do not coordinate admission or aggregate counts, so each replica may deliver the same logical notification.         |
| State is volatile                                                | Restarting a process loses cooldown entries and accumulated suppression counts.                                              |
| Admission occurs before delivery                                 | A failed retained Telegram request consumes the cooldown, and any suppression count attached to that attempt is not durable. |
| Cache entries can be evicted at the configured bound             | Eviction can lose suppression counts and permit an earlier subsequent delivery.                                              |
| Title normalization replaces timestamps, UUIDs, and numeric runs | Distinct incidents may collapse into one key, producing false-positive suppression.                                          |
| All notification types are deduplicated                          | A legitimately repeated signal with the same normalized key can be suppressed during the cooldown.                           |
| No durable suppression summary exists                            | Counts cannot be recovered after restart, eviction, or a failed retained delivery.                                           |

## Related Source-Record Deduplication

`AbstractConsumer` separately records Kafka source coordinates and exception type to avoid repeatedly publishing a failure notification for the same consumed record. That source-record-level guard is intentionally not replaced by the Telegram cooldown:

- the consumer guard prevents duplicate publication for one Kafka record;
- the Telegram guard controls delivery volume across all notification producers;
- the keys, lifetimes, and ownership boundaries differ.

The two layers can therefore both apply without representing the same policy. The consumer guard remains lossy and process-local and should be revisited independently if its global-clear behavior becomes operationally significant.

## Migration Triggers

Move admission and counters to a distributed or persistent implementation when any of the following occurs:

- the platform routinely runs multiple notification-producing replicas;
- restart-related duplicate delivery or lost counts becomes operationally relevant;
- Telegram 429 responses persist after cooldown tuning;
- suppression summaries require auditability or exact counts;
- notification routing expands to channels requiring shared rate limits.

## Migration Options

Preferred options are an atomic Redis-backed cooldown/counter operation with TTL, or a durable database/outbox-backed notification delivery policy. Either option must preserve atomic first admission, repeat counting, cooldown rollover, bounded retention, and failure semantics.

## Contract Impact

- No Kafka topic, protobuf schema, dataset, manifest, storage path, or public API changes.
- The environment/configuration contract adds `TELEGRAM_DEDUPLICATION_COOLDOWN` and `TELEGRAM_DEDUPLICATION_MAX_CACHE_SIZE`.
- Telegram outbound `sendMessage` payloads add `disable_notification=true`.
- Rendered Telegram text may include an internal `Repeated notifications suppressed: N` summary.

## Repository Guidance Review

`AGENTS.md`, `CLAUDE.md`, and `.roo/rules/` were reviewed conceptually for synchronization. No update is required because this is a notification-local implementation decision, not a new repository-wide development rule or cross-service contract.
