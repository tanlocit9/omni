package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record SymbolJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        String symbolKey,
        Instant fromOffset,
        Instant toOffset,
        Map<String, Object> metadata) implements JobMessage {
}
