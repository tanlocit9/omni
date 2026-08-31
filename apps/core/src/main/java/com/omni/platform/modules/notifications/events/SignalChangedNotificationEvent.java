package com.omni.platform.modules.notifications.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SignalChangedNotificationEvent(
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
        Instant createdAt,
        Map<String, Object> metadata,
        String deliveryIdentity) {

    public SignalChangedNotificationEvent(
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
            Instant createdAt,
            Map<String, Object> metadata) {
        this(executionId, parentExecutionId, symbolKey, previousSignal, newSignal, price, signalDate,
                reasonCodes, score, strategy, timeframe, createdAt, metadata, null);
    }
}
