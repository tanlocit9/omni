package com.omni.platform.modules.scheduler.notifications;

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
