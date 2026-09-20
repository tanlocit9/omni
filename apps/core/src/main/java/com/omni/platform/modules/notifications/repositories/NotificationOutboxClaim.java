package com.omni.platform.modules.notifications.repositories;

import java.util.UUID;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;

public record NotificationOutboxClaim(
        UUID messageId,
        UUID claimToken,
        String claimedBy,
        Provider provider,
        NotificationChannel channel,
        NotificationKind kind,
        int schemaVersion,
        String payload,
        int attempts) {
}
