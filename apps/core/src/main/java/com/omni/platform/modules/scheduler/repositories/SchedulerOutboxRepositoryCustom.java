package com.omni.platform.modules.scheduler.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SchedulerOutboxRepositoryCustom {

    List<UUID> findPendingCandidateIds(Instant now, int candidateLimit);

    SchedulerOutboxClaim claimEligible(
            UUID messageId, Instant now, String claimedBy, Duration leaseDuration);

    List<SchedulerOutboxClaim> claimPending(Instant now, String claimedBy, Duration leaseDuration, int batchSize);

    boolean markWaiting(UUID messageId, Instant availableAt, String reason);

    boolean markBlocked(UUID messageId, Instant blockedAt, String reason);

    boolean markPublished(UUID messageId, UUID claimToken, String claimedBy, Instant publishedAt);

    boolean markFailed(UUID messageId, UUID claimToken, String claimedBy, Instant availableAt, String error);
}
