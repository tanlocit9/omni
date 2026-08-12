package com.omni.platform.modules.scheduler.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SchedulerOutboxRepositoryCustom {

    List<SchedulerOutboxClaim> claimPending(Instant now, String claimedBy, Duration leaseDuration, int batchSize);

    boolean markPublished(UUID messageId, UUID claimToken, String claimedBy, Instant publishedAt);

    boolean markFailed(UUID messageId, UUID claimToken, String claimedBy, Instant availableAt, String error);
}
