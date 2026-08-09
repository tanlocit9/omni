package com.omni.platform.modules.scheduler.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.templates.JobNotificationTemplate;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;

class SectorTransitionNotificationPolicyTest {

    @Test
    void failedSectorTransitionBuildsActionableNotification() {
        SectorTransitionNotificationPolicy policy = new SectorTransitionNotificationPolicy(
                new DefaultJobNotificationPolicy(new JobNotificationTemplate()));
        JobExecutionHistory parent = execution(JobStatus.FAILED, null);
        parent.setError("1/1 tasks failed");
        parent.setMetaJson(Map.of("evaluationDate", "2026-08-09"));
        JobExecutionHistory child = execution(JobStatus.FAILED, parent.getId());
        child.setMetaJson(Map.of(
                "focusSectorCodes", List.of("BANKS"),
                "sectorCodes", List.of("BANKS", "TECH"),
                "sectorLevel", 2,
                "timeframe", "1d",
                "strategy", "SECTOR_TRANSITION_V1",
                "predictionHorizons", List.of(5, 10, 20),
                "errorMessage", "sector frame missing"));

        Optional<Object> notification = policy.buildNotification(new JobNotificationContext(parent, List.of(child), 1, 0, 1));

        assertThat(notification).isPresent();
        assertThat(notification.get()).isInstanceOfSatisfying(OperationalNotificationEvent.class, event -> {
            assertThat(event.severity()).isEqualTo(NotificationSeverity.ERROR);
            assertThat(event.title()).isEqualTo("Sector Transition analysis failed");
            assertThat(event.message()).contains(
                    "Focus: BANKS",
                    "Evaluation date: 2026-08-09",
                    "Timeframe: 1d",
                    "Strategy: SECTOR_TRANSITION_V1",
                    "Horizons: T5, T10, T20",
                    "Reason:\nsector frame missing",
                    "Progress: 0 succeeded / 1 failed");
            assertThat(event.message()).doesNotContain(
                    "1/1 tasks failed",
                    "childCount",
                    "metadata",
                    "jobDefinitionId",
                    "errorMessage:",
                    "workKey",
                    "executionId",
                    "recordsProcessed");
            assertThat(event.metadata()).isEmpty();
        });
    }

    private JobExecutionHistory execution(JobStatus status, UUID parentLogId) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setTitle("Sector transition analyze");
        job.setJobType(JobType.SECTOR_TRANSITION_ANALYZE);
        job.setSource(DataSource.ANALYZER);

        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setJob(job);
        execution.setStatus(status);
        execution.setParentLogId(parentLogId);
        return execution;
    }
}
