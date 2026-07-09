package com.omni.platform.shared.infrastructure.minio.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;

@Configuration
public class MinioConfig {

    private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

    @Value("${minio.endpoint}")
    private String minIOEndpoint;

    @Value("${minio.access-key}")
    private String minIOAccessKey;

    @Value("${minio.secret-key}")
    private String minIOSecretKey;

    @Bean
    public MinioClient MinioClient() {
        logger.info("Bean MinIO created");
        return MinioClient.builder()
                .endpoint(normalizedEndpoint())
                .credentials(minIOAccessKey, minIOSecretKey)
                .build();
    }

    private String normalizedEndpoint() {
        if (minIOEndpoint.startsWith("http://") || minIOEndpoint.startsWith("https://")) {
            return minIOEndpoint;
        }
        return "http://" + minIOEndpoint;
    }
}
