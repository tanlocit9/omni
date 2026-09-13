package com.omni.platform.modules.notifications.dtos;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SignalChangedNotificationMessage(
        String type,
        UUID executionId,
        UUID parentExecutionId,
        String symbolKey,
        String previousSignal,
        String newSignal,
        Object price,
        String signalDate,
        List<String> reasonCodes,
        Object score,
        String strategy,
        String timeframe,
        boolean signalChanged,
        Boolean newSignalDate,
        Instant createdAt,
        Map<String, Object> metadata) {

    public boolean isNewSignalDate() {
        return Boolean.TRUE.equals(newSignalDate);
    }
}
