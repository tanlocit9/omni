package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.omni.platform.modules.scheduler.config.ManualTriggerProperties;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyContextFactory;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ExecutionSummary;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ManualTriggerRequest;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ManualTriggerResponse;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.TriggerStatusResponse;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.ManualJobTrigger;
import com.omni.platform.modules.scheduler.entities.ManualJobTrigger.ManualTriggerState;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.ManualJobTriggerRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManualJobTriggerService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobExecutionHistoryRepository executionRepository;
    private final ManualJobTriggerRepository triggerRepository;
    private final SchedulerClaimService claimService;
    private final JobDependencyContextFactory dependencyContextFactory;
    private final JobDependencyGuard dependencyGuard;
    private final JobProducerRegistry producerRegistry;
    private final ManualTriggerProperties triggerProperties;

    /**
     * Orchestrates independent claim and outbox transactions. This method is
     * intentionally not transactional: the claim must commit before the producer's
     * REQUIRES_NEW transaction validates ownership.
     */
    public ManualTriggerResponse trigger(UUID definitionId, String actorValue, ManualTriggerRequest request) {
        String actor = required(actorValue, "operator identity", 200);
        if (request == null) {
            throw invalid("A trigger request body is required");
        }
        String key = required(request.idempotencyKey(), "idempotencyKey", 128);
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw invalid("idempotencyKey contains unsupported characters");
        }
        String reason = required(request.reason(), "reason", 500);
        Map<String, Object> parameters = request.parameters() == null ? Map.of() : Map.copyOf(request.parameters());

        Optional<ManualJobTrigger> duplicate = triggerRepository.findByActorAndIdempotencyKey(actor, key);
        if (duplicate.isPresent()) {
            return response(duplicate.get(), true);
        }

        JobDefinition initial = findDefinition(definitionId);
        Map<String, Object> target = validateParameters(initial, parameters);
        ManualJobTrigger audit = new ManualJobTrigger();
        audit.setJobDefinition(initial);
        audit.setActor(actor);
        audit.setIdempotencyKey(key);
        audit.setReason(reason);
        audit.setParameters(parameters);
        audit.setState(ManualTriggerState.REQUESTED);
        audit.setRequestedAt(Instant.now());
        try {
            audit = triggerRepository.saveAndFlush(audit);
        } catch (DataIntegrityViolationException race) {
            return response(triggerRepository.findByActorAndIdempotencyKey(actor, key)
                    .orElseThrow(() -> race), true);
        }

        if (!Boolean.TRUE.equals(initial.getIsActive())) {
            return resolve(audit, ManualTriggerState.CONFLICT, "Job definition is inactive", null);
        }
        if (!triggerProperties.allows(initial)) {
            return resolve(audit, ManualTriggerState.CONFLICT,
                    "Job definition is not in the manual-trigger allow-list", null);
        }

        SchedulerClaim claim = claimService.claimJobDefinition(definitionId, Instant.now(), actor)
                .orElse(null);
        if (claim == null) {
            return resolve(audit, ManualTriggerState.CONFLICT,
                    "Another scheduler or operator execution owns this job", null);
        }

        try {
            JobDefinition claimed = findDefinition(definitionId);
            var guardResult = dependencyGuard.checkDependencies(dependencyContextFactory.create(claimed));
            if (!guardResult.canExecute()) {
                claimService.releaseClaim(claim.jobDefinitionId(), claim.claimToken(), claim.claimedBy());
                return resolve(audit, ManualTriggerState.BLOCKED,
                        JobOperationsCatalogService.sanitize(guardResult.blockReason()), null);
            }

            UUID executionId = producerRegistry.getProducer(claimed.getJobType()).prepareManualDispatch(
                    claimed,
                    claim,
                    Instant.now(),
                    guardResult.approvedInputVersions(),
                    Map.of(
                            "trigger", Map.of(
                                    "kind", "MANUAL",
                                    "requestId", audit.getId().toString(),
                                    "actor", actor,
                                    "reason", reason,
                                    "idempotencyKey", key),
                            "metadataTarget", target));
            audit.setExecutionId(executionId);
            return resolve(audit, ManualTriggerState.ACCEPTED, null, null);
        } catch (JobOperationException exception) {
            releaseBestEffort(claim);
            resolve(audit, ManualTriggerState.FAILED, null, exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            log.error(
                    "Manual trigger dispatch failed: requestId={}, definitionId={}, jobType={}, actor={}",
                    audit.getId(), definitionId, initial.getJobType(), actor, exception);
            releaseBestEffort(claim);
            resolve(audit, ManualTriggerState.FAILED, null, "Manual trigger dispatch failed");
            throw new JobOperationException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "manual_trigger_failed", "Manual trigger dispatch failed");
        }
    }

    public TriggerStatusResponse triggerStatus(UUID requestId, String actorValue) {
        String actor = required(actorValue, "operator identity", 200);
        ManualJobTrigger trigger = triggerRepository.findById(requestId)
                .filter(value -> value.getActor().equals(actor))
                .orElseThrow(() -> new JobOperationException(
                        HttpStatus.NOT_FOUND, "manual_trigger_not_found", "Manual trigger was not found"));
        ExecutionSummary execution = trigger.getExecutionId() == null ? null
                : executionRepository.findById(trigger.getExecutionId())
                        .map(JobOperationsCatalogService::execution).orElse(null);
        return new TriggerStatusResponse(response(trigger, false), execution);
    }

    public ExecutionSummary executionStatus(UUID executionId) {
        JobExecutionHistory execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new JobOperationException(
                        HttpStatus.NOT_FOUND, "job_execution_not_found", "Job execution was not found"));
        return JobOperationsCatalogService.execution(execution);
    }

    private ManualTriggerResponse resolve(
            ManualJobTrigger trigger, ManualTriggerState state, String blockReason, String error) {
        trigger.setState(state);
        trigger.setBlockReason(JobOperationsCatalogService.sanitize(blockReason));
        trigger.setError(JobOperationsCatalogService.sanitize(error));
        trigger.setResolvedAt(Instant.now());
        return response(triggerRepository.save(trigger), false);
    }

    private void releaseBestEffort(SchedulerClaim claim) {
        try {
            claimService.releaseClaim(claim.jobDefinitionId(), claim.claimToken(), claim.claimedBy());
        } catch (RuntimeException ignored) {
            // The producer may already have released the exact claim atomically.
        }
    }

    private JobDefinition findDefinition(UUID id) {
        return jobDefinitionRepository.findById(id)
                .orElseThrow(() -> new JobOperationException(
                        HttpStatus.NOT_FOUND, "job_definition_not_found", "Job definition was not found"));
    }

    private static ManualTriggerResponse response(ManualJobTrigger trigger, boolean duplicate) {
        return new ManualTriggerResponse(
                trigger.getId(), trigger.getJobDefinition().getId(), trigger.getExecutionId(),
                trigger.getState().name(), duplicate,
                JobOperationsCatalogService.sanitize(trigger.getBlockReason()),
                JobOperationsCatalogService.sanitize(trigger.getError()),
                trigger.getRequestedAt(), trigger.getResolvedAt());
    }

    private static Map<String, Object> validateParameters(
            JobDefinition definition, Map<String, Object> parameters) {
        if (definition.getJobType() != JobDefinition.JobType.SYNC_METADATA) {
            if (!parameters.isEmpty()) {
                throw invalid("This job does not accept runtime parameters");
            }
            return Map.of();
        }
        if (parameters.isEmpty()) {
            return Map.of();
        }
        if (!parameters.keySet().equals(parameters.containsKey("partition")
                ? java.util.Set.of("dataset", "partition")
                : java.util.Set.of("dataset"))) {
            throw invalid("Metadata synchronization accepts only dataset and partition");
        }
        String dataset = required(String.valueOf(parameters.get("dataset")), "dataset", 64).toLowerCase();
        Map<String, java.util.Set<String>> keys = Map.of(
                "eod", java.util.Set.of("exchange", "code"),
                "indicators", java.util.Set.of("source", "timeframe", "exchange", "code"),
                "signals", java.util.Set.of("strategy", "timeframe", "exchange"));
        if (!keys.containsKey(dataset)) {
            throw invalid("Unsupported metadata dataset");
        }
        if (!parameters.containsKey("partition")) {
            return Map.of("dataset", dataset);
        }
        if (!(parameters.get("partition") instanceof Map<?, ?> raw)
                || !raw.keySet().equals(keys.get(dataset))) {
            throw invalid("Metadata partition keys are incomplete or unsupported");
        }
        Map<String, String> partition = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> partition.put(
                String.valueOf(key), required(String.valueOf(value), "partition value", 256).toLowerCase()));
        return Map.of("dataset", dataset, "partition", partition);
    }

    private static String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw invalid(name + " exceeds " + maxLength + " characters");
        }
        return result;
    }

    private static JobOperationException invalid(String message) {
        return new JobOperationException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_trigger_request", message);
    }
}
