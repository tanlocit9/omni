package com.omni.platform.shared.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface ClaimableOutboxStore<C> {

    List<C> claimPending(Instant now, String instanceId, Duration lease, int batchSize);

    boolean markDelivered(C claim, Instant deliveredAt);

    boolean scheduleRetry(C claim, Instant retryAt, String sanitizedError);
}
