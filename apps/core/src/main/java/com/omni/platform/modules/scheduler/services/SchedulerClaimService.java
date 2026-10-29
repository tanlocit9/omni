package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SchedulerClaimService {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final SchedulerProperties schedulerProperties;
    private final StaticJobTopologyProvider topology;

    @Value("${app.scheduler.job-run.preparation-enabled:false}")
    private boolean jobRunPreparationEnabled;

    @Value("${app.scheduler.job-run.graph-expansion-enabled:false}")
    private boolean graphExpansionEnabled;

    @Transactional
    public List<SchedulerClaim> claimDueJobs(Instant now) {
        if (graphExpansionEnabled) {
            var catalog = jobDefinitionRepository.findAll().stream()
                    .filter(d -> Boolean.TRUE.equals(d.getIsActive()) && Boolean.TRUE.equals(d.getScheduleEnabled())
                            && d.getCanonicalDefinitionId() == null).toList();
            var ancestorTypes = catalog.stream().flatMap(d ->
                    com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.upstreamTypes(
                            d.getJobType(), d.getConfigJson() == null ? java.util.Map.of() : d.getConfigJson()).stream())
                    .collect(java.util.stream.Collectors.toSet());
            var eligible = catalog.stream().filter(d -> !ancestorTypes.contains(d.getJobType()))
                    .map(JobDefinition::getId).toList();
            return jobDefinitionRepository.claimDueJobs(now, schedulerProperties.instanceId(),
                    schedulerProperties.claim().leaseDuration(), schedulerProperties.claim().batchSize(), eligible);
        }

        if (jobRunPreparationEnabled) {
            var entries = topology.definitionDependencies().scheduledEntries();
            var eligibleIds =
                    entries.stream()
                            .map(
                                    identity ->
                                            jobDefinitionRepository
                                                    .findBySourceAndJobTypeAndCronExpr(
                                                            identity.source(),
                                                            identity.jobType(),
                                                            identity.cronExpression()))
                            .flatMap(Optional::stream)
                            .map(JobDefinition::getId)
                            .toList();
            return jobDefinitionRepository.claimDueJobs(
                    now,
                    schedulerProperties.instanceId(),
                    schedulerProperties.claim().leaseDuration(),
                    schedulerProperties.claim().batchSize(),
                    eligibleIds);
        }
        return jobDefinitionRepository.claimDueJobs(
                now,
                schedulerProperties.instanceId(),
                schedulerProperties.claim().leaseDuration(),
                schedulerProperties.claim().batchSize());
    }

    @Transactional
    public Optional<SchedulerClaim> claimJobDefinition(
            UUID jobDefinitionId, Instant now, String actor) {
        return jobDefinitionRepository.claimJobDefinition(
                jobDefinitionId,
                now,
                "manual:" + actor,
                schedulerProperties.claim().leaseDuration());
    }

    @Transactional
    public boolean releaseClaim(UUID jobDefinitionId, UUID claimToken, String claimedBy) {
        return jobDefinitionRepository.releaseClaim(jobDefinitionId, claimToken, claimedBy);
    }
}
