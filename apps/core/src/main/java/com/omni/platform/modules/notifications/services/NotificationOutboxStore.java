package com.omni.platform.modules.notifications.services;

import java.time.Duration;
import java.time.Instant;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxClaim;
import com.omni.platform.shared.outbox.ClaimableOutboxStore;

public interface NotificationOutboxStore extends ClaimableOutboxStore<NotificationOutboxClaim> {

    NotificationRequest decode(NotificationOutboxClaim claim);

    boolean markDead(NotificationOutboxClaim claim, Instant failedAt, String sanitizedError);

    boolean acquireProviderPermit(Provider provider, Instant now, Duration interval);
}
