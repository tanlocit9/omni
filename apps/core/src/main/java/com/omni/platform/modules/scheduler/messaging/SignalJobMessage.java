package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public record SignalJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        String symbolKey,
        String timeframe,
        String strategy,
        Map<String, Object> metadata) implements JobMessage {
}
