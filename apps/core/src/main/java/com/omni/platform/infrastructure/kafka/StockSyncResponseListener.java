package com.omni.platform.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.application.dtos.stock.StockSyncResponse;
import com.omni.platform.application.entities.UpdateLog;
import com.omni.platform.application.repositories.UpdateLogRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

    @KafkaListener(topics = "${spring.kafka.consumer.topic.stock-sync-status:stock-sync-status}",
                   groupId = "${spring.kafka.consumer.group-id:platform-group}")
    public void handleSyncStatus(ConsumerRecord<String, String> record) {
        try {
            String json = record.value();
            StockSyncResponse response = objectMapper.readValue(json, StockSyncResponse.class);

            UpdateLog log = new UpdateLog();
            log.setSymbol(response.getSymbol());
            log.setStatus(response.getStatus());
            log.setRecordsInserted(response.getRecordsInserted());
            log.setRecordsUpdated(response.getRecordsUpdated());
            log.setRecordsSkipped(response.getRecordsSkipped());
            log.setTotalRecords(response.getTotalRecords());
            log.setDurationMs(response.getDurationMs());
            log.setErrorMessage(response.getErrorMessage());

            updateLogRepository.save(log);
        } catch (Exception e) {
            // In production you would log this error appropriately
            throw new RuntimeException("Failed to process stock-sync-status message", e);
        }
    }
}