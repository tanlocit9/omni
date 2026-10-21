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

        new JobScheduler(
                        definitions,
                        producers,
                        claims,
                        mock(
                                com.omni.platform.modules.scheduler.runs.JobRunPreparationService
                                        .class))
                .scan();

        verify(producer)
                .prepareDispatch(
                        org.mockito.ArgumentMatchers.same(job),
                        org.mockito.ArgumentMatchers.same(claim),
                        any(Instant.class));
    }

    @Test
    void noDueJobDoesNotResolveProducer() {
        JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
        JobProducerRegistry producers = mock(JobProducerRegistry.class);
        SchedulerClaimService claims = mock(SchedulerClaimService.class);
        when(claims.claimDueJobs(any())).thenReturn(List.of());

        new JobScheduler(
                        definitions,
                        producers,
                        claims,
                        mock(
                                com.omni.platform.modules.scheduler.runs.JobRunPreparationService
                                        .class))
                .scan();

        verifyNoInteractions(producers);
    }

    @Test
    void gatedTargetUsesJobRunPreparationAndDisabledTargetRetainsLegacyDispatch() {
        var definitions = mock(JobDefinitionRepository.class);
        var producers = mock(JobProducerRegistry.class);
        var claims = mock(SchedulerClaimService.class);
        var preparation =
                mock(com.omni.platform.modules.scheduler.runs.JobRunPreparationService.class);
        var producer = mock(JobProducer.class);
        var job = job();
        var claim = claim(job);
        when(claims.claimDueJobs(any())).thenReturn(List.of(claim));
        when(definitions.findById(job.getId())).thenReturn(Optional.of(job));
        var scheduler = new JobScheduler(definitions, producers, claims, preparation);
        when(preparation.supports(job)).thenReturn(true);
        scheduler.scan();
        verify(preparation)
                .prepareScheduled(
                        org.mockito.ArgumentMatchers.same(job),
                        org.mockito.ArgumentMatchers.same(claim),
                        any());
        verifyNoInteractions(producers);
        when(preparation.supports(job)).thenReturn(false);
        when(producers.getProducer(job.getJobType())).thenReturn(producer);
        scheduler.scan();
        verify(producer)
                .prepareDispatch(
                        org.mockito.ArgumentMatchers.same(job),
                        org.mockito.ArgumentMatchers.same(claim),
                        any());
    }

    @Test
    void missingDefinitionsAndFailedPreparationDoNotPreventLaterClaimedJobs() {
        var definitions = mock(JobDefinitionRepository.class);
        var producers = mock(JobProducerRegistry.class);
        var claims = mock(SchedulerClaimService.class);
        var preparation =
                mock(com.omni.platform.modules.scheduler.runs.JobRunPreparationService.class);
        var producer = mock(JobProducer.class);
        var missing = job();
        var broken = job();
        var good = job();
        var missingClaim = claim(missing);
        var brokenClaim = claim(broken);
        var goodClaim = claim(good);
        when(claims.claimDueJobs(any())).thenReturn(List.of(missingClaim, brokenClaim, goodClaim));
        when(definitions.findById(missing.getId())).thenReturn(Optional.empty());
        when(definitions.findById(broken.getId())).thenReturn(Optional.of(broken));
        when(definitions.findById(good.getId())).thenReturn(Optional.of(good));
        when(producers.getProducer(good.getJobType())).thenReturn(producer);
        org.mockito.Mockito.doThrow(new IllegalStateException("Preparation failed"))
                .when(producer)
                .prepareDispatch(
                        org.mockito.ArgumentMatchers.eq(broken),
                        org.mockito.ArgumentMatchers.eq(brokenClaim),
                        any());
        new JobScheduler(definitions, producers, claims, preparation).scan();
        verify(producer)
                .prepareDispatch(
                        org.mockito.ArgumentMatchers.eq(good),
                        org.mockito.ArgumentMatchers.eq(goodClaim),
                        any());
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
