package com.omni.platform.modules.scheduler.consumers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.services.JobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobStatusConsumer {

    private final JobExecutionHistoryRepository historyRepository;
    private final JobService jobService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "${kafka.topics.topic-sync-job-status}", groupId = "${kafka.consumer.group-id:platform-group}")
    @Transactional
    public void handleSyncStatus(ConsumerRecord<String, String> record) {
        try {
            log.debug("Topic topic-sync-job-status received message: {}", record);
            JobStatusMessage response = jsonMapper.readValue(record.value(), JobStatusMessage.class);
            applyToLog(response);
        } catch (Exception e) {
            log.error("Failed to process stock-sync-status message [{}]: {}", record.key(), e.getMessage());
            throw new RuntimeException("Failed to process stock-sync-status message", e);
        }
    }

    private void applyToLog(JobStatusMessage response) {
        UUID executionId = UUID.fromString(response.executionId());
        JobExecutionHistory history = historyRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("JobExecutionHistory not found: " + executionId));

        history.setStatus(JobStatus.valueOf(response.status().toUpperCase()));
        history.setError(response.errorMessage());
        history.setStartedAt(response.startedAt());
        history.setFinishedAt(response.finishedAt());
        history.setRecordsSynced(getIntMetaValue(response, "recordsInserted"));
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
        return meta;
    }

    private int getIntMetaValue(JobStatusMessage response, String key) {
        if (response.metaJson() == null) {
            return 0;
        }

        Object value = response.metaJson().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Integer.parseInt(stringValue);
        }

        return 0;
    }

    private void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, String.valueOf(value));
        }
    }
}
