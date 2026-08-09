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

class JobSchedulerTest {

    @Test
    void scanDispatchesDueJobsThroughRegistry() {
        JobDefinitionRepository repository = mock(JobDefinitionRepository.class);
        JobProducerRegistry registry = mock(JobProducerRegistry.class);
        JobProducer producer = mock(JobProducer.class);
        JobDefinition job = job(JobType.SYNC_STOCK_PRICE);
        when(repository.findJobsDue(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of(job));
        when(registry.getProducer(JobType.SYNC_STOCK_PRICE)).thenReturn(producer);
        JobScheduler scheduler = new JobScheduler(repository, registry);

        scheduler.scan();

        verify(registry).getProducer(JobType.SYNC_STOCK_PRICE);
        ArgumentCaptor<Instant> timestamp = ArgumentCaptor.forClass(Instant.class);
        verify(producer).publish(org.mockito.ArgumentMatchers.same(job), timestamp.capture());
    }

    @Test
    void scanDoesNotDispatchWhenNoJobsAreDue() {
        JobDefinitionRepository repository = mock(JobDefinitionRepository.class);
        JobProducerRegistry registry = mock(JobProducerRegistry.class);
        when(repository.findJobsDue(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of());
        JobScheduler scheduler = new JobScheduler(repository, registry);

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
}
