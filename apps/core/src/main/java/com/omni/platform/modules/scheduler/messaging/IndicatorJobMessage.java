package com.omni.platform.modules.scheduler.messaging;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record IndicatorJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        String symbolKey,
        String timeframe,
        List<String> indicators,
        Map<String, Object> metadata) implements JobMessage {
}
