package com.omni.platform.modules.scheduler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.CachedManifestReader;
import com.omni.platform.modules.scheduler.dependencies.ManifestReader;
import com.omni.platform.modules.scheduler.dependencies.MinioManifestReader;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfig {
    @Bean
    public MinioClient minioClient(
            @Value("${app.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.minio.access-key:test-access-key}") String accessKey,
            @Value("${app.minio.secret-key:test-secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public ObjectMapper legacyObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public ManifestReader manifestReader(
            MinioClient minioClient,
            ObjectMapper legacyObjectMapper,
            @Value("${app.minio.bucket:omni}") String bucket
    ) {
        MinioManifestReader delegate = new MinioManifestReader(minioClient, bucket, legacyObjectMapper);
        return new CachedManifestReader(delegate); // default: 60s TTL, 500 max entries
    }
}
