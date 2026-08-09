package com.omni.platform.modules.scheduler.notifications;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.events.SignalDigestItem;
import com.omni.platform.modules.notifications.events.SignalDigestNotificationEvent;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.shared.utils.MetadataUtils;

@Component
public class SignalDigestNotificationPolicy implements JobNotificationPolicy {

    private final DefaultJobNotificationPolicy defaultPolicy;

    public SignalDigestNotificationPolicy(DefaultJobNotificationPolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public JobType getJobType() {
        return JobType.SYNC_SIGNALS;
    }

    @Override
    public Optional<Object> buildNotification(JobNotificationContext context) {
        JobExecutionHistory parent = context.execution();
        if (!context.hasChildren() || parent.getStatus() != JobStatus.SUCCESS) {
            return defaultPolicy.buildNotification(context);
        }

        List<SignalDigestItem> changedItems = context.children().stream()
                .filter(child -> child.getStatus() == JobStatus.SUCCESS)
                .filter(child -> isSignalChanged(child.getMetaJson()))
                .map(this::toSignalDigestItem)
                .toList();
        if (changedItems.isEmpty()) {
            return defaultPolicy.buildNotification(context);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        putAllAsStrings(metadata, parent.getMetaJson());
        if (parent.getJob() != null) {
            MetadataUtils.putIfPresent(metadata, "jobDefinitionId", parent.getJob().getId());
            MetadataUtils.putIfPresent(metadata, "jobType", parent.getJob().getJobType());
        }

        return Optional.of(new SignalDigestNotificationEvent(
                parent.getId(),
                jobTitle(parent),
                firstNonBlank(changedItems.stream().map(SignalDigestItem::strategy).toList()),
                firstNonBlank(changedItems.stream().map(SignalDigestItem::timeframe).toList()),
                context.children().size(),
                changedItems.size(),
                changedItems,
                metadata));
    }

    private SignalDigestItem toSignalDigestItem(JobExecutionHistory child) {
        Map<String, Object> metadata = child.getMetaJson() == null ? Map.of() : child.getMetaJson();
        return new SignalDigestItem(
                stringValue(metadata.get("symbolKey")),
                stringValue(metadata.get("previousSignal")),
                stringValue(metadata.get("newSignal")),
                stringValue(metadata.get("price")),
                stringValue(metadata.get("signalDate")),
                stringValue(metadata.get("strategy")),
                stringValue(metadata.get("timeframe")),
                stringValue(metadata.get("score")),
                parseReasonCodes(metadata.get("reasonCodes")));
    }

    private boolean isSignalChanged(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        return Boolean.parseBoolean(stringValue(metadata.get("signalChanged")));
    }

    private List<String> parseReasonCodes(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .toList();
        }
        return List.of(String.valueOf(value));
    }

    private String firstNonBlank(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("UNKNOWN");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String jobTitle(JobExecutionHistory execution) {
        JobDefinition job = execution.getJob();
        if (job == null) {
            return String.valueOf(execution.getId());
        }
        if (job.getTitle() != null && !job.getTitle().isBlank()) {
            return job.getTitle();
        }
        return String.valueOf(job.getId());
    }

    private void putAllAsStrings(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }

        source.forEach((key, value) -> MetadataUtils.putIfPresent(target, key, value));
    }
}
