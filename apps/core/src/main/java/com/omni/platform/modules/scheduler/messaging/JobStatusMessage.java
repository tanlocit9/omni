package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.Map;

public record JobStatusMessage(
        String jobDefinitionId,
        String executionId,
        String parentExecutionId,
        String symbolKey,
        String status,
        Map<String, Object> metaJson,
        String newOffset,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String errorMessage) {
}
