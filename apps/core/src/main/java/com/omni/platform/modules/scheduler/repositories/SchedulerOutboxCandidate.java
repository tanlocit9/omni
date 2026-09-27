package com.omni.platform.modules.scheduler.repositories;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition;

/** Unclaimed immutable snapshot used for dependency evaluation outside a transaction. */
public record SchedulerOutboxCandidate(
        UUID messageId,
        UUID executionId,
        UUID parentExecutionId,
        JobDefinition jobDefinition,
        String workType,
        String workKey,
        String runKey,
        Map<String, Object> executionMetadata,
        Instant availableAt) {

    public SchedulerOutboxCandidate {
        executionMetadata = executionMetadata == null ? Map.of() : Map.copyOf(executionMetadata);
    }
}
