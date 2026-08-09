package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.constants.SyncSignalsConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SignalJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SyncSignalsJobProducer extends JobProducer {

    private final SymbolRepository symbolRepository;

    @Value("${kafka.topics.topic-sync-signals}")
    private String topic;

    public SyncSignalsJobProducer(
            JobService jobService,
            KafkaPublisher kafkaPublisher,
            SymbolRepository symbolRepository) {

        super(jobService, kafkaPublisher);
        this.symbolRepository = symbolRepository;
    }

    @Override
    public JobType getJobType() {
        return JobType.SYNC_SIGNALS;
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
        SyncSignalsConfig config = JobConfigMapper.toSignalsConfig(jobConfig);
        List<String> sectorCodes = config.filters().sectorCodes();
        int sectorLevel = config.filters().sectorLevel();
        String timeframe = config.timeframe();
        String strategy = config.strategy();

        List<SymbolKeyProjection> symbols = symbolRepository.findBySectorCodesAndLevel(
                sectorCodes.isEmpty() ? null : sectorCodes.toArray(new String[0]),
                sectorLevel);

        log.info("Syncing signals for {} symbols with sectorCodes: {} at level {} strategy={} timeframe={} jobId={} executionId={}",
                symbols.size(), sectorCodes, sectorLevel, strategy, timeframe, job.getId(), jobExecutionHistory.getId());
        if (symbols.isEmpty()) {
            log.warn("No symbols found for signal sync job [{}] using sectorCodes [{}] sectorLevel [{}]. No Kafka messages will be published.",
                    job.getId(), sectorCodes, sectorLevel);
        }

        return symbols.stream()
                .map(symbol -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.putAll(jobConfig);

                    JobExecutionHistory childJobExecutionHistory = jobService.createChildExecution(
                            jobExecutionHistory.getId(),
                            symbol.symbolKey(),
                            metadata,
                            timestamps);

                    return new KafkaMessage(
                            symbol.symbolKey(),
                            new SignalJobMessage(
                                    job.getId(),
                                    childJobExecutionHistory.getId(),
                                    jobExecutionHistory.getId(),
                                    job.getSource().toString(),
                                    symbol.symbolKey(),
                                    timeframe,
                                    strategy,
                                    metadata));
                })
                .toList();
    }

    @Override
    protected void postPublish(JobDefinition job, Instant now) {
        log.info("Published signal sync job [{}] for source [{}]", job.getId(), job.getSource());
    }

}
