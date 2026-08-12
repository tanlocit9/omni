package com.omni.platform.modules.scheduler.repositories;

import java.time.Instant;
import java.util.UUID;

public record SchedulerClaim(
        UUID jobDefinitionId,
        UUID claimToken,
        String claimedBy,
        Instant claimedAt,
        Instant claimUntil,
        Instant scheduledFor) {
}
