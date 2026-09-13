package com.omni.platform.modules.notifications.consumers;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.SignalChangedNotificationMessage;
import com.omni.platform.modules.notifications.events.SignalChangedNotificationEvent;
import com.omni.platform.shared.infrastructure.kafka.AbstractConsumer;

import tools.jackson.databind.json.JsonMapper;

@Component
public class SignalChangedNotificationConsumer extends AbstractConsumer {

    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper;
    private final String notificationStrategy;

    @Value("${kafka.topics.topic-signal-notifications}")
    private String topic;

    public SignalChangedNotificationConsumer(
            ApplicationEventPublisher eventPublisher,
            JsonMapper jsonMapper,
            @Value("${app.notifications.signal-strategy:CONFIRMED_TREND_EQUALS}") String notificationStrategy) {
        super(eventPublisher);
        this.eventPublisher = eventPublisher;
        this.jsonMapper = jsonMapper;
        this.notificationStrategy = notificationStrategy;
    }

    @Override
    protected String topicName() {
        return topic;
    }

    @KafkaListener(
            topics = "${kafka.topics.topic-signal-notifications}",
            groupId = "${app.notifications.signal-consumer-group:platform-signal-notifications-v1}")
    public void handle(ConsumerRecord<String, String> record) {
        try {
            SignalChangedNotificationMessage message = jsonMapper.readValue(
                    record.value(), SignalChangedNotificationMessage.class);
            validate(message);
            if (!notificationStrategy.equalsIgnoreCase(message.strategy())) {
                return;
            }
            eventPublisher.publishEvent(new SignalChangedNotificationEvent(
                    message.executionId(), message.parentExecutionId(), message.symbolKey(), message.previousSignal(),
                    message.newSignal(), message.price(), message.signalDate(), message.reasonCodes(), message.score(),
                    message.strategy(), message.timeframe(), message.createdAt(), message.metadata()));
        } catch (Exception exc) {
            publishMessageProcessingFailed(record, exc);
            throw new RuntimeException("Failed to process signal notification", exc);
        }
    }

    private void validate(SignalChangedNotificationMessage message) {
        if (message == null || !"SIGNAL_CHANGED".equals(message.type())
                || (!message.signalChanged() && !message.isNewSignalDate())
                || message.executionId() == null || message.parentExecutionId() == null
                || isBlank(message.symbolKey()) || isBlank(message.newSignal()) || message.createdAt() == null) {
            throw new IllegalArgumentException("Invalid SIGNAL_CHANGED notification contract");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
