package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.IndicatorJobMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SyncIndicatorsJobProducer extends JobProducer {

    private static final int DEFAULT_SECTOR_LEVEL = 1;
    private static final int MIN_SECTOR_LEVEL = 1;
    private static final int MAX_SECTOR_LEVEL = 4;

    private final SymbolRepository symbolRepository;

    @Value("${kafka.topics.topic-sync-indicators}")
    private String topic;

    public SyncIndicatorsJobProducer(
            JobService jobService,
            KafkaPublisher kafkaPublisher,
            SymbolRepository symbolRepository) {

        super(jobService, kafkaPublisher);
        this.symbolRepository = symbolRepository;
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
        List<String> sectorCodes = extractSectorCodes(jobConfig);
        int sectorLevel = extractSectorLevel(jobConfig);
        String timeframe = extractTimeframe(jobConfig);
        List<String> indicators = extractIndicators(jobConfig);
        String indicatorSource = extractIndicatorSource(jobConfig);

        List<SymbolKeyProjection> symbols = symbolRepository.findBySectorCodesAndLevel(
                sectorCodes.isEmpty() ? null : sectorCodes.toArray(new String[0]),
                sectorLevel);

        log.info("Syncing indicators for {} symbols with sectorCodes: {} at level {}",
                symbols.size(), sectorCodes, sectorLevel);

        return symbols.stream()
                .map(symbol -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.putAll(jobConfig);

                    JobExecutionHistory childJobExecutionHistory = jobService.createSymbolChildExecution(
                            jobExecutionHistory.getId(),
                            symbol.symbolKey(),
                            metadata,
                            timestamps);

                    return new KafkaMessage(
                            symbol.symbolKey(),
                            new IndicatorJobMessage(
                                    job.getId(),
                                    childJobExecutionHistory.getId(),
                                    jobExecutionHistory.getId(),
                                    job.getSource().toString(),
                                    symbol.symbolKey(),
                                    timeframe,
                                    indicatorSource,
                                    indicators,
                                    metadata));
                })
                .toList();
    }

    @Override
    protected void postPublish(JobDefinition job, Instant now) {
        log.info("Published indicator sync job [{}] for source [{}]", job.getId(), job.getSource());
    }

    private String extractTimeframe(Map<String, Object> config) {
        Object raw = config.get(JobDefinitionConfig.CONFIG_KEY_TIMEFRAME);
        if (raw == null || raw.toString().isBlank()) {
            return JobDefinitionConfig.INDICATOR_TIMEFRAME_1D;
        }
        return raw.toString();
    }

    private String extractIndicatorSource(Map<String, Object> config) {
        Object raw = config.get(JobDefinitionConfig.CONFIG_KEY_INDICATOR_SOURCE);
        if (raw == null || raw.toString().isBlank()) {
            return JobDefinitionConfig.CONFIG_KEY_INDICATOR_SOURCE_CLOSE;
        }
        return raw.toString();
    }

    private List<String> extractIndicators(Map<String, Object> config) {
        Object raw = config.get(JobDefinitionConfig.CONFIG_KEY_INDICATORS);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return JobDefinitionConfig.SUPPORTED_INDICATORS;
        }

        List<String> requested = list.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toString().toUpperCase())
                .toList();

        if (!requested.equals(JobDefinitionConfig.SUPPORTED_INDICATORS)) {
            log.warn("Ignoring partial or non-canonical indicator set {}. Using full supported set {}",
                    requested, JobDefinitionConfig.SUPPORTED_INDICATORS);
            return JobDefinitionConfig.SUPPORTED_INDICATORS;
        }
        return requested;
    }

    private List<String> extractSectorCodes(Map<String, Object> config) {
        if (!config.containsKey(JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES)) {
            return List.of();
        }
        Object raw = config.get(JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES);
        if (!(raw instanceof List<?> list)) {
            log.warn("Job configJson has 'sectorCodes' key but it's not a List: {}", raw);
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(v -> v.toString().toUpperCase())
                .toList();
    }

    private int extractSectorLevel(Map<String, Object> config) {
        if (!config.containsKey(JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL)) {
            return DEFAULT_SECTOR_LEVEL;
        }
        Object raw = config.get(JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL);
        int level;
        try {
            level = Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            log.warn("Job configJson has 'sectorLevel' key but it's not an integer: {}. Falling back to level {}",
                    raw, DEFAULT_SECTOR_LEVEL);
            return DEFAULT_SECTOR_LEVEL;
        }
        if (level < MIN_SECTOR_LEVEL || level > MAX_SECTOR_LEVEL) {
            log.warn("Job configJson 'sectorLevel' [{}] out of range [{}-{}]. Falling back to level {}",
                    level, MIN_SECTOR_LEVEL, MAX_SECTOR_LEVEL, DEFAULT_SECTOR_LEVEL);
            return DEFAULT_SECTOR_LEVEL;
        }
        return level;
    }
}
