package com.omni.platform.modules.scheduler.dtos;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JobOperationsDtos {
    private JobOperationsDtos() {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }

    public record ExecutionSummary(
            UUID id,
            String status,
            Instant triggeredAt,
            Instant startedAt,
            Instant finishedAt,
            Integer recordsSynced,
            Integer recordsSkipped,
            String error) {
    }

    public record DependencySummary(
            List<String> jobs,
            List<String> datasets,
            List<String> produces) {
    }

    public record JobDefinitionSummary(
            UUID id,
            String title,
            String source,
            String jobType,
            String workType,
            String workKey,
            boolean active,
            String cronExpression,
            Instant nextRun,
            boolean triggerable,
            String triggerBlockReason,
            ExecutionSummary lastExecution) {
    }

    public record JobDefinitionDetail(
            UUID id,
            String title,
            String source,
            String jobType,
            String workType,
            String workKey,
            boolean active,
            String cronExpression,
            Instant nextRun,
            boolean triggerable,
            String triggerBlockReason,
            ExecutionSummary lastExecution,
            DependencySummary dependencies,
            List<String> acceptedTriggerParameters,
            List<ExecutionSummary> recentExecutions) {
    }

    public record ManualTriggerRequest(
            String idempotencyKey,
            String reason,
            Map<String, Object> parameters) {
    }

    public record ManualTriggerResponse(
            UUID requestId,
            UUID definitionId,
            UUID executionId,
            String state,
            boolean duplicate,
            String blockReason,
            String error,
            Instant requestedAt,
            Instant resolvedAt) {
    }

    public record TriggerStatusResponse(
            ManualTriggerResponse trigger,
            ExecutionSummary execution) {
    }
}
