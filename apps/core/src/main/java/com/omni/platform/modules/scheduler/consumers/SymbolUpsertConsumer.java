package com.omni.platform.modules.scheduler.consumers;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage;
import com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage.SymbolRecord;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolUpsertConsumer {

    private final SymbolRepository symbolRepository;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "${kafka.topics.topic-upsert-symbols:topic-upsert-symbols}", groupId = "${spring.kafka.consumer.group-id:platform-group}")
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
                    "Received empty symbol upsert batch for [{}] (jobId={}, logId={}), skipping to avoid mass deactivation",
                    event.exchange(), event.jobId(), event.logId());
            return;
        }

        log.info("Applying symbol upsert batch for [{}]: {} records (expected {})",
                event.exchange(), event.actualCount(), event.expectedCount());

        for (SymbolRecord r : symbols) {
            String metaJson = jsonMapper.writeValueAsString(r.meta());
            symbolRepository.upsertOne(r.code(), r.exchange(), metaJson);
        }

        Set<String> incomingCodes = symbols.stream()
                .map(r -> r.code())
                .collect(Collectors.toSet());
        symbolRepository.deactivateMissing(event.exchange(), incomingCodes);

        log.info("Symbol upsert complete for [{}]: upserted={} deactivatedCheck against {} codes",
                event.exchange(), symbols.size(), incomingCodes.size());
    }
}