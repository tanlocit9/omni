package com.omni.platform.modules.scheduler.metadata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Disabled shadow collection only. Exceptions must reach the partition error handler. */
@Component
@ConditionalOnProperty(name = "app.output-registration.shadow-enabled", havingValue = "true")
public class OutputRegistrationConsumer {
    private final OutputRegistrationStore store;

    public OutputRegistrationConsumer(OutputRegistrationStore store) { this.store = store; }

    @KafkaListener(topics = "topic-register-output-metadata-v1", groupId = "platform-output-registration-shadow-v1",
            containerFactory = "outputRegistrationKafkaListenerContainerFactory")
    public void accept(String payload) { store.accept(payload); }
}
