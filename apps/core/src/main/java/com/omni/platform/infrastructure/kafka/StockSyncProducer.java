package com.omni.platform.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.application.dtos.stock.StockSyncRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Service responsible for publishing {@link StockSyncRequest} messages to the {@code stock-sync} Kafka topic.
 */
@Service
public class StockSyncProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.producer.topic.stock-sync:stock-sync}")
    private String stockSyncTopic;

    public StockSyncProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Publish a sync request.
     *
     * @param request the request payload
     */
    public void sendSyncRequest(StockSyncRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            // Using the symbol as key for partitioning (optional)
            kafkaTemplate.send(stockSyncTopic, request.getSymbol(), json);
        } catch (JsonProcessingException e) {
            // In a real system you might want to log this or rethrow a custom exception
            throw new RuntimeException("Failed to serialize StockSyncRequest", e);
        }
    }
}