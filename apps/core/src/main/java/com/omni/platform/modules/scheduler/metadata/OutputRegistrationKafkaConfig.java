package com.omni.platform.modules.scheduler.metadata;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** No skip/recover/DLT path: failed partitions retry without committing past the record. */
@Configuration
@ConditionalOnProperty(name = "app.output-registration.shadow-enabled", havingValue = "true")
public class OutputRegistrationKafkaConfig {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> outputRegistrationKafkaListenerContainerFactory(
            @Value("${kafka.bootstrap-servers}") String bootstrap) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var errors = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        // Conversion/validation/DB errors all retain the failing record; no default fatal recovery.
        errors.setClassifications(Map.of(Exception.class, true), true);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
