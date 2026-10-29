package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunWorkContext;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobProducerTest {
    private final Instant now = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void preparationPersistsAtomicOutboxAndReleasesClaimWithoutPublishingKafka() {
        var jobs = mock(JobService.class);
        var publisher = mock(KafkaPublisher.class);
        var job = job();
        var root = execution(job);
        var child = execution(job);
        when(jobs.prepareClaimedExecution(eq(job), any(), eq(now), eq(Map.of()))).thenReturn(root);
        when(jobs.createChildExecution(eq(root.getId()), any(), any(), eq(now))).thenReturn(child);
        var producer = new SyncMetadataJobProducer(jobs, publisher);
        assertThat(producer.prepareDispatch(job, claim(job), now)).isEqualTo(root.getId());
        ArgumentCaptor<List<KafkaMessage>> sent = ArgumentCaptor.forClass((Class) List.class);
        verify(jobs).enqueueDispatch(eq(root), nullable(String.class), sent.capture(), eq(now));
        assertThat(sent.getValue()).singleElement().satisfies(message -> {
            var work = (ExpandedJobMessage) message.payload();
            assertThat(work.executionId()).isEqualTo(child.getId());
            assertThat(work.parentExecutionId()).isEqualTo(root.getId());
            assertThat(work.businessDate().toString()).isEqualTo("2026-08-13");
            assertThat(work.atomicWorkId()).startsWith("work:");
        });
        verify(jobs).releaseClaim(claim(job));
        verifyNoInteractions(publisher);
    }

    @Test
    void manualAndZeroChildPreparationRetainClaimReleaseAndNoopBehavior() {
        var jobs = mock(JobService.class);
        var publisher = mock(KafkaPublisher.class);
        var job = job();
        var root = execution(job);
        var lease = claim(job);
        var metadata = Map.<String, Object>of("trigger", "manual");
        when(jobs.prepareManualClaimedExecution(job, lease, now, Map.of(), metadata)).thenReturn(root);
        when(jobs.prepareClaimedExecution(job, lease, now, Map.of())).thenReturn(root);
        JobProducer empty = new JobProducer(jobs, publisher) {
            public JobType getJobType() { return JobType.SYNC_METADATA; }
            protected String getTopic() { return "jobs"; }
            protected List<KafkaMessage> buildMessages(JobDefinition d, JobExecutionHistory h, Instant t) {
                throw new AssertionError("Preparation must use the unified boundary");
            }
            protected List<KafkaMessage> buildRunMessages(JobDefinition d, JobExecutionHistory h,
                                                         Instant t, JobRunWorkContext context) {
                return List.of();
            }
        };
        empty.prepareManualDispatch(job, lease, now, Map.of(), metadata);
        empty.prepareDispatch(job, lease, now);
        verify(jobs, times(2)).markParentWithNoChildren(root, now);
        verify(jobs, times(2)).releaseClaim(lease);
        verify(jobs, never()).enqueueDispatch(any(), any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void unsupportedPreparationCapabilityStillFailsClosed() {
        var jobs = mock(JobService.class);
        var publisher = mock(KafkaPublisher.class);
        var unsupported = new JobProducer(jobs, publisher) {
            public JobType getJobType() { return JobType.SYNC_METADATA; }
            protected String getTopic() { return "jobs"; }
            protected List<KafkaMessage> buildMessages(JobDefinition d, JobExecutionHistory h, Instant t) {
                return List.of();
            }
        };
        assertThatThrownBy(() -> unsupported.prepareRunStep(job(), execution(job()), now, null))
                .hasMessageContaining("UNSUPPORTED_PRODUCER");
        verifyNoInteractions(jobs, publisher);
    }

    private JobDefinition job() {
        var job = new JobDefinition();
        job.setId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        job.setSource(DataSource.ANALYZER);
        job.setJobType(JobType.SYNC_METADATA);
        return job;
    }

    private JobExecutionHistory execution(JobDefinition job) {
        var execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setJob(job);
        execution.setUsedSource(job.getSource());
        return execution;
    }

    private SchedulerClaim claim(JobDefinition job) {
        return new SchedulerClaim(job.getId(), job.getId(), "core-a", now, now.plusSeconds(120), now);
    }
}
