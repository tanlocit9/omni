# Telegram Notification Deduplication Technical Debt

## MVP Status

The 2026-09-05 full deferral is historical. P8-I5 in the [canonical increment registry](../../plans/roadmap/implementation-increments.md) owns durable enqueue/delivery identity, bounded retries, terminal `DEAD`, and operator visibility through the separate notification outbox described in [`docs/plans/022-notification-outbox.md`](../plans/022-notification-outbox.md). Relevant source is present, but P8-I5 remains `verification_pending`; this record does not claim completion. It retains cooldown-specific limitations and out-of-scope follow-ups and is not a competing schedule.

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

## Current Source Assessment

- **Current:** cooldown state and suppression counts are process-local and volatile.
- **Current:** cooldown admission occurs before Telegram delivery, so a failed retained
  request still consumes local admission state.
- **Current:** title normalization can collapse distinct incidents, and all notification
  types share the cooldown mechanism.
- **Current:** Kafka source-record failure suppression is a separate process-local guard
  with a different identity and lifetime.
- **Source present, verification pending:** notification-outbox retries and terminal
  `DEAD` handling exist in source but must not be described as completed P8-I5 evidence.
- **Evidence-dependent:** whether cooldown loss, false-positive suppression, or Telegram
  429 responses are operationally material requires runtime evidence.

## Recommended Actions

1. Keep durable accepted-notification delivery under P8-I5 and keep cooldown admission
   as a separate best-effort volume control.
2. Revisit distributed cooldown counters only when multiple replicas or exact suppression
   auditability becomes an approved requirement.
3. Narrow or type-scope normalization before changing it globally; add fixtures proving
   distinct incidents are not accidentally collapsed.
4. Reassess the source-record suppression guard separately if its process-local/global-
   clear behavior causes repeated operational notifications.
5. Preserve `verification_pending` wording until approved checks and exact-head CI are
   recorded for P8-I5.

## Retained Follow-up Triggers

Reassess cooldown-specific distributed counters or policy beyond P8-I5 when any of the following occurs:

- the platform routinely runs multiple notification-producing replicas;
- restart-related duplicate delivery or lost counts becomes operationally relevant;
- Telegram 429 responses persist after cooldown tuning;
- suppression summaries require auditability or exact counts;
- notification routing expands to channels requiring shared rate limits.

## Retained Options

P8-I5 selects the durable database/outbox-backed notification delivery policy for accepted notifications. A future atomic Redis-backed cooldown/counter operation with TTL is optional technical debt only if exact cross-replica suppression counters remain necessary after P8-I5. Any follow-up must preserve atomic first admission, repeat counting, cooldown rollover, bounded retention, and failure semantics.

## Contract Impact

- No Kafka topic, protobuf schema, dataset, manifest, storage path, or public API changes.
- The environment/configuration contract adds `TELEGRAM_DEDUPLICATION_COOLDOWN` and `TELEGRAM_DEDUPLICATION_MAX_CACHE_SIZE`.
- Telegram outbound `sendMessage` payloads add `disable_notification=true`.
- Rendered Telegram text may include an internal `Repeated notifications suppressed: N` summary.

## Repository Guidance Review

`AGENTS.md`, `CLAUDE.md`, and `.roo/rules/` were reviewed conceptually for synchronization. No update is required because this is a notification-local implementation decision, not a new repository-wide development rule or cross-service contract.
