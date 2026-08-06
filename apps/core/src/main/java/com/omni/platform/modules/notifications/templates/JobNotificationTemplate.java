package com.omni.platform.modules.notifications.templates;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.shared.utils.MetadataUtils;

/**
 * Builds channel-neutral operational notification events for scheduler job executions.
 * <p>
 * This class owns wording, severity, and stable metadata keys for job-level
 * notifications only. It intentionally avoids Telegram-specific concerns such as
 * chat ids, Bot API calls, HTML markup, or parse-mode escaping; those remain the
 * responsibility of notification delivery adapters.
 */
@Component
public class JobNotificationTemplate {

    /**
     * Builds the notification emitted when a standalone execution first reaches a
     * successful terminal state.
     *
     * @param execution persisted standalone execution that completed successfully
     * @return channel-neutral operational notification event
     */
    public OperationalNotificationEvent standaloneSucceeded(JobExecutionHistory execution) {
        return new OperationalNotificationEvent(
                NotificationSeverity.INFO,
                "Job completed: " + jobTitle(execution),
                "Job completed successfully",
                baseMetadata(execution));
    }

    /**
     * Builds the notification emitted when a standalone execution first reaches a
     * failed terminal state.
     *
     * @param execution persisted standalone execution that failed
     * @return channel-neutral operational notification event
     */
    public OperationalNotificationEvent standaloneFailed(JobExecutionHistory execution) {
        return new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Job failed: " + jobTitle(execution),
                failureMessage(execution),
                baseMetadata(execution));
    }

    /**
     * Builds the digest emitted when all child executions of a fan-out parent finish
     * successfully.
     *
     * @param parent persisted parent execution after aggregation
     * @param total total number of child executions
     * @param success number of successful child executions
     * @param failed number of failed child executions
     * @return channel-neutral operational notification event
     */
    public OperationalNotificationEvent parentSucceeded(
            JobExecutionHistory parent,
            long total,
            long success,
            long failed) {
        return new OperationalNotificationEvent(
                NotificationSeverity.INFO,
                "Job completed: " + jobTitle(parent),
                success + "/" + total + " symbol tasks completed successfully",
                parentMetadata(parent, total, success, failed));
    }

    /**
     * Builds the digest emitted when a fan-out parent finishes with at least one
     * failed child execution.
     *
     * @param parent persisted parent execution after aggregation
     * @param total total number of child executions
     * @param success number of successful child executions
     * @param failed number of failed child executions
     * @return channel-neutral operational notification event
     */
    public OperationalNotificationEvent parentFailed(
            JobExecutionHistory parent,
            long total,
            long success,
            long failed) {
        return new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Job failed: " + jobTitle(parent),
                failed + "/" + total + " symbol tasks failed",
                parentMetadata(parent, total, success, failed));
    }

    private Map<String, Object> parentMetadata(
            JobExecutionHistory execution,
            long total,
            long success,
            long failed) {
        Map<String, Object> metadata = baseMetadata(execution);
        metadata.put("total", total);
        metadata.put("success", success);
        metadata.put("failed", failed);
        return metadata;
    }

    private Map<String, Object> baseMetadata(JobExecutionHistory execution) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        JobDefinition job = execution.getJob();
        if (job != null) {
            MetadataUtils.putIfPresent(metadata, "jobDefinitionId", job.getId());
            MetadataUtils.putIfPresent(metadata, "jobType", job.getJobType());
            MetadataUtils.putIfPresent(metadata, "source", job.getSource());
        }
        MetadataUtils.putIfPresent(metadata, "executionId", execution.getId());
        MetadataUtils.putIfPresent(metadata, "recordsSynced", execution.getRecordsSynced());
        MetadataUtils.putIfPresent(metadata, "recordsSkipped", execution.getRecordsSkipped());
        return metadata;
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

    private String failureMessage(JobExecutionHistory execution) {
        if (execution.getError() != null && !execution.getError().isBlank()) {
            return execution.getError();
        }
        return "Job failed";
    }

}
