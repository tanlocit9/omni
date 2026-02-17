package com.omni.platform.modules.minio.configs;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

  private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

  @Value("${min-io.url}")
  private String minIOUrl;
  @Value("${min-io.username}")
  private String minIOUsername;
  @Value("${min-io.password}")
  private String minIOPassword;

  @Bean
  public MinioClient MinioClient() {
    logger.info("Bean MinIO created");
    return MinioClient.builder()
        .endpoint(minIOUrl)
        .credentials(minIOUsername, minIOPassword)
        .build();
  }
}
