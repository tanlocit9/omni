package com.omni.platform.modules.scheduler.metadata;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;

/** A terminal result and its output evidence cross Kafka as one record. */
public record WorkerCompletionEvent(
        String kind,
        int schemaVersion,
        UUID eventId,
        UUID atomicAttemptId,
        JobStatusMessage status,
        OutputRegistrationEvent registration) {
    public static final String PROTOCOL = "WORKER_COMPLETION_V1";

    public WorkerCompletionEvent {
        OutputRegistrationEvent.require(
                PROTOCOL.equals(kind) && schemaVersion == 1, "Unsupported completion protocol");
        OutputRegistrationEvent.require(
                eventId != null && atomicAttemptId != null && status != null,
                "Missing completion identity/status");
        OutputRegistrationEvent.require(
                Set.of("SUCCESS", "ERROR").contains(status.status()),
                "Completion requires SUCCESS or ERROR");
        UUID.fromString(status.executionId());
        UUID.fromString(status.jobDefinitionId());
        if (status.parentExecutionId() != null) UUID.fromString(status.parentExecutionId());
        OutputRegistrationEvent.require(
                status.workType() != null
                        && status.workKey() != null
                        && !status.workKey().isBlank()
                        && status.startedAt() != null
                        && status.finishedAt() != null
                        && !status.finishedAt().isBefore(status.startedAt())
                        && status.durationMs() >= 0
                        && status.recordsProcessed() != null
                        && status.recordsProcessed() >= 0,
                "Invalid completion status");
        if (status.metaJson() != null) {
            for (String field : java.util.List.of("recordsProcessed", "recordsInserted")) {
                var value = status.metaJson().get(field);
                if (value == null || value instanceof Number) continue;
                OutputRegistrationEvent.require(
                        value instanceof String, "Invalid completion metadata counter");
                Integer.parseInt((String) value);
            }
        }
        if ("SUCCESS".equals(status.status())) {
            OutputRegistrationEvent.require(
                    registration != null && registration.registrations().size() == 1,
                    "Successful completion requires one registration");
            var item = registration.registrations().getFirst();
            OutputRegistrationEvent.require(
                    eventId.equals(registration.eventId())
                            && atomicAttemptId.equals(item.atomicAttemptId())
                            && status.executionId().equals(item.executionId().toString())
                            && status.workType() == item.workType()
                            && Objects.equals(status.workKey(), item.workKey()),
                    "Completion/registration identity mismatch");
        } else
            OutputRegistrationEvent.require(
                    registration == null, "Failed completion cannot register outputs");
    }

    public static void validateWire(tools.jackson.databind.JsonNode node) {
        OutputRegistrationEvent.require(
                node != null
                        && node.isObject()
                        && node.path("schemaVersion").isIntegralNumber()
                        && node.path("schemaVersion").canConvertToInt()
                        && node.path("schemaVersion").intValue() == 1,
                "Invalid completion envelope");
        for (String field : java.util.List.of("eventId", "atomicAttemptId"))
            requireUuid(node.get(field));
        var status = node.get("status");
        OutputRegistrationEvent.require(
                status != null && status.isObject(), "Missing completion status");
        for (String field : java.util.List.of("executionId", "jobDefinitionId"))
            requireUuid(status.get(field));
        if (status.hasNonNull("parentExecutionId")) requireUuid(status.get("parentExecutionId"));
        for (String field : java.util.List.of("recordsProcessed", "durationMs"))
            OutputRegistrationEvent.require(
                    status.path(field).isIntegralNumber() && status.path(field).canConvertToLong(),
                    "Invalid completion counter");
        if (node.hasNonNull("registration"))
            OutputRegistrationEvent.validateWire(node.get("registration"));
    }

    private static void requireUuid(tools.jackson.databind.JsonNode node) {
        OutputRegistrationEvent.require(
                node != null
                        && node.isString()
                        && node.asText()
                                .matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"),
                "Invalid completion UUID");
    }
}
