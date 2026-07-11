package com.omni.platform.shared.infrastructure.mapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonMapperConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return configuredJsonMapper();
    }

    @Bean
    public JsonMapper jsonMapper() {
        return configuredJsonMapper();
    }

    private JsonMapper configuredJsonMapper() {
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build();
    }
}