package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record SignalJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        String symbolKey,
        String timeframe,
        String strategy,
        Map<String, Object> metadata) implements JobMessage {
}
