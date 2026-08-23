package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

class JobProducerTest {

    @Test
    void preparationPersistsOutboxAndReleasesClaimWithoutPublishingKafka() {
        JobService jobService = mock(JobService.class);
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        JobDefinition job = job();
        SchedulerClaim claim = claim(job);
        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        when(jobService.prepareClaimedExecution(job, claim, now, Map.of())).thenReturn(execution);
        KafkaMessage message = new KafkaMessage("stable-key", java.util.Map.of("executionId", execution.getId()));
        JobProducer producer = producer(jobService, kafkaPublisher, message);

        UUID executionId = producer.prepareDispatch(job, claim, now);

        assertThat(executionId).isEqualTo(execution.getId());
        verify(jobService).prepareClaimedExecution(job, claim, now, Map.of());
        verify(jobService).enqueueDispatch(execution, "jobs", List.of(message), now);
        verify(jobService).releaseClaim(claim);
        verifyNoInteractions(kafkaPublisher);
    }

    private JobProducer producer(JobService jobService, KafkaPublisher kafkaPublisher, KafkaMessage message) {
        return new JobProducer(jobService, kafkaPublisher) {
            @Override
            public JobType getJobType() {
                return JobType.SYNC_STOCK_PRICE;
            }

            @Override
            protected String getTopic() {
                return "jobs";
            }

            @Override
            protected List<KafkaMessage> buildMessages(
                    JobDefinition job,
                    JobExecutionHistory log,
                    Instant timestamps) {
                return List.of(message);
            }
        };
    }

    private JobDefinition job() {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(DataSource.VND);
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        return job;
    }

    private SchedulerClaim claim(JobDefinition job) {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        return new SchedulerClaim(
                job.getId(), UUID.randomUUID(), "core-a", now, now.plusSeconds(120), now);
    }
}
