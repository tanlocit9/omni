package com.omni.platform.modules.scheduler.messaging;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record IndicatorJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        String symbolKey,
        String timeframe,
        String indicatorSource,
        List<String> indicators,
        Map<String, Object> metadata) implements JobMessage {
}
