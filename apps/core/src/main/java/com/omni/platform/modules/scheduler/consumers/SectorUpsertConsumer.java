package com.omni.platform.modules.scheduler.consumers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.messaging.SectorUpsertMessage;
import com.omni.platform.modules.scheduler.messaging.SectorUpsertMessage.SectorRecord;
import com.omni.platform.modules.scheduler.repositories.SectorRepository;
import com.omni.platform.shared.infrastructure.kafka.AbstractConsumer;
import com.omni.platform.shared.utils.MetadataUtils;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class SectorUpsertConsumer extends AbstractConsumer {

    private final SectorRepository sectorRepository;
    private final JsonMapper jsonMapper;

    public SectorUpsertConsumer(
            ApplicationEventPublisher eventPublisher,
            SectorRepository sectorRepository,
            JsonMapper jsonMapper) {
        super(eventPublisher);
        this.sectorRepository = sectorRepository;
        this.jsonMapper = jsonMapper;
    }

    @Value("${kafka.topics.topic-upsert-sectors}")
    private String upsertSectorsTopic;

    @Override
    protected String topicName() {
        return upsertSectorsTopic;
    }

    @KafkaListener(topics = "${kafka.topics.topic-upsert-sectors}", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void handleSectorUpsert(ConsumerRecord<String, String> record) {
        try {
            SectorUpsertMessage event = jsonMapper.readValue(record.value(), SectorUpsertMessage.class);
            applyUpsert(event);
        } catch (Exception e) {
            publishMessageProcessingFailed(record, e);
            log.error("Failed to process sector-upsert message [{}]: {}", record.key(), e.getMessage(), e);
            throw new RuntimeException("Failed to process sector-upsert message", e);
        }
    }

    private void applyUpsert(SectorUpsertMessage event) {
        List<SectorRecord> sectors = event.sectors();

        if (sectors == null || sectors.isEmpty()) {
            log.warn("Received empty sector upsert batch for [{}], skipping", event.exchange());
            return;
        }

        log.info("Applying sector upsert batch for [{}]: {} records (expected {})",
                event.exchange(), event.actualCount(), event.expectedCount());

        int failedUpserts = 0;
        List<String> codes = new ArrayList<>();
        List<String> nameVis = new ArrayList<>();
        List<String> nameEns = new ArrayList<>();
        List<String> taxonomies = new ArrayList<>();
        List<Integer> taxonomyLevels = new ArrayList<>();
        List<String> sourceCodes = new ArrayList<>();
        List<String> metaJsons = new ArrayList<>();

        for (SectorRecord sector : sectors) {
            if (isBlank(sector.sectorCode())) {
                failedUpserts++;
                log.warn("Skipping sector upsert with missing canonical sectorCode: {}", sector);
                continue;
            }

            try {
                codes.add(sector.sectorCode());
                nameVis.add(sector.sourceSectorNameVi());
                nameEns.add(sector.sourceSectorNameEn());
                taxonomies.add(sector.sectorTaxonomy());
                taxonomyLevels.add(sector.sectorLevel());
                sourceCodes.add(sector.sourceSectorCode());
                metaJsons.add(jsonMapper.writeValueAsString(toMeta(event, sector)));
            } catch (Exception exc) {
                failedUpserts++;
                log.error("Failed to prepare sector [{}] for batch upsert: {}", sector.sectorCode(), exc.getMessage(), exc);
            }
        }

        int successfulUpserts = codes.size();
        if (!codes.isEmpty()) {
            try {
                sectorRepository.upsertBatch(
                        codes.toArray(String[]::new),
                        nameVis.toArray(String[]::new),
                        nameEns.toArray(String[]::new),
                        taxonomies.toArray(String[]::new),
                        taxonomyLevels.toArray(Integer[]::new),
                        sourceCodes.toArray(String[]::new),
                        metaJsons.toArray(String[]::new));
            } catch (Exception exc) {
                failedUpserts += codes.size();
                successfulUpserts = 0;
                log.error("Failed to batch upsert {} sectors for [{}]: {}", codes.size(), event.exchange(), exc.getMessage(), exc);
            }
        }

        log.info("Sector upsert complete for [{}]: success={} failed={}",
                event.exchange(), successfulUpserts, failedUpserts);

        if (failedUpserts > 0) {
            throw new IllegalStateException(
                    "Sector upsert batch completed with " + failedUpserts + " failed records for " + event.exchange());
        }
    }

    private Map<String, Object> toMeta(SectorUpsertMessage event, SectorRecord record) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (record.meta() != null) {
            record.meta().forEach((key, value) -> MetadataUtils.putIfPresent(meta, key, value));
        }

        MetadataUtils.putIfPresent(meta, "exchange", event.exchange());
        MetadataUtils.putIfPresent(meta, "jobDefinitionId", event.jobDefinitionId() != null ? event.jobDefinitionId() : event.jobId());
        MetadataUtils.putIfPresent(meta, "executionId", event.executionId() != null ? event.executionId() : event.logId());
        MetadataUtils.putIfPresent(meta, "parentExecutionId", event.parentExecutionId());
        MetadataUtils.putIfPresent(meta, "expectedCount", event.expectedCount());
        MetadataUtils.putIfPresent(meta, "actualCount", event.actualCount());
        MetadataUtils.putIfPresent(meta, "detectedAt", event.detectedAt());
        MetadataUtils.putIfPresent(meta, "classificationUpdatedAt", record.classificationUpdatedAt());
        return meta;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
