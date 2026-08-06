package com.omni.platform.modules.notifications.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SignalDigestNotificationEvent(
        UUID parentExecutionId,
        String jobTitle,
        String strategy,
        String timeframe,
        int totalChildren,
        int changedCount,
        List<SignalDigestItem> items,
        Map<String, Object> metadata) {
}
