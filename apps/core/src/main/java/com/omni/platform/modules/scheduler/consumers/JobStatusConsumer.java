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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobStatusConsumer {

    private final JobExecutionHistoryRepository historyRepository;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "${kafka.topics.topic-sync-job-status:topic-sync-job-status}", groupId = "${spring.kafka.consumer.group-id:platform-group}")
    @Transactional
    public void handleSyncStatus(ConsumerRecord<String, String> record) {
        try {
            JobStatusMessage response = jsonMapper.readValue(record.value(), JobStatusMessage.class);
            applyToLog(response);
        } catch (Exception e) {
            log.error("Failed to process stock-sync-status message [{}]: {}", record.key(), e.getMessage());
            throw new RuntimeException("Failed to process stock-sync-status message", e);
        }
    }

    private void applyToLog(JobStatusMessage response) {
        UUID logId = UUID.fromString(response.logId());
        JobExecutionHistory history = historyRepository.findById(logId)
                .orElseThrow(() -> new IllegalStateException("JobExecutionHistory not found: " + logId));

        history.setStatus(JobStatus.valueOf(response.status().toUpperCase()));
        history.setError(response.errorMessage());
        history.setStartedAt(response.startedAt());
        history.setFinishedAt(response.finishedAt());
        history.setRecordsSynced(response.recordsInserted());
        history.setNewOffset(response.newOffset());
        history.setMetaJson(buildMetaJson(response));

        historyRepository.save(history);
    }

    private Map<String, Object> buildMetaJson(JobStatusMessage response) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("symbolKey", response.symbolKey());
        meta.put("totalRecords", response.totalRecords());
        meta.put("durationMs", response.durationMs());
        return meta;
    }
}