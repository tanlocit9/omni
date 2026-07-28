package com.omni.platform.shared.infrastructure.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractConsumer extends AbstractKafkaSubscriptionLogger {

    private final ApplicationEventPublisher eventPublisher;

    protected void publishMessageProcessingFailed(ConsumerRecord<String, String> record, Exception exc) {
        publishOperationalNotification(
                NotificationSeverity.ERROR,
                consumerName() + " message processing failed",
                exc.getMessage(),
                kafkaMetadata(record, exc));
    }

    protected void publishInvalidMessage(ConsumerRecord<String, String> record, Exception exc) {
        publishOperationalNotification(
                NotificationSeverity.WARNING,
                consumerName() + " invalid message",
                exc.getMessage(),
                kafkaMetadata(record, exc));
    }

    protected void publishJobCompleted(String title, String message, Map<String, Object> metadata) {
        publishOperationalNotification(NotificationSeverity.INFO, title, message, metadata);
    }

    protected void publishJobFailed(String title, String message, Map<String, Object> metadata) {
        publishOperationalNotification(NotificationSeverity.ERROR, title, message, metadata);
    }

    protected void publishOperationalNotification(
            NotificationSeverity severity,
            String title,
            String message,
            Map<String, Object> metadata) {
        try {
            eventPublisher.publishEvent(new OperationalNotificationEvent(severity, title, message, metadata));
        } catch (Exception exc) {
            log.warn("Failed to publish operational notification event: {}", exc.getMessage(), exc);
        }
    }

    private Map<String, Object> kafkaMetadata(ConsumerRecord<String, String> record, Exception exc) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("consumer", consumerName());
        metadata.put("topic", record.topic());
        metadata.put("partition", record.partition());
        metadata.put("offset", record.offset());
        metadata.put("key", record.key());
        metadata.put("timestamp", record.timestamp());
        metadata.put("exception", exc.getClass().getSimpleName());
        return metadata;
    }
}
