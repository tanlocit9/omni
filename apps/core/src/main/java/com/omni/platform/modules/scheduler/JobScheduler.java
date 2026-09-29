package com.omni.platform.modules.scheduler;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Claims due definitions and durably prepares execution/outbox intent. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobProducerRegistry jobProducerRegistry;
    private final SchedulerClaimService schedulerClaimService;

    @Scheduled(fixedDelayString = "${app.scheduler.global.fixedDelayString:30000}")
    public void scan() {
        Instant now = Instant.now();
        List<SchedulerClaim> claims = schedulerClaimService.claimDueJobs(now);
        for (SchedulerClaim claim : claims) {
            JobDefinition job = jobDefinitionRepository.findById(claim.jobDefinitionId()).orElse(null);
            if (job == null) {
                log.warn("Claimed job definition disappeared before preparation: {}", claim.jobDefinitionId());
                continue;
            }
            try {
                jobProducerRegistry.getProducer(job.getJobType()).prepareDispatch(job, claim, now);
            } catch (RuntimeException exception) {
                log.error("Failed to prepare claimed job {}", job.getId(), exception);
            }
        }
    }
}
