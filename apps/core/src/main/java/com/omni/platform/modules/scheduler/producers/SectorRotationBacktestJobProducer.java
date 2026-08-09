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
import com.omni.platform.modules.scheduler.messaging.SectorRotationBacktestJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SectorRotationBacktestJobProducer extends JobProducer {

    private final SymbolRepository symbolRepository;

    @Value("${kafka.topics.topic-sector-rotation-backtest}")
    private String topic;

    public SectorRotationBacktestJobProducer(
            JobService jobService,
            KafkaPublisher kafkaPublisher,
            SymbolRepository symbolRepository) {
        super(jobService, kafkaPublisher);
        this.symbolRepository = symbolRepository;
    }

    @Override
    public JobType getJobType() {
        return JobType.SECTOR_ROTATION_BACKTEST;
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

        Map<String, Object> metadata = new HashMap<>();
        metadata.putAll(jobConfig);
        metadata.put("resolvedSectorCodes", resolvedSectorCodes);
        JobExecutionHistory child = jobService.createChildExecution(
                jobExecutionHistory.getId(),
                config.strategy(),
                metadata,
                timestamps);

        log.info("Running sector rotation backtest sectorCodes={} sectorLevel={} strategy={} timeframe={} jobId={} executionId={}",
                resolvedSectorCodes, sectorLevel, config.strategy(), config.timeframe(), job.getId(), child.getId());

        return List.of(new KafkaMessage(
                config.strategy(),
                new SectorRotationBacktestJobMessage(
                        job.getId(),
                        child.getId(),
                        jobExecutionHistory.getId(),
                        job.getSource().toString(),
                        resolvedSectorCodes,
                        sectorLevel,
                        config.timeframe(),
                        config.strategy(),
                        metadata)));
    }

    @Override
    protected void postPublish(JobDefinition job, Instant now) {
        log.info("Published sector rotation backtest job [{}] for source [{}]", job.getId(), job.getSource());
    }
}
