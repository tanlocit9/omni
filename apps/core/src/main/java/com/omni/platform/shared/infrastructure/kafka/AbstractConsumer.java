package com.omni.platform.shared.infrastructure.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractConsumer extends AbstractKafkaSubscriptionLogger {

    private static final int MAX_FAILURE_NOTIFICATION_KEYS = 10_000;
    private static final Set<String> PUBLISHED_FAILURE_NOTIFICATION_KEYS = ConcurrentHashMap.newKeySet();

    private final ApplicationEventPublisher eventPublisher;

    protected void publishMessageProcessingFailed(ConsumerRecord<String, String> record, Exception exc) {
        String notificationKey = failureNotificationKey(record, exc);
        if (!PUBLISHED_FAILURE_NOTIFICATION_KEYS.add(notificationKey)) {
            log.warn(
                    "Suppressing duplicate message processing failure notification consumer={} topic={} partition={} offset={} key={} exception={}: {}",
                    consumerName(), record.topic(), record.partition(), record.offset(), record.key(),
                    exc.getClass().getSimpleName(), exc.getMessage(), exc);
            return;
        }
        trimFailureNotificationKeysIfNeeded();

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

    private String failureNotificationKey(ConsumerRecord<String, String> record, Exception exc) {
        return consumerName()
                + "|" + record.topic()
                + "|" + record.partition()
                + "|" + record.offset()
                + "|" + record.key()
                + "|" + exc.getClass().getName();
    }

    private void trimFailureNotificationKeysIfNeeded() {
        if (PUBLISHED_FAILURE_NOTIFICATION_KEYS.size() <= MAX_FAILURE_NOTIFICATION_KEYS) {
            return;
        }
        PUBLISHED_FAILURE_NOTIFICATION_KEYS.clear();
        log.info("Cleared message processing failure notification de-duplication cache after reaching {} entries",
                MAX_FAILURE_NOTIFICATION_KEYS);
    }
}
