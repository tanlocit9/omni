package com.omni.platform.modules.scheduler.metadata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Opt-in collection; only separately enrolled executions gain completion authority. */
@Component
@ConditionalOnProperty(name = "app.output-registration.shadow-enabled", havingValue = "true")
public class OutputRegistrationConsumer {
    private final OutputRegistrationStore store;
    private final WorkerCompletionStore completions;
    private final tools.jackson.databind.json.JsonMapper json;

    public OutputRegistrationConsumer(
            OutputRegistrationStore store,
            WorkerCompletionStore completions,
            tools.jackson.databind.json.JsonMapper json) {
        this.store = store;
        this.completions = completions;
        this.json = json;
    }

    @KafkaListener(
            topics = "topic-register-output-metadata-v1",
            groupId = "platform-output-registration-shadow-v1",
            containerFactory = "outputRegistrationKafkaListenerContainerFactory")
    public void accept(String payload) {
        OutputRegistrationEvent.require(
                payload != null
                        && payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                <= OutputRegistrationEvent.MAX_BYTES,
                "Registration/completion event exceeds 256 KiB");
        if (json.readTree(payload).has("kind")) completions.accept(payload);
        else store.accept(payload);
    }
}
