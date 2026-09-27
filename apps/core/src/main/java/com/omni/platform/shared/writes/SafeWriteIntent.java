package com.omni.platform.shared.writes;

import java.time.Instant;

/**
 * Mechanics-only contract for durable writers. Dependency eligibility is not part
 * of this type and remains owned by Platform scheduler outbox dispatch.
 *
 * <p>The immutable identity fences retries to one logical output. Implementations
 * must acquire a lease before writing, use an atomic conditional state transition,
 * bound retries, make completion idempotent, and acknowledge input only after the
 * output and completion state are durable.
 */
public record SafeWriteIntent(
        String intentId,
        String outputIdentity,
        String claimToken,
        Instant leaseUntil,
        int attempt,
        int maxAttempts) {

    public SafeWriteIntent {
        if (intentId == null || intentId.isBlank()
                || outputIdentity == null || outputIdentity.isBlank()
                || claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("Safe-write identity and fencing token are required");
        }
        if (leaseUntil == null || attempt < 1 || maxAttempts < attempt) {
            throw new IllegalArgumentException("Safe-write lease and bounded attempt are invalid");
        }
    }
}
