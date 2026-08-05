package com.omni.platform.shared.infrastructure.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    public void publish(String topic, String key, Object message) {
        String payload = jsonMapper.writeValueAsString(message);
        log.info("Attempting to publish Kafka message topic [{}] key [{}] payloadBytes [{}]", topic, key,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish Kafka message topic [{}] key [{}]: {}", topic, key, ex.getMessage(), ex);
                    } else {
                        log.info("Published Kafka message topic [{}] key [{}] partition [{}] offset [{}]", topic, key,
                                result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                    }
                });
    }
}
