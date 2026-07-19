package com.omni.platform.modules.scheduler.consumers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.Symbol;
import com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage;
import com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage.SymbolRecord;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.AbstractKafkaSubscriptionLogger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolUpsertConsumer extends AbstractKafkaSubscriptionLogger {

    private final SymbolRepository symbolRepository;
    private final JobService jobService;
    private final JsonMapper jsonMapper;

    @Value("${kafka.topics.topic-upsert-symbols}")
    private String upsertSymbolsTopic;

    @Override
    protected String topicName() {
        return upsertSymbolsTopic;
    }

    @KafkaListener(topics = "${kafka.topics.topic-upsert-symbols}", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void handleSymbolUpsert(ConsumerRecord<String, String> record) {
        try {
            SymbolUpsertMessage event = jsonMapper.readValue(record.value(), SymbolUpsertMessage.class);
            applyUpsert(event);
        } catch (Exception e) {
            log.error("Failed to process symbol-upsert message [{}]: {}", record.key(), e.getMessage());
            throw new RuntimeException("Failed to process symbol-upsert message", e);
        }
    }

    private void applyUpsert(SymbolUpsertMessage event) {
        List<SymbolRecord> symbols = event.symbols();

        if (symbols == null || symbols.isEmpty()) {
            log.warn(
                    "Received empty symbol upsert batch for [{}] (jobDefinitionId={}, executionId={}, parentExecutionId={}), skipping to avoid mass deactivation",
                    event.exchange(), jobDefinitionId(event), executionId(event), parentExecutionId(event));
            return;
        }

        log.info("Applying symbol upsert batch for [{}]: {} records (expected {})",
                event.exchange(), event.actualCount(), event.expectedCount());

        int successfulUpserts = 0;
        int failedUpserts = 0;

        for (SymbolRecord r : symbols) {
            JobExecutionHistory childExecution = createChildExecutionIfTracked(event, r);

            try {
                String metaJson = jsonMapper.writeValueAsString(toMeta(r));
                symbolRepository.upsertOne(r.code(), r.exchange(), metaJson);
                successfulUpserts++;

                if (childExecution != null) {
                    jobService.markExecutionSuccess(
                            childExecution,
                            1,
                            0,
                            Instant.now(),
                            toExecutionMeta(event, r));
                }
            } catch (Exception exc) {
                failedUpserts++;
                if (childExecution != null) {
                    jobService.markExecutionFailed(
                            childExecution,
                            exc.getMessage(),
                            Instant.now(),
                            toExecutionMeta(event, r));
                }
                log.error("Failed to upsert symbol [{}]: {}", symbolKey(event, r), exc.getMessage(), exc);
            }
        }

        Set<String> incomingCodes = symbols.stream()
                .map(r -> r.code())
                .collect(Collectors.toSet());
        symbolRepository.deactivateMissing(event.exchange(), incomingCodes);

        UUID parentExecutionId = parentExecutionId(event);
        if (parentExecutionId != null) {
            jobService.aggregateParentExecution(parentExecutionId);
        }

        log.info(
                "Symbol upsert complete for [{}]: success={} failed={} deactivatedCheck against {} codes",
                event.exchange(), successfulUpserts, failedUpserts, incomingCodes.size());

        if (failedUpserts > 0) {
            throw new IllegalStateException(
                    "Symbol upsert batch completed with " + failedUpserts + " failed records for " + event.exchange());
        }
    }

    private JobExecutionHistory createChildExecutionIfTracked(SymbolUpsertMessage event, SymbolRecord record) {
        UUID parentExecutionId = parentExecutionId(event);
        if (parentExecutionId == null) {
            return null;
        }

        return jobService.createSymbolChildExecution(
                parentExecutionId,
                symbolKey(event, record),
                toExecutionMeta(event, record),
                Instant.now());
    }

    private String symbolKey(SymbolUpsertMessage event, SymbolRecord record) {
        String exchange = !isBlank(record.exchange()) ? record.exchange() : event.exchange();
        return exchange + "-" + record.code();
    }

    private Map<String, Object> toExecutionMeta(SymbolUpsertMessage event, SymbolRecord record) {
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "symbolKey", symbolKey(event, record));
        putIfPresent(meta, "jobDefinitionId", jobDefinitionId(event));
        putIfPresent(meta, "executionId", executionId(event));
        putIfPresent(meta, "parentExecutionId", parentExecutionId(event));
        putIfPresent(meta, "exchange", !isBlank(record.exchange()) ? record.exchange() : event.exchange());
        putIfPresent(meta, "code", record.code());
        putIfPresent(meta, "expectedCount", event.expectedCount());
        putIfPresent(meta, "actualCount", event.actualCount());
        putIfPresent(meta, "detectedAt", event.detectedAt().toString());
        putIfPresent(meta, "sectorTaxonomy", record.sectorTaxonomy());
        putIfPresent(meta, "sectorLevel", record.sectorLevel());
        putIfPresent(meta, "sourceSectorCode", record.sourceSectorCode());
        putIfPresent(meta, "classificationUpdatedAt", record.classificationUpdatedAt());
        return meta;
    }

    private Map<String, Object> toMeta(SymbolRecord record) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (record.meta() != null) {
            record.meta().forEach((key, value) -> putIfPresent(meta, key, value));
        }

        putIfPresent(meta, "companyName", record.companyName());
        putIfPresent(meta, "listedDate", record.listedDate());
        putIfPresent(meta, "sectorTaxonomy", record.sectorTaxonomy());
        putIfPresent(meta, "sectorLevel", record.sectorLevel());
        putIfPresent(meta, "sourceSectorCode", record.sourceSectorCode());
        putIfPresent(meta, "sectorLv1Code", record.sectorLv1Code());
        putIfPresent(meta, "sectorLv2Code", record.sectorLv2Code());
        putIfPresent(meta, "sectorLv3Code", record.sectorLv3Code());
        putIfPresent(meta, "sectorLv4Code", record.sectorLv4Code());
        putIfPresent(meta, "classificationUpdatedAt", record.classificationUpdatedAt());

        meta.keySet().retainAll(Symbol.META_JSON_ALLOWED_KEYS);
        return meta;
    }

    private void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, String.valueOf(value));
        }
    }

    private UUID jobDefinitionId(SymbolUpsertMessage event) {
        return event.jobDefinitionId() != null ? event.jobDefinitionId() : event.jobId();
    }

    private UUID executionId(SymbolUpsertMessage event) {
        return event.executionId() != null ? event.executionId() : event.logId();
    }

    private UUID parentExecutionId(SymbolUpsertMessage event) {
        if (event.parentExecutionId() != null) {
            return event.parentExecutionId();
        }
        return event.logId() != null ? event.logId() : event.executionId();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}