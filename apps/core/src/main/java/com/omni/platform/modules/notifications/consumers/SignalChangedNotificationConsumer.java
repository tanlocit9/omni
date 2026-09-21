package com.omni.platform.modules.notifications.consumers;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties.SignalFilterConfig;
import com.omni.platform.modules.notifications.dtos.SignalChangedNotificationMessage;
import com.omni.platform.modules.notifications.events.SignalChangedNotificationEvent;
import com.omni.platform.modules.notifications.services.NotificationOutboxService;
import com.omni.platform.modules.notifications.templates.SignalChangedNotificationTemplate;
import com.omni.platform.shared.infrastructure.kafka.AbstractConsumer;

import tools.jackson.databind.json.JsonMapper;

@Component
public class SignalChangedNotificationConsumer extends AbstractConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignalChangedNotificationConsumer.class);

    private final JsonMapper jsonMapper;
    private final SignalFilterConfig signalFilter;
    private final SignalChangedNotificationTemplate template;
    private final NotificationOutboxService outboxService;

    @Value("${kafka.topics.topic-signal-notifications}")
    private String topic;

    public SignalChangedNotificationConsumer(
            ApplicationEventPublisher eventPublisher,
            JsonMapper jsonMapper,
            TelegramNotificationProperties telegramProperties,
            SignalChangedNotificationTemplate template,
            NotificationOutboxService outboxService) {
        super(eventPublisher);
        this.jsonMapper = jsonMapper;
        this.template = template;
        this.outboxService = outboxService;
        this.signalFilter = telegramProperties.signalFilter() != null
                ? telegramProperties.signalFilter()
                : new SignalFilterConfig(null, null, null, null, null, null, null);
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
            
            // Apply comprehensive signal filters
            if (!passesFilters(message)) {
                log.debug("Signal notification filtered out: strategy={} symbolKey={} signal={} score={} timeframe={}",
                        message.strategy(), message.symbolKey(), message.newSignal(), message.score(), message.timeframe());
                return;
            }
            
            SignalChangedNotificationEvent event = new SignalChangedNotificationEvent(
                    message.executionId(), message.parentExecutionId(), message.symbolKey(), message.previousSignal(),
                    message.newSignal(), message.price(), message.signalDate(), message.reasonCodes(), message.score(),
                    message.strategy(), message.timeframe(), message.createdAt(), message.metadata());
            outboxService.enqueue(template.render(event), Instant.now());
        } catch (Exception exc) {
            publishMessageProcessingFailed(record, exc);
            throw new RuntimeException("Failed to process signal notification", exc);
        }
    }

    private boolean passesFilters(SignalChangedNotificationMessage message) {
        // Strategy filter
        if (!signalFilter.matchesStrategy(message.strategy())) {
            return false;
        }

        // Symbol filter
        if (!signalFilter.matchesSymbol(message.symbolKey())) {
            return false;
        }

        // Direction filter
        if (!signalFilter.matchesDirection(message.newSignal())) {
            return false;
        }

        // Timeframe filter
        if (!signalFilter.matchesTimeframe(message.timeframe())) {
            return false;
        }

        // Score threshold filter
        if (!signalFilter.matchesScore(message.score())) {
            return false;
        }

        return true;
    }

    private void validate(SignalChangedNotificationMessage message) {
        if (message == null || !"SIGNAL_CHANGED".equals(message.type())
                || !eligible(message)
                || message.executionId() == null || message.parentExecutionId() == null
                || isBlank(message.symbolKey()) || isBlank(message.newSignal()) || message.createdAt() == null) {
            throw new IllegalArgumentException("Invalid SIGNAL_CHANGED notification contract");
        }
    }

    private boolean eligible(SignalChangedNotificationMessage message) {
        if ("NO_DECISION".equalsIgnoreCase(message.newSignal())) {
            return false;
        }
        return message.signalChanged()
                || (message.isNewSignalDate()
                        && Boolean.TRUE.equals(signalFilter.enableNewSignalDate())
                        && "CONFIRMED_TREND_EQUALS".equalsIgnoreCase(message.strategy())
                        && "1d".equalsIgnoreCase(message.timeframe())
                        && message.metadata() != null
                        && Boolean.parseBoolean(String.valueOf(message.metadata().get("persisted"))));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
