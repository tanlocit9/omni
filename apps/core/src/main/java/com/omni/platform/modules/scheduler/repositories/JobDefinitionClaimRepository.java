package com.omni.platform.modules.scheduler.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface JobDefinitionClaimRepository {

    List<SchedulerClaim> claimDueJobs(Instant now, String claimedBy, Duration leaseDuration, int batchSize);

    boolean releaseClaim(SchedulerClaim claim);

    boolean releaseClaim(java.util.UUID jobDefinitionId, java.util.UUID claimToken, String claimedBy);
}
