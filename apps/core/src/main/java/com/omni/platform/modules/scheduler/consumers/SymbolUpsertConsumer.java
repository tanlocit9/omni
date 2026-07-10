package com.omni.platform.modules.scheduler.consumers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.Sector;
import com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage;
import com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage.SymbolRecord;
import com.omni.platform.modules.scheduler.repositories.SectorRepository;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolUpsertConsumer {

    private final SymbolRepository symbolRepository;
    private final SectorRepository sectorRepository;
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
            UUID sectorId = resolveSectorId(r);
            String metaJson = jsonMapper.writeValueAsString(toMeta(r));
            symbolRepository.upsertOne(r.code(), r.exchange(), sectorId, metaJson);
        }

        Set<String> incomingCodes = symbols.stream()
                .map(r -> r.code())
                .collect(Collectors.toSet());
        symbolRepository.deactivateMissing(event.exchange(), incomingCodes);

        log.info("Symbol upsert complete for [{}]: upserted={} deactivatedCheck against {} codes",
                event.exchange(), symbols.size(), incomingCodes.size());
    }

    private UUID resolveSectorId(SymbolRecord record) {
        if (isBlank(record.sectorCode())) {
            return null;
        }

        return sectorRepository.findByCode(record.sectorCode())
                .map(sector -> updateSectorMetadata(sector, record).getId())
                .orElseGet(() -> {
                    log.warn("Unknown canonical sector code [{}] for symbol [{}], preserving existing sector relation.",
                            record.sectorCode(), record.code());
                    return null;
                });
    }

    private Sector updateSectorMetadata(Sector sector, SymbolRecord record) {
        if (isBlank(record.sourceSectorCode()) || isBlank(record.sectorTaxonomy()) || record.sectorLevel() == null) {
            return sector;
        }

        if (isBlank(sector.getSourceCode())) {
            sector.setSourceCode(record.sourceSectorCode());
        }
        if (isBlank(sector.getTaxonomy())) {
            sector.setTaxonomy(record.sectorTaxonomy());
        }
        if (sector.getTaxonomyLevel() == null) {
            sector.setTaxonomyLevel(record.sectorLevel());
        }
        if (!isBlank(record.sourceSectorNameVi())) {
            sector.setNameVi(record.sourceSectorNameVi());
        }
        if (!isBlank(record.sourceSectorNameEn())) {
            sector.setNameEn(record.sourceSectorNameEn());
        }
        return sectorRepository.save(sector);
    }

    private Map<String, Object> toMeta(SymbolRecord record) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (record.meta() != null) {
            meta.putAll(record.meta());
        }

        putIfPresent(meta, "type", record.type());
        putIfPresent(meta, "status", record.status());
        putIfPresent(meta, "isin", record.isin());
        putIfPresent(meta, "companyId", record.companyId());
        putIfPresent(meta, "companyName", record.companyName());
        putIfPresent(meta, "listedDate", record.listedDate());
        putIfPresent(meta, "sectorCode", record.sectorCode());
        putIfPresent(meta, "sectorTaxonomy", record.sectorTaxonomy());
        putIfPresent(meta, "sectorLevel", record.sectorLevel());
        putIfPresent(meta, "sourceSectorCode", record.sourceSectorCode());
        putIfPresent(meta, "sourceSectorNameVi", record.sourceSectorNameVi());
        putIfPresent(meta, "sourceSectorNameEn", record.sourceSectorNameEn());
        putIfPresent(meta, "icbLv1Code", record.icbLv1Code());
        putIfPresent(meta, "icbLv1NameVi", record.icbLv1NameVi());
        putIfPresent(meta, "icbLv1NameEn", record.icbLv1NameEn());
        putIfPresent(meta, "icbLv2Code", record.icbLv2Code());
        putIfPresent(meta, "icbLv2NameVi", record.icbLv2NameVi());
        putIfPresent(meta, "icbLv2NameEn", record.icbLv2NameEn());
        putIfPresent(meta, "icbLv3Code", record.icbLv3Code());
        putIfPresent(meta, "icbLv3NameVi", record.icbLv3NameVi());
        putIfPresent(meta, "icbLv3NameEn", record.icbLv3NameEn());
        putIfPresent(meta, "icbLv4Code", record.icbLv4Code());
        putIfPresent(meta, "icbLv4NameVi", record.icbLv4NameVi());
        putIfPresent(meta, "icbLv4NameEn", record.icbLv4NameEn());
        putIfPresent(meta, "classificationUpdatedAt", record.classificationUpdatedAt());

        return meta;
    }

    private void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, value);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}