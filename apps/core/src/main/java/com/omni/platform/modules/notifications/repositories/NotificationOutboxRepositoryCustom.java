package com.omni.platform.modules.notifications.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationOutboxRepositoryCustom {

    List<NotificationOutboxClaim> claimPending(
            Instant now,
            String claimedBy,
            Duration leaseDuration,
            int batchSize);

    boolean markSent(UUID messageId, UUID claimToken, String claimedBy, Instant sentAt);

    boolean markRetry(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant availableAt,
            String sanitizedError);

    boolean markDead(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant failedAt,
            String sanitizedError);

    boolean acquireProviderPermit(String provider, Instant now, Duration interval);
}
