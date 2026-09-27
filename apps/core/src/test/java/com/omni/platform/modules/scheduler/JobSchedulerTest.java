package com.omni.platform.modules.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.producers.JobProducer;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;

class JobSchedulerTest {

    @Test
    void dueJobIsPreparedWithoutPreEnqueueDependencyEvaluation() {
        JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
        JobProducerRegistry producers = mock(JobProducerRegistry.class);
        SchedulerClaimService claims = mock(SchedulerClaimService.class);
        JobProducer producer = mock(JobProducer.class);
        JobDefinition job = job();
        SchedulerClaim claim = claim(job);
        when(claims.claimDueJobs(any())).thenReturn(List.of(claim));
        when(definitions.findById(job.getId())).thenReturn(Optional.of(job));
        when(producers.getProducer(job.getJobType())).thenReturn(producer);

        new JobScheduler(definitions, producers, claims).scan();

        verify(producer).prepareDispatch(org.mockito.ArgumentMatchers.same(job),
                org.mockito.ArgumentMatchers.same(claim), any(Instant.class));
    }

    @Test
    void noDueJobDoesNotResolveProducer() {
        JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
        JobProducerRegistry producers = mock(JobProducerRegistry.class);
        SchedulerClaimService claims = mock(SchedulerClaimService.class);
        when(claims.claimDueJobs(any())).thenReturn(List.of());

        new JobScheduler(definitions, producers, claims).scan();

        verifyNoInteractions(producers);
    }

    private JobDefinition job() {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setJobType(JobDefinition.JobType.SYNC_INDICATORS);
        job.setSource(JobDefinition.DataSource.ANALYZER);
        return job;
    }

    private SchedulerClaim claim(JobDefinition job) {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        UUID token = UUID.randomUUID();
        job.setClaimToken(token);
        job.setClaimedBy("core-a");
        return new SchedulerClaim(job.getId(), token, "core-a", now, now.plusSeconds(120), now);
    }
}
