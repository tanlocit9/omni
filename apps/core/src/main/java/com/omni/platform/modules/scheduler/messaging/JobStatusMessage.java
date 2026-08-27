package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.Map;

import com.omni.platform.shared.executions.WorkType;

public record JobStatusMessage(
        String jobDefinitionId,
        String executionId,
        String parentExecutionId,
        WorkType workType,
        String workKey,
        String status,
        Map<String, Object> metaJson,
        String newOffset,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String errorMessage,
        Integer recordsProcessed) {
}
