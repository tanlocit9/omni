package com.omni.platform.modules.scheduler.messaging;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.EnvelopedMessage;

/** Additive atomic command envelope; its original business message is flattened. */
public record ExpandedJobMessage(
        @JsonUnwrapped JobMessage command,
        String jobType,
        LocalDate businessDate,
        Map<String, Object> parameters,
        String atomicWorkId)
        implements JobMessage, EnvelopedMessage {
    @Override
    @JsonIgnore
    public Object body() {
        return command;
    }

    @Override
    @JsonIgnore
    public Map<String, Object> additionalFields() {
        return Map.of(
                "jobType",
                jobType,
                "businessDate",
                businessDate.toString(),
                "parameters",
                parameters,
                "atomicWorkId",
                atomicWorkId);
    }

    @Override
    @JsonIgnore
    public UUID jobDefinitionId() {
        return command.jobDefinitionId();
    }

    @Override
    @JsonIgnore
    public UUID executionId() {
        return command.executionId();
    }

    @Override
    @JsonIgnore
    public UUID parentExecutionId() {
        return command.parentExecutionId();
    }

    @Override
    @JsonIgnore
    public String source() {
        return command.source();
    }

    @Override
    @JsonIgnore
    public WorkType workType() {
        return command.workType();
    }

    @Override
    @JsonIgnore
    public String workKey() {
        return command.workKey();
    }

    @Override
    @JsonIgnore
    public Map<String, Object> metadata() {
        return command.metadata();
    }
}
