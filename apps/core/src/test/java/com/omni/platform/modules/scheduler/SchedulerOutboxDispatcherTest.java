package com.omni.platform.modules.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

class SchedulerOutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void successfulPublishAcknowledgesTheExactOutboxClaim() {
        SchedulerOutboxService outboxService = mock(SchedulerOutboxService.class);
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        SchedulerProperties properties = properties();
        SchedulerOutboxClaim claim = claim();
        when(outboxService.claimPending(NOW, "core-a", Duration.ofMinutes(2), 10))
                .thenReturn(List.of(claim));
        SchedulerOutboxDispatcher dispatcher = new SchedulerOutboxDispatcher(
                outboxService, kafkaPublisher, properties);

        dispatcher.dispatchBatch(NOW);

        verify(kafkaPublisher).publishSerializedAndWait(
                org.mockito.ArgumentMatchers.eq("jobs"),
                org.mockito.ArgumentMatchers.eq("ACB"),
                org.mockito.ArgumentMatchers.eq("{\"executionId\":\"stable\"}"),
                org.mockito.ArgumentMatchers.any(Duration.class));
        verify(outboxService).markPublished(
                org.mockito.ArgumentMatchers.same(claim),
                org.mockito.ArgumentMatchers.any(Instant.class));
        verify(outboxService, never()).markFailed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishFailureKeepsTheSameMessageRecoverable() {
        SchedulerOutboxService outboxService = mock(SchedulerOutboxService.class);
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        SchedulerOutboxClaim claim = claim();
        when(outboxService.claimPending(NOW, "core-a", Duration.ofMinutes(2), 10))
                .thenReturn(List.of(claim));
        RuntimeException failure = new RuntimeException("broker unavailable");
        org.mockito.Mockito.doThrow(failure).when(kafkaPublisher).publishSerializedAndWait(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
        SchedulerOutboxDispatcher dispatcher = new SchedulerOutboxDispatcher(
                outboxService, kafkaPublisher, properties());

        dispatcher.dispatchBatch(NOW);

        verify(outboxService).markFailed(
                org.mockito.ArgumentMatchers.same(claim),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.same(failure));
        verify(outboxService, never()).markPublished(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private SchedulerProperties properties() {
        return new SchedulerProperties(
                "core-a",
                new SchedulerProperties.Claim(Duration.ofMinutes(2), 10));
    }

    private SchedulerOutboxClaim claim() {
        return new SchedulerOutboxClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "core-a",
                UUID.randomUUID(),
                "jobs",
                "ACB",
                "{\"executionId\":\"stable\"}",
                1);
    }
}

