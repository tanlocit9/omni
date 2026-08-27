package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record SyncSymbolsJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        String exchange,
        Instant timestamp,
        Map<String, Object> metadata) implements JobMessage {
}
