package com.omni.platform.modules.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.producers.JobProducer;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;

class JobSchedulerTest {

    @Test
    void scanDispatchesDueJobsThroughRegistry() {
        JobDefinitionRepository repository = mock(JobDefinitionRepository.class);
        JobProducerRegistry registry = mock(JobProducerRegistry.class);
        SchedulerClaimService claimService = mock(SchedulerClaimService.class);
        JobProducer producer = mock(JobProducer.class);
        JobDefinition job = job(JobType.SYNC_STOCK_PRICE);
        SchedulerClaim claim = claim(job);
        when(claimService.claimDueJobs(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of(claim));
        when(repository.findById(job.getId())).thenReturn(java.util.Optional.of(job));
        when(registry.getProducer(JobType.SYNC_STOCK_PRICE)).thenReturn(producer);
        JobScheduler scheduler = new JobScheduler(repository, registry, claimService);

        scheduler.scan();

        verify(registry).getProducer(JobType.SYNC_STOCK_PRICE);
        ArgumentCaptor<Instant> timestamp = ArgumentCaptor.forClass(Instant.class);
        verify(producer).prepareDispatch(
                org.mockito.ArgumentMatchers.same(job),
                org.mockito.ArgumentMatchers.same(claim),
                timestamp.capture());
    }

    @Test
    void scanDoesNotDispatchWhenNoJobsAreDue() {
        JobDefinitionRepository repository = mock(JobDefinitionRepository.class);
        JobProducerRegistry registry = mock(JobProducerRegistry.class);
        SchedulerClaimService claimService = mock(SchedulerClaimService.class);
        when(claimService.claimDueJobs(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of());
        JobScheduler scheduler = new JobScheduler(repository, registry, claimService);

        scheduler.scan();

        verifyNoInteractions(registry);
    }

    private JobDefinition job(JobType jobType) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setJobType(jobType);
        job.setSource(DataSource.VND);
        job.setTitle("Test job");
        return job;
    }

    private SchedulerClaim claim(JobDefinition job) {
        Instant claimedAt = Instant.parse("2026-08-13T00:00:00Z");
        UUID token = UUID.randomUUID();
        job.setClaimToken(token);
        job.setClaimedBy("core-a");
        job.setClaimedAt(claimedAt);
        job.setClaimUntil(claimedAt.plusSeconds(120));
        return new SchedulerClaim(
                job.getId(), token, "core-a", claimedAt, claimedAt.plusSeconds(120), claimedAt);
    }
}
