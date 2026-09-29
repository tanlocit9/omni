package com.omni.platform.modules.scheduler.dependencies;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition;

/** Immutable identity used to make one outbox eligibility decision. */
public record DependencyRequest(
        UUID outboxMessageId,
        UUID executionId,
        UUID parentExecutionId,
        JobDefinition jobDefinition,
        String workType,
        String workKey,
        String runKey,
        Map<String, Object> executionMetadata,
        Instant evaluatedAt) {

    public DependencyRequest {
        executionMetadata = executionMetadata == null ? Map.of() : Map.copyOf(executionMetadata);
    }
}
