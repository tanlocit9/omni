package com.omni.platform.modules.scheduler.consumers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.services.JobService;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class JobStatusConsumerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private JobService jobService;

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void handleSyncStatusDelegatesValidMessageToJobService() {
        JobStatusConsumer consumer = new JobStatusConsumer(eventPublisher, jobService, jsonMapper);
        ConsumerRecord<String, String> record = record("""
                {
                  "jobDefinitionId":"job-1",
                  "executionId":"44e8cce7-7197-42d7-93ce-e64d3002e88a",
                  "status":"SUCCESS",
                  "durationMs":10,
                  "recordsProcessed":5
                }
                """);

        consumer.handleSyncStatus(record);

        ArgumentCaptor<JobStatusMessage> captor = ArgumentCaptor.forClass(JobStatusMessage.class);
        verify(jobService).applyStatus(captor.capture());
        verify(eventPublisher, never()).publishEvent(any(OperationalNotificationEvent.class));
        JobStatusMessage message = captor.getValue();
        assert message.executionId().equals("44e8cce7-7197-42d7-93ce-e64d3002e88a");
        assert message.status().equals("SUCCESS");
    }

    @Test
    void handleSyncStatusPublishesProcessingFailureAndRethrowsMalformedMessage() {
        JobStatusConsumer consumer = new JobStatusConsumer(eventPublisher, jobService, jsonMapper);
        ConsumerRecord<String, String> record = record("not-json");

        assertThatThrownBy(() -> consumer.handleSyncStatus(record))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to process stock-sync-status message");

        verify(jobService, never()).applyStatus(any());
        verify(eventPublisher).publishEvent(any(OperationalNotificationEvent.class));
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("topic-sync-job-status", 0, 1L, "key", payload);
    }
}
