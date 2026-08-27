package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.constants.SectorWaveConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SectorWaveSectorFeatureJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PrecomputeSectorFeaturesJobProducer extends JobProducer {

    private final SymbolRepository symbolRepository;

    @Value("${kafka.topics.topic-precompute-sector-features}")
    private String topic;

    public PrecomputeSectorFeaturesJobProducer(
            JobService jobService,
            KafkaPublisher kafkaPublisher,
            SymbolRepository symbolRepository) {
        super(jobService, kafkaPublisher);
        this.symbolRepository = symbolRepository;
    }

    @Override
    public JobType getJobType() {
        return JobType.PRECOMPUTE_SECTOR_FEATURES;
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
        SectorWaveConfig config = JobConfigMapper.toSectorWaveConfig(jobConfig);
        List<String> sectorCodes = config.filters().sectorCodes();
        int sectorLevel = config.filters().sectorLevel();
        String[] sectorCodeFilter = sectorCodes.isEmpty() ? null : sectorCodes.toArray(new String[0]);
        List<String> resolvedSectorCodes = symbolRepository.findDistinctSectorCodesByLevel(
                sectorCodeFilter,
                sectorLevel);

        log.info("Precomputing sector features for sectorCodes={} sectorLevel={} timeframe={} jobId={} executionId={}",
                resolvedSectorCodes, sectorLevel, config.timeframe(), job.getId(), jobExecutionHistory.getId());

        return resolvedSectorCodes.stream()
                .map(sectorCode -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.putAll(jobConfig);
                    JobExecutionHistory child = jobService.createChildExecution(
                            jobExecutionHistory.getId(),
                            WorkIdentity.of(WorkType.SECTOR, sectorCode),
                            metadata,
                            timestamps);
                    return new KafkaMessage(
                            sectorCode,
                            new SectorWaveSectorFeatureJobMessage(
                                    job.getId(),
                                    child.getId(),
                                    jobExecutionHistory.getId(),
                                    job.getSource().toString(),
                                    WorkType.SECTOR,
                                    sectorCode,
                                    sectorCode,
                                    sectorLevel,
                                    config.timeframe(),
                                    metadata));
                })
                .toList();
    }

    @Override
    protected void postPublish(JobDefinition job, Instant now) {
        log.info("Published sector feature precompute job [{}] for source [{}]", job.getId(), job.getSource());
    }
}
