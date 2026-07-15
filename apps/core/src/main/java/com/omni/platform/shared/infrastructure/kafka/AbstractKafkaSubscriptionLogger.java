package com.omni.platform.shared.infrastructure.kafka;

import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractKafkaSubscriptionLogger {

    @Value("${spring.kafka.consumer.group-id}")
    private String consumerGroupId;

    @Value("${kafka.bootstrap-servers}")
    private String bootstrapServers;

    @PostConstruct
    protected void logKafkaSubscription() {
        log.info("{} subscribed to topic={} groupId={} bootstrapServers={}",
                consumerName(), topicName(), consumerGroupId, bootstrapServers);
    }

    protected String consumerName() {
        return getClass().getSimpleName();
    }

    protected abstract String topicName();
}
