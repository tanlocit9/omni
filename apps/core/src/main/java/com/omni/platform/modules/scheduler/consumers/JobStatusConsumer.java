package com.omni.platform.modules.scheduler.consumers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.AbstractKafkaSubscriptionLogger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobStatusConsumer extends AbstractKafkaSubscriptionLogger {

    private final JobExecutionHistoryRepository historyRepository;
    private final JobService jobService;
    private final JsonMapper jsonMapper;

    @Value("${kafka.topics.topic-sync-job-status}")
    private String jobStatusTopic;

    @Override
    protected String topicName() {
        return jobStatusTopic;
    }

    @KafkaListener(topics = "${kafka.topics.topic-sync-job-status}", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void handleSyncStatus(ConsumerRecord<String, String> record) {
        try {
            log.info("JobStatusConsumer received topic={} partition={} offset={} key={} timestamp={} payload={}",
                    record.topic(), record.partition(), record.offset(), record.key(), record.timestamp(),
                    record.value());
            JobStatusMessage response = jsonMapper.readValue(record.value(), JobStatusMessage.class);
            applyToLog(response);
        } catch (Exception e) {
            log.error(
                    "Failed to process stock-sync-status message topic={} partition={} offset={} key={} payload={}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(), record.value(), e.getMessage(),
                    e);
            throw new RuntimeException("Failed to process stock-sync-status message", e);
        }
    }

    private void applyToLog(JobStatusMessage response) {
        UUID executionId = UUID.fromString(response.executionId());
        JobExecutionHistory history = historyRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("JobExecutionHistory not found: " + executionId));

        history.setStatus(resolveStatus(response.status()));
        history.setError(response.errorMessage());
        history.setStartedAt(response.startedAt());
        history.setFinishedAt(response.finishedAt());
        history.setRecordsSynced(resolveRecordsProcessed(response));
        history.setNewOffset(response.newOffset());
        history.setMetaJson(buildMetaJson(response));

        historyRepository.save(history);

        if (response.parentExecutionId() != null) {
            jobService.aggregateParentExecution(UUID.fromString(response.parentExecutionId()));
        }
    }

    private Map<String, Object> buildMetaJson(JobStatusMessage response) {
        Map<String, Object> meta = new HashMap<>();
        if (response.metaJson() != null) {
            meta.putAll(response.metaJson());
        }

        putIfPresent(meta, "symbolKey", response.symbolKey());
        putIfPresent(meta, "jobDefinitionId", response.jobDefinitionId());
        putIfPresent(meta, "executionId", response.executionId());
        putIfPresent(meta, "parentExecutionId", response.parentExecutionId());
        putIfPresent(meta, "durationMs", response.durationMs());
        putIfPresent(meta, "recordsProcessed", response.recordsProcessed());
        return meta;
    }

    private JobStatus resolveStatus(String status) {
        JobStatus resolved = JobStatus.valueOf(status.toUpperCase());
        return resolved == JobStatus.ERROR ? JobStatus.FAILED : resolved;
    }

    private int resolveRecordsProcessed(JobStatusMessage response) {
        if (response.recordsProcessed() != null) {
            return response.recordsProcessed();
        }
        Integer recordsProcessed = getOptionalIntMetaValue(response, "recordsProcessed");
        if (recordsProcessed != null) {
            return recordsProcessed;
        }
        Integer recordsInserted = getOptionalIntMetaValue(response, "recordsInserted");
        return recordsInserted == null ? 0 : recordsInserted;
    }

    private Integer getOptionalIntMetaValue(JobStatusMessage response, String key) {
        if (response.metaJson() == null) {
            return null;
        }

        Object value = response.metaJson().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Integer.parseInt(stringValue);
        }

        return null;
    }

    private void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, String.valueOf(value));
        }
    }
}
