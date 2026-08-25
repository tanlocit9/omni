package com.omni.platform.modules.scheduler.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobDefinitionClaimRepository {

    List<SchedulerClaim> claimDueJobs(Instant now, String claimedBy, Duration leaseDuration, int batchSize);

    Optional<SchedulerClaim> claimJobDefinition(
            UUID jobDefinitionId,
            Instant now,
            String claimedBy,
            Duration leaseDuration);

    boolean releaseClaim(SchedulerClaim claim);

    boolean releaseClaim(UUID jobDefinitionId, UUID claimToken, String claimedBy);
}
