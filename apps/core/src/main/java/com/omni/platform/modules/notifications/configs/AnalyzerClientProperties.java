package com.omni.platform.modules.notifications.configs;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.analyzer")
public record AnalyzerClientProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:8000" : baseUrl;
    }

    public Duration resolvedConnectTimeout() {
        return connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    }

    public Duration resolvedReadTimeout() {
        return readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}
