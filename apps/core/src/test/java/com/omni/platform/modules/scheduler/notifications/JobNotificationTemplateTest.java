package com.omni.platform.modules.scheduler.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;

class JobNotificationTemplateTest {

    private final JobNotificationTemplate template = new JobNotificationTemplate();

    @Test
    void standaloneSucceededBuildsInfoEvent() {
        JobExecutionHistory execution = execution("Sync symbols", null);
        execution.setRecordsSynced(12);
        execution.setRecordsSkipped(1);

        OperationalNotificationEvent event = template.standaloneSucceeded(execution);

        assertThat(event.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(event.title()).isEqualTo("Job completed: Sync symbols");
        assertThat(event.message()).isEqualTo("Job completed successfully");
        assertThat(event.metadata()).containsEntry("executionId", execution.getId().toString())
                .containsEntry("jobDefinitionId", execution.getJob().getId().toString())
                .containsEntry("jobType", "SYNC_SYMBOLS")
                .containsEntry("source", "VND")
                .containsEntry("recordsSynced", "12")
                .containsEntry("recordsSkipped", "1");
    }

    @Test
    void standaloneFailedBuildsErrorEventWithErrorMessage() {
        JobExecutionHistory execution = execution("Sync prices", "network down");

        OperationalNotificationEvent event = template.standaloneFailed(execution);

        assertThat(event.severity()).isEqualTo(NotificationSeverity.ERROR);
        assertThat(event.title()).isEqualTo("Job failed: Sync prices");
        assertThat(event.message()).isEqualTo("network down");
    }

    @Test
    void standaloneFailedFallsBackWhenErrorMessageIsBlank() {
        JobExecutionHistory execution = execution("Sync prices", " ");

        OperationalNotificationEvent event = template.standaloneFailed(execution);

        assertThat(event.message()).isEqualTo("Job failed");
    }

    @Test
    void parentSucceededBuildsDigestEvent() {
        JobExecutionHistory parent = execution("Banking sector", null);

        OperationalNotificationEvent event = template.parentSucceeded(parent, 25, 25, 0);

        assertThat(event.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(event.title()).isEqualTo("Job completed: Banking sector");
        assertThat(event.message()).isEqualTo("25/25 symbol tasks completed successfully");
        assertThat(event.metadata()).containsEntry("total", 25L)
                .containsEntry("success", 25L)
                .containsEntry("failed", 0L);
    }

    @Test
    void parentFailedBuildsDigestEvent() {
        JobExecutionHistory parent = execution("Banking sector", "2/25 symbol tasks failed");

        OperationalNotificationEvent event = template.parentFailed(parent, 25, 23, 2);

        assertThat(event.severity()).isEqualTo(NotificationSeverity.ERROR);
        assertThat(event.title()).isEqualTo("Job failed: Banking sector");
        assertThat(event.message()).isEqualTo("2/25 symbol tasks failed");
        assertThat(event.metadata()).containsEntry("total", 25L)
                .containsEntry("success", 23L)
                .containsEntry("failed", 2L);
    }

    private JobExecutionHistory execution(String title, String error) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setTitle(title);
        job.setJobType(JobType.SYNC_SYMBOLS);
        job.setSource(DataSource.VND);

        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setJob(job);
        execution.setError(error);
        return execution;
    }
}
