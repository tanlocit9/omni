package com.omni.platform.modules.scheduler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.CachedManifestReader;
import com.omni.platform.modules.scheduler.dependencies.ManifestReader;
import com.omni.platform.modules.scheduler.dependencies.MinioManifestReader;
import io.minio.MinioClient;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SchedulerProperties.class, ManualTriggerProperties.class})
public class SchedulerConfig {
    @Bean
    public MinioClient minioClient(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey
    ) {
        // Configure OkHttp dispatcher with expanded limits for parallel dependency evaluation
        // Default limits (maxRequests=64, maxRequestsPerHost=5) were insufficient for
        // concurrent manifest reads during dependency checks, causing executor rejections
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(256);           // Up from default 64
        dispatcher.setMaxRequestsPerHost(128);    // Up from default 5
        
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .build();
        
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
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
            @Value("${app.minio.bucket}") String bucket
    ) {
        MinioManifestReader delegate = new MinioManifestReader(minioClient, bucket, legacyObjectMapper);
        return new CachedManifestReader(delegate); // default: 60s TTL, 500 max entries
    }
}
