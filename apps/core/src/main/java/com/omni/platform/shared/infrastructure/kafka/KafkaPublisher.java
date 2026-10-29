package com.omni.platform.shared.infrastructure.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    public void publish(String topic, String key, Object message) {
        String payload = serialize(message);
        log.info(
                "Attempting to publish Kafka message topic [{}] key [{}] payloadBytes [{}]",
                topic,
                key,
                payload.getBytes(StandardCharsets.UTF_8).length);

        kafkaTemplate
                .send(topic, key, payload)
                .whenComplete(
                        (result, ex) -> {
                            if (ex != null) {
                                log.error(
                                        "Failed to publish Kafka message topic [{}] key [{}]: {}",
                                        topic,
                                        key,
                                        ex.getMessage(),
                                        ex);
                            } else {
                                log.info(
                                        "Published Kafka message topic [{}] key [{}] partition [{}] offset [{}]",
                                        topic,
                                        key,
                                        result.getRecordMetadata().partition(),
                                        result.getRecordMetadata().offset());
                            }
                        });
    }

    @SuppressWarnings("unchecked")
    public String serialize(Object message) {
        if (message instanceof EnvelopedMessage envelope) {
            Map<String, Object> wire =
                    new LinkedHashMap<>(jsonMapper.convertValue(envelope.body(), Map.class));
            for (var field : envelope.additionalFields().entrySet()) {
                if (wire.containsKey(field.getKey()))
                    throw new IllegalArgumentException(
                            "Envelope cannot replace body field " + field.getKey());
                wire.put(field.getKey(), field.getValue());
            }
            return jsonMapper.writeValueAsString(wire);
        }
        return jsonMapper.writeValueAsString(message);
    }

    public void publishSerializedAndWait(
            String topic, String key, String payload, Duration timeout) {
        try {
            kafkaTemplate.send(topic, key, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Published outbox message topic [{}] key [{}]", topic, key);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Kafka publish failed for topic " + topic + " key " + key, exception);
        }
    }
}
