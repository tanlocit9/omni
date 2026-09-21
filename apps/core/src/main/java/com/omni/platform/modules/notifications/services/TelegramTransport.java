package com.omni.platform.modules.notifications.services;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.telegram.TelegramRendering.Registry;

@Component
public class TelegramTransport {

    private static final String HTML_PARSE_MODE = "HTML";

    private final TelegramNotificationProperties properties;
    private final RestClient telegramRestClient;
    private final Registry rendererRegistry;

    public TelegramTransport(
            TelegramNotificationProperties properties,
            RestClient telegramRestClient,
            Registry rendererRegistry) {
        this.properties = properties;
        this.telegramRestClient = telegramRestClient;
        this.rendererRegistry = rendererRegistry;
    }

    public DeliveryResult deliver(NotificationRequest request) {
        if (!properties.isConfigured(request.channel())) {
            return DeliveryResult.permanentFailure("telegram_not_configured");
        }
        var rendered = rendererRegistry.render(request, 0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", properties.destination(request.channel()));
        payload.put("text", rendered.html());
        payload.put("parse_mode", HTML_PARSE_MODE);
        payload.put("disable_notification", rendered.disableNotification());
        try {
            telegramRestClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return DeliveryResult.delivered();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            Optional<Duration> retryAfter = parseRetryAfter(exception.getResponseHeaders(), Instant.now());
            if (status == 429 || status >= 500) {
                return DeliveryResult.retryable("telegram_http_" + status, retryAfter);
            }
            return DeliveryResult.permanentFailure("telegram_http_" + status);
        } catch (ResourceAccessException exception) {
            return DeliveryResult.retryable("telegram_transport_failure", Optional.empty());
        }
    }

    Optional<Duration> parseRetryAfter(HttpHeaders headers, Instant now) {
        if (headers == null) {
            return Optional.empty();
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim()))));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Optional.of(retryAt.isAfter(now) ? Duration.between(now, retryAt) : Duration.ZERO);
            } catch (DateTimeParseException invalidDate) {
                return Optional.empty();
            }
        }
    }

    public record DeliveryResult(Outcome outcome, String errorCategory, Optional<Duration> retryAfter) {
        static DeliveryResult delivered() {
            return new DeliveryResult(Outcome.DELIVERED, null, Optional.empty());
        }

        static DeliveryResult retryable(String category, Optional<Duration> retryAfter) {
            return new DeliveryResult(Outcome.RETRYABLE, category, retryAfter);
        }

        static DeliveryResult permanentFailure(String category) {
            return new DeliveryResult(Outcome.PERMANENT_FAILURE, category, Optional.empty());
        }
    }

    public enum Outcome {
        DELIVERED, RETRYABLE, PERMANENT_FAILURE
    }
}
