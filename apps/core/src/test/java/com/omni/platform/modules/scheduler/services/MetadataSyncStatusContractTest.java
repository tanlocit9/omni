package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.omni.platform.modules.notifications.templates.SignalNotificationTemplate;
import com.omni.platform.modules.notifications.services.NotificationOutboxService;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.notifications.JobNotificationPolicyRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;

import tools.jackson.databind.json.JsonMapper;

class MetadataSyncStatusContractTest {
    @Test
    void analyzerPartialWirePayloadSucceedsWithWarningAndPreservesCounters() throws Exception {
        JobStatusMessage message;
        try (var input =
                getClass().getResourceAsStream("/contracts/metadata-sync-partial-status.json")) {
            assertThat(input).isNotNull();
            message =
                    JsonMapper.builder()
                            .findAndAddModules()
                            .build()
                            .readValue(input, JobStatusMessage.class);
        }
        var histories = mock(JobExecutionHistoryRepository.class);
        var events = mock(ApplicationEventPublisher.class);
        var policies = mock(JobNotificationPolicyRegistry.class);
        when(policies.buildNotification(any())).thenReturn(Optional.empty());
        var service =
                new JobService(
                        mock(JobDefinitionRepository.class),
                        histories,
                        events,
                        policies,
                        mock(SchedulerOutboxService.class),
                        mock(NotificationOutboxService.class),
                        mock(SignalNotificationTemplate.class));
        var execution = new JobExecutionHistory();
        execution.setId(UUID.fromString(message.executionId()));
        execution.setStatus(JobStatus.RUNNING);
        when(histories.findById(execution.getId())).thenReturn(Optional.of(execution));

        service.applyStatus(message);

        assertThat(execution.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(execution.getRecordsSynced()).isEqualTo(1);
        assertThat(execution.getError()).isNull();
        assertThat(execution.getMetaJson())
                .containsEntry("objectsSkipped", "1")
                .containsEntry("objectsFailed", "0")
                .containsEntry("result", "PARTIAL_SUCCESS")
                .containsEntry("warning", message.metaJson().get("warning").toString());
        verify(histories).saveAndFlush(execution);
        verify(policies).buildNotification(any());
    }
}
