package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SymbolJobMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SyncStockPriceJobProducer extends JobProducer {

    private final SymbolRepository symbolRepository;
    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;

    @Value("${spring.kafka.topics.topic-sync-stock-prices}")
    private String topic;

    public SyncStockPriceJobProducer(
            JobService jobService,
            KafkaPublisher kafkaPublisher,
            SymbolRepository symbolRepository,
            JobExecutionHistoryRepository jobExecutionHistoryRepository) {

        super(jobService, kafkaPublisher);

        this.symbolRepository = symbolRepository;
        this.jobExecutionHistoryRepository = jobExecutionHistoryRepository;
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
        List<String> sectors = extractSectors(job.getConfigJson());
        List<SymbolKeyProjection> symbols = symbolRepository.findBySectors(
                sectors.isEmpty() ? null : sectors.toArray(new String[0]));

        return symbols.stream()
                .map(symbol -> {
                    Instant fromOffset = jobExecutionHistoryRepository
                            .findLastOffset(job.getId(), symbol.symbolKey())
                            .map(offset -> parseOffset(offset, symbol.symbolKey()))
                            .orElse(null);

                    Map<String, Object> metadata = new HashMap<>();
                    if (job.getConfigJson() != null) {
                        metadata.putAll(job.getConfigJson());
                    }

                    JobExecutionHistory childJobExcutionHistory = jobService.createSymbolChildExecution(
                            jobExecutionHistory.getId(),
                            symbol.symbolKey(),
                            metadata,
                            timestamps);

                    return new KafkaMessage(
                            symbol.symbolKey(),
                            new SymbolJobMessage(
                                    job.getId(),
                                    childJobExcutionHistory.getId(),
                                    jobExecutionHistory.getId(),
                                    job.getSource().toString(),
                                    symbol.symbolKey(),
                                    fromOffset,
                                    timestamps.truncatedTo(ChronoUnit.SECONDS),
                                    metadata));
                })
                .toList();
    }

    @Override
    protected void postPublish(
            JobDefinition job, Instant now) {

        log.info(
                "Published sync job [{}] for source [{}]",
                job.getId(),
                job.getSource());
    }

    private Instant parseOffset(String offset, String symbolKey) {
        try {
            return Instant.parse(offset);
        } catch (DateTimeParseException instantParseException) {
            try {
                return LocalDate.parse(offset).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException localDateParseException) {
                log.warn(
                        "Ignoring invalid new_offset [{}] for symbol [{}]",
                        offset,
                        symbolKey,
                        localDateParseException);
                return null;
            }
        }
    }

    private List<String> extractSectors(Map<String, Object> config) {
        if (config == null || !config.containsKey(JobDefinitionConfig.CONFIG_KEY_SECTOR)) {
            return List.of();
        }
        Object raw = config.get(JobDefinitionConfig.CONFIG_KEY_SECTOR);
        if (!(raw instanceof List<?> list)) {
            log.warn("Job configJson has 'sector' key but it's not a List: {}", raw);
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(v -> v.toString().toUpperCase())
                .toList();
    }

}
