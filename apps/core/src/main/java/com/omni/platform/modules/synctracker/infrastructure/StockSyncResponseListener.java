package com.omni.platform.modules.synctracker.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.synctracker.dtos.StockSyncResponse;
import com.omni.platform.modules.synctracker.entities.UpdateLog;
import com.omni.platform.modules.synctracker.repositories.UpdateLogRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jetbrains.annotations.NotNull;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listener for {@code stock-sync-status} Kafka topic.
 * Persists the sync result into the {@code update_log} table.
 */
@Component
public class StockSyncResponseListener {

    private final UpdateLogRepository updateLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockSyncResponseListener(UpdateLogRepository updateLogRepository) {
        this.updateLogRepository = updateLogRepository;
    }

    @NotNull
    private static UpdateLog getUpdateLog(StockSyncResponse response) {
        UpdateLog log = new UpdateLog();
        log.setSymbol(response.getSymbol());
        log.setStatus(response.getStatus());
        log.setRecordsInserted(response.getRecordsInserted());
        log.setRecordsUpdated(response.getRecordsUpdated());
        log.setRecordsSkipped(response.getRecordsSkipped());
        log.setTotalRecords(response.getTotalRecords());
        log.setDurationMs(response.getDurationMs());
        log.setErrorMessage(response.getErrorMessage());
        return log;
    }

    @KafkaListener(topics = "${spring.kafka.consumer.topic.stock-sync-status:stock-sync-status}",
            groupId = "${spring.kafka.consumer.group-id:platform-group}")
    public void handleSyncStatus(ConsumerRecord<String, String> record) {
        try {
            String json = record.value();
            StockSyncResponse response = objectMapper.readValue(json, StockSyncResponse.class);

            UpdateLog log = getUpdateLog(response);

            updateLogRepository.save(log);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process stock-sync-status message", e);
        }
    }
}