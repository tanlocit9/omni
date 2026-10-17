package com.omni.platform.modules.scheduler.repositories;

import java.util.Objects;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

/** Raw bounded aggregate returned without loading scheduler-outbox payloads. */
public record SchedulerBacklogGroup(
        UUID jobDefinitionId,
        DataSource source,
        JobType jobType,
        String cronExpression,
        long pending,
        long dispatchable,
        long retryDelayed,
        long inFlight) {

    public SchedulerBacklogGroup {
        Objects.requireNonNull(jobDefinitionId, "jobDefinitionId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(cronExpression, "cronExpression");
        if (pending < 0 || dispatchable < 0 || retryDelayed < 0 || inFlight < 0) {
            throw new IllegalArgumentException("Backlog counts must not be negative");
        }
    }
}
