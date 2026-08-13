package com.omni.platform.modules.scheduler.repositories;

import java.util.UUID;

public record SchedulerOutboxClaim(
        UUID messageId,
        UUID claimToken,
        String claimedBy,
        UUID executionId,
        String topic,
        String key,
        String payload,
        int attempts) {
}

