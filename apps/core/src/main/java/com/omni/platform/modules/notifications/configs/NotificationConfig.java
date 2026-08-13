package com.omni.platform.modules.notifications.configs;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TelegramNotificationProperties.class)
public class NotificationConfig {

    @Bean
    RestClient telegramRestClient(TelegramNotificationProperties properties) {
        return RestClient.builder().baseUrl(properties.resolvedApiBaseUrl()).build();
    }

    @Bean
    Clock notificationClock() {
        return Clock.systemUTC();
    }
}
