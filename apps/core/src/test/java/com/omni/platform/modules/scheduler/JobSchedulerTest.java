package com.omni.platform.modules.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard.GuardResult;
import com.omni.platform.modules.scheduler.dependencies.JobExecutionContext;
import com.omni.platform.modules.scheduler.entities.BlockedJob;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.producers.JobProducer;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.BlockedJobTracker;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;

class JobSchedulerTest {

    private JobDefinitionRepository repository;
    private JobProducerRegistry registry;
    private SchedulerClaimService claimService;
    private JobDependencyGuard dependencyGuard;
    private BlockedJobTracker blockedJobTracker;
    private JobScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(JobDefinitionRepository.class);
        registry = mock(JobProducerRegistry.class);
        claimService = mock(SchedulerClaimService.class);
        dependencyGuard = mock(JobDependencyGuard.class);
        blockedJobTracker = mock(BlockedJobTracker.class);
        scheduler = new JobScheduler(repository, registry, claimService, dependencyGuard, blockedJobTracker);

        // Default: no blocked jobs ready for retry
        when(blockedJobTracker.findJobsReadyForRetry(any(Instant.class))).thenReturn(List.of());
    }

    @Test
    void scanDispatchesDueJobsThroughRegistry() {
        JobProducer producer = mock(JobProducer.class);
        JobDefinition job = job(JobType.SYNC_STOCK_PRICE);
        SchedulerClaim claim = claim(job);

        when(claimService.claimDueJobs(any(Instant.class))).thenReturn(List.of(claim));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(registry.getProducer(JobType.SYNC_STOCK_PRICE)).thenReturn(producer);
        // Guard returns READY
        when(dependencyGuard.checkDependencies(any(JobExecutionContext.class)))
                .thenReturn(GuardResult.ready());
        when(blockedJobTracker.isBlocked(job)).thenReturn(false);

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
        when(claimService.claimDueJobs(any(Instant.class))).thenReturn(List.of());

        scheduler.scan();

        verifyNoInteractions(registry);
    }

    @Test
    void scanBlocksJobWhenDependencyGuardBlocks() {
        JobDefinition job = job(JobType.SYNC_INDICATORS);
        SchedulerClaim claim = claim(job);

        when(claimService.claimDueJobs(any(Instant.class))).thenReturn(List.of(claim));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(blockedJobTracker.isBlocked(job)).thenReturn(false);
        when(dependencyGuard.checkDependencies(any(JobExecutionContext.class)))
                .thenReturn(GuardResult.blocked(List.of(), "eod dataset not READY"));

        scheduler.scan();

        verifyNoInteractions(registry);
        verify(blockedJobTracker).recordBlocked(
                org.mockito.ArgumentMatchers.same(job),
                any(GuardResult.class),
                any(String.class));
    }

    @Test
    void scanSkipsJobAlreadyInBlockedState() {
        JobDefinition job = job(JobType.SYNC_INDICATORS);
        SchedulerClaim claim = claim(job);

        when(claimService.claimDueJobs(any(Instant.class))).thenReturn(List.of(claim));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(blockedJobTracker.isBlocked(job)).thenReturn(true);

        scheduler.scan();

        verifyNoInteractions(registry);
        verify(dependencyGuard, never()).checkDependencies(any());
    }

    @Test
    void scanRetriesBlockedJobWhenDependenciesResolved() {
        JobDefinition job = job(JobType.SYNC_INDICATORS);
        SchedulerClaim claim = claim(job);
        BlockedJob blockedJob = blockedJobStub(job);

        when(blockedJobTracker.findJobsReadyForRetry(any(Instant.class))).thenReturn(List.of(blockedJob));
        when(repository.findAll()).thenReturn(List.of(job));
        when(dependencyGuard.checkDependencies(any(JobExecutionContext.class)))
                .thenReturn(GuardResult.ready());
        // Re-claim succeeds
        when(claimService.claimDueJobs(any(Instant.class))).thenReturn(List.of(claim));
        JobProducer producer = mock(JobProducer.class);
        when(registry.getProducer(JobType.SYNC_INDICATORS)).thenReturn(producer);

        scheduler.scan();

        verify(blockedJobTracker).markResolved(job);
        verify(producer).prepareDispatch(
                org.mockito.ArgumentMatchers.same(job),
                org.mockito.ArgumentMatchers.same(claim),
                any(Instant.class));
    }

    @Test
    void scanUpdatesBlockedJobWhenStillBlocked() {
        JobDefinition job = job(JobType.SYNC_INDICATORS);
        BlockedJob blockedJob = blockedJobStub(job);

        when(blockedJobTracker.findJobsReadyForRetry(any(Instant.class))).thenReturn(List.of(blockedJob));
        when(repository.findAll()).thenReturn(List.of(job));
        when(dependencyGuard.checkDependencies(any(JobExecutionContext.class)))
                .thenReturn(GuardResult.blocked(List.of(), "eod still missing"));
        // No new claims in pass 2
        when(claimService.claimDueJobs(any(Instant.class))).thenReturn(List.of());

        scheduler.scan();

        verify(blockedJobTracker, never()).markResolved(any());
        verify(blockedJobTracker).recordBlocked(
                org.mockito.ArgumentMatchers.same(job),
                any(GuardResult.class),
                any(String.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JobDefinition job(JobType jobType) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setJobType(jobType);
        job.setSource(DataSource.VND);
        job.setTitle("Test job");
        job.setIsActive(true);
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

    private BlockedJob blockedJobStub(JobDefinition job) {
        String jobName = job.getJobType().name() + "_" + job.getSource().name();
        BlockedJob blocked = new BlockedJob();
        blocked.setJobName(jobName);
        blocked.setJobType(job.getJobType().name());
        blocked.setExecutionId(UUID.randomUUID().toString());
        blocked.setBlockReason("eod not ready");
        blocked.setFirstBlockedAt(Instant.now().minusSeconds(60));
        blocked.setNextRetryAt(Instant.now().minusSeconds(5));
        blocked.setRetryCount(1);
        blocked.setMaxRetries(20);
        blocked.setResolved(false);
        return blocked;
    }
}
