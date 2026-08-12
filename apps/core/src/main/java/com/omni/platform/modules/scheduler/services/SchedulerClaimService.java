package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SchedulerClaimService {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final SchedulerProperties schedulerProperties;

    @Transactional
    public List<SchedulerClaim> claimDueJobs(Instant now) {
        return jobDefinitionRepository.claimDueJobs(
                now,
                schedulerProperties.instanceId(),
                schedulerProperties.claim().leaseDuration(),
                schedulerProperties.claim().batchSize());
    }

    @Transactional
    public boolean releaseClaim(UUID jobDefinitionId, UUID claimToken, String claimedBy) {
        return jobDefinitionRepository.releaseClaim(jobDefinitionId, claimToken, claimedBy);
    }
}
