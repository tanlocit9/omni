package com.omni.platform.modules.notifications.configs;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({
        TelegramNotificationProperties.class,
        AnalyzerClientProperties.class
})
public class NotificationConfig {

    @Bean
    RestClient telegramRestClient(TelegramNotificationProperties properties) {
        return RestClient.builder().baseUrl(properties.resolvedApiBaseUrl()).build();
    }

    @Bean("analyzerRestClient")
    RestClient analyzerRestClient(AnalyzerClientProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.resolvedConnectTimeout());
        requestFactory.setReadTimeout(properties.resolvedReadTimeout());
        return RestClient.builder()
                .baseUrl(properties.resolvedBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    Clock notificationClock() {
        return Clock.systemUTC();
    }
}
