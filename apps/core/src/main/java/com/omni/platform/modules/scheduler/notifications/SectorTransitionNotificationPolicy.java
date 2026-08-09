package com.omni.platform.modules.scheduler.notifications;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;

@Component
public class SectorTransitionNotificationPolicy implements JobNotificationPolicy {

    private final DefaultJobNotificationPolicy defaultPolicy;

    public SectorTransitionNotificationPolicy(DefaultJobNotificationPolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public JobType getJobType() {
        return JobType.SECTOR_TRANSITION_ANALYZE;
    }

    @Override
    public Optional<Object> buildNotification(JobNotificationContext context) {
        if (context.execution().getStatus() != JobStatus.FAILED) {
            return defaultPolicy.buildNotification(context);
        }

        Map<String, Object> metadata = mergedMetadata(context);
        String message = String.join("\n",
                "Focus: " + displayValue(metadata, "focusSectorCodes", displayValue(metadata, "sectorCodes", "UNKNOWN")),
                "Evaluation date: " + displayValue(metadata, "evaluationDate", "UNKNOWN"),
                "Timeframe: " + displayValue(metadata, "timeframe", "UNKNOWN"),
                "Strategy: " + displayValue(metadata, "strategy", "UNKNOWN"),
                "Horizons: " + horizons(metadata.get("predictionHorizons")),
                "",
                "Reason:",
                reason(context, metadata),
                "",
                "Progress: " + context.success() + " succeeded / " + context.failed() + " failed");

        return Optional.of(new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Sector Transition analysis failed",
                message,
                Map.of()));
    }

    private Map<String, Object> mergedMetadata(JobNotificationContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context.execution().getMetaJson() != null) {
            metadata.putAll(context.execution().getMetaJson());
        }
        if (context.children() != null) {
            context.children().stream()
                    .filter(child -> child.getStatus() == JobStatus.FAILED || child.getStatus() == JobStatus.ERROR)
                    .findFirst()
                    .ifPresent(child -> {
                        if (child.getMetaJson() != null) {
                            metadata.putAll(child.getMetaJson());
                        }
                    });
        }
        metadata.put("total", context.total());
        metadata.put("success", context.success());
        metadata.put("failed", context.failed());
        return metadata;
    }

    private String reason(JobNotificationContext context, Map<String, Object> metadata) {
        String analyzerError = displayValue(metadata, "errorMessage", "");
        if (!analyzerError.isBlank()) {
            return analyzerError;
        }
        String executionError = context.execution().getError();
        if (executionError != null && !executionError.isBlank()) {
            return executionError;
        }
        return "Sector Transition analysis failed";
    }

    private String displayValue(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    private String horizons(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "UNKNOWN";
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(item -> "T" + item)
                    .collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }
}
