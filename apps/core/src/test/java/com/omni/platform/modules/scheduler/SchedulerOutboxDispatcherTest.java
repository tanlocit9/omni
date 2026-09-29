package com.omni.platform.modules.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.dependencies.DependencyDecision;
import com.omni.platform.modules.scheduler.dependencies.DependencyRegistry;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxCandidate;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

class SchedulerOutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void waitingCandidateDoesNotConsumeAttemptAndReadyCandidateIsPublished() {
        SchedulerOutboxService outbox = mock(SchedulerOutboxService.class);
        DependencyRegistry dependencies = mock(DependencyRegistry.class);
        KafkaPublisher kafka = mock(KafkaPublisher.class);
        var waiting = candidate("waiting");
        var ready = candidate("ready");
        var claim = claim(ready);
        when(outbox.findCandidates(NOW, 8)).thenReturn(List.of(waiting, ready));
        when(dependencies.evaluate(any())).thenReturn(
                DependencyDecision.waiting("DEPENDENCY_WAITING", "manifest missing", NOW.plusSeconds(30)),
                DependencyDecision.ready(Map.of()));
        when(outbox.claimEligible(
                ready.messageId(), NOW, "core-a", Duration.ofMinutes(2), Map.of()))
                .thenReturn(claim);
        SchedulerOutboxDispatcher dispatcher = new SchedulerOutboxDispatcher(
                outbox, dependencies, kafka, properties(2));

        dispatcher.dispatchBatch(NOW);

        verify(outbox).markWaiting(eq(waiting.messageId()), eq(NOW.plusSeconds(30)), any());
        verify(outbox, never()).claimEligible(eq(waiting.messageId()), any(), any(), any(), any());
        verify(kafka).publishSerializedAndWait(eq("jobs"), eq("ready"), any(), any());
        verify(outbox).markDelivered(eq(claim), any());
    }

    @Test
    void terminalDecisionMakesOutboxNonPublishable() {
        SchedulerOutboxService outbox = mock(SchedulerOutboxService.class);
        DependencyRegistry dependencies = mock(DependencyRegistry.class);
        KafkaPublisher kafka = mock(KafkaPublisher.class);
        var blocked = candidate("blocked");
        when(outbox.findCandidates(NOW, 4)).thenReturn(List.of(blocked));
        when(dependencies.evaluate(any())).thenReturn(
                DependencyDecision.blocked("DEPENDENCY_INCOMPATIBLE", "schema unsupported"));
        SchedulerOutboxDispatcher dispatcher = new SchedulerOutboxDispatcher(
                outbox, dependencies, kafka, properties(1));

        dispatcher.dispatchBatch(NOW);

        verify(outbox).markBlocked(eq(blocked.messageId()), eq(NOW), any());
        verify(outbox, never()).claimEligible(any(), any(), any(), any(), any());
        verify(kafka, never()).publishSerializedAndWait(any(), any(), any(), any());
    }

    private SchedulerProperties properties(int batchSize) {
        return new SchedulerProperties("core-a", new SchedulerProperties.Claim(Duration.ofMinutes(2), batchSize));
    }

    private SchedulerOutboxCandidate candidate(String key) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setJobType(JobDefinition.JobType.SYNC_INDICATORS);
        job.setSource(JobDefinition.DataSource.ANALYZER);
        return new SchedulerOutboxCandidate(UUID.randomUUID(), UUID.randomUUID(), null, job,
                "SYMBOL", key, "2026-08-13", Map.of(), NOW);
    }

    private SchedulerOutboxClaim claim(SchedulerOutboxCandidate candidate) {
        return new SchedulerOutboxClaim(candidate.messageId(), UUID.randomUUID(), "core-a",
                candidate.executionId(), "jobs", candidate.workKey(), "{}", 1);
    }
}
