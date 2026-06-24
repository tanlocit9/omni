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
        log.info("Publishing to topic [{}] key [{}] payload: {}", topic, key, payload);

        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish job [{}]: {}", key, ex.getMessage());
                    } else {
                        log.info("Published job [{}] to topic [{}]", key, topic);
                    }
                });
    }
}
