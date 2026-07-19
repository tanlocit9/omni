package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SyncSymbolsJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SyncSymbolsJobProducer extends JobProducer {

    private static final List<String> DEFAULT_EXCHANGES = List.of("HOSE", "HNX", "UPCOM");

    @Value("${kafka.topics.topic-sync-symbols}")
    private String topic;

    private final SymbolRepository symbolRepository;

    public SyncSymbolsJobProducer(
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

        List<String> exchanges = extractExchanges(job.getConfigJson());
        Map<String, Integer> symbolCountsByExchange = symbolRepository.countAllActiveSymbolsGroupedByExchange().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Long) row[1]).intValue()));

        return exchanges.stream()
                .map(exchange -> {
                    Integer symbolCount = symbolCountsByExchange.getOrDefault(exchange, 0);
                    Map<String, Object> messageConfig = new java.util.HashMap<>(job.getConfigJson());
                    messageConfig.put(JobDefinitionConfig.CONFIG_KEY_SYMBOL_COUNT, symbolCount);
                    enrichSectorConfig(messageConfig);

                    JobExecutionHistory childJobExecutionHistory = jobService.createSymbolChildExecution(
                            jobExecutionHistory.getId(),
                            exchange,
                            messageConfig,
                            timestamps);

                    return new KafkaMessage(
                            exchange,
                            new SyncSymbolsJobMessage(
                                    job.getId(),
                                    childJobExecutionHistory.getId(),
                                    jobExecutionHistory.getId(),
                                    job.getSource().toString(),
                                    exchange,
                                    timestamps.truncatedTo(ChronoUnit.SECONDS),
                                    messageConfig));
                })
                .toList();
    }

    @Override
    protected void postPublish(
            JobDefinition job, Instant now) {

        log.info(
                "Published sync-symbols job [{}] for source [{}]",
                job.getId(),
                job.getSource());
    }

    private void enrichSectorConfig(Map<String, Object> messageConfig) {
        boolean includeSectorClassification = Boolean.TRUE.equals(
                messageConfig.get(JobDefinitionConfig.CONFIG_KEY_INCLUDE_SECTOR_CLASSIFICATION));

        if (!includeSectorClassification) {
            messageConfig.putIfAbsent(JobDefinitionConfig.CONFIG_KEY_INCLUDE_SECTOR_CLASSIFICATION, false);
            return;
        }
    }

    private List<String> extractExchanges(Map<String, Object> config) {
        if (config == null || !config.containsKey(JobDefinitionConfig.CONFIG_KEY_EXCHANGES)) {
            return DEFAULT_EXCHANGES;
        }
        Object raw = config.get(JobDefinitionConfig.CONFIG_KEY_EXCHANGES);
        if (!(raw instanceof List<?> list)) {
            log.warn("Job configJson has 'exchange' key but it's not a List: {}", raw);
            return DEFAULT_EXCHANGES;
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(v -> v.toString().toUpperCase())
                .toList();
    }
}