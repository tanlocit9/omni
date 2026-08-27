package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.constants.SectorTransitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SectorTransitionOutcomeEvaluationJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SectorTransitionOutcomeEvaluationJobProducer extends JobProducer {

    private final SymbolRepository symbolRepository;

    @Value("${kafka.topics.topic-sector-transition-evaluate-outcomes}")
    private String topic;

    public SectorTransitionOutcomeEvaluationJobProducer(
            JobService jobService,
            KafkaPublisher kafkaPublisher,
            SymbolRepository symbolRepository) {
        super(jobService, kafkaPublisher);
        this.symbolRepository = symbolRepository;
    }

    @Override
    public JobType getJobType() {
        return JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES;
    }

    @Override
    protected String getTopic() {
        return topic;
    }

    @Override
    protected List<KafkaMessage> buildMessages(
            JobDefinition job,
            JobExecutionHistory jobExecutionHistory,
            Instant timestamps) {
        Map<String, Object> jobConfig = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        SectorTransitionConfig config = JobConfigMapper.toSectorTransitionConfig(jobConfig);
        List<String> configuredSectorCodes = config.filters().sectorCodes();
        int sectorLevel = config.filters().sectorLevel();
        String[] sectorCodeFilter = configuredSectorCodes.isEmpty() ? null : configuredSectorCodes.toArray(new String[0]);
        List<String> resolvedUniverse = symbolRepository.findDistinctSectorCodesByLevel(
                sectorCodeFilter,
                sectorLevel);
        List<String> configuredFocusSectorCodes = config.focusSectorCodes();
        List<String> resolvedFocus = configuredFocusSectorCodes.isEmpty() ? resolvedUniverse : configuredFocusSectorCodes;
        validateFocusWithinUniverse(resolvedFocus, resolvedUniverse);

        Map<String, Object> metadata = new HashMap<>();
        metadata.putAll(jobConfig);
        metadata.put("configuredSectorCodes", configuredSectorCodes);
        metadata.put("configuredFocusSectorCodes", configuredFocusSectorCodes);
        metadata.put("resolvedUniverse", resolvedUniverse);
        metadata.put("resolvedFocus", resolvedFocus);
        metadata.put("resolvedSectorCodes", resolvedUniverse);
        JobExecutionHistory child = jobService.createChildExecution(
                jobExecutionHistory.getId(),
                WorkIdentity.of(WorkType.GLOBAL, config.strategy()),
                metadata,
                timestamps);

        log.info("Evaluating Sector Transition outcomes universe={} focus={} sectorLevel={} strategy={} timeframe={} evaluationDate={} horizons={} jobId={} executionId={}",
                resolvedUniverse, resolvedFocus, sectorLevel, config.strategy(), config.timeframe(), config.evaluationDate(),
                config.predictionHorizons(), job.getId(), child.getId());

        return List.of(new KafkaMessage(
                config.strategy(),
                new SectorTransitionOutcomeEvaluationJobMessage(
                        job.getId(),
                        child.getId(),
                        jobExecutionHistory.getId(),
                        job.getSource().toString(),
                        WorkType.GLOBAL,
                        config.strategy(),
                        config.evaluationDate(),
                        resolvedUniverse,
                        resolvedFocus,
                        sectorLevel,
                        config.timeframe(),
                        config.strategy(),
                        config.predictionHorizons(),
                        metadata)));
    }

    private void validateFocusWithinUniverse(List<String> resolvedFocus, List<String> resolvedUniverse) {
        List<String> invalidFocus = resolvedFocus.stream()
                .filter(focus -> !resolvedUniverse.contains(focus))
                .toList();
        if (!invalidFocus.isEmpty()) {
            throw new IllegalArgumentException(
                    "Sector Transition focusSectorCodes must be within resolved sectorCodes universe: " + invalidFocus);
        }
    }

    @Override
    protected void postPublish(JobDefinition job, Instant now) {
        log.info("Published Sector Transition outcome job [{}] for source [{}]", job.getId(), job.getSource());
    }
}
