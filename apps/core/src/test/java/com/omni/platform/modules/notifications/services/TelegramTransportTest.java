package com.omni.platform.modules.notifications.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class TelegramTransportTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    @Test
    void parsesRetryAfterDeltaSeconds() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "45");

        assertThat(transport().parseRetryAfter(headers, NOW)).contains(Duration.ofSeconds(45));
    }

    @Test
    void parsesRetryAfterHttpDateAndClampsPastDates() {
        HttpHeaders future = new HttpHeaders();
        future.set(HttpHeaders.RETRY_AFTER, "Sat, 19 Sep 2026 12:01:30 GMT");
        HttpHeaders past = new HttpHeaders();
        past.set(HttpHeaders.RETRY_AFTER, "Sat, 19 Sep 2026 11:59:00 GMT");

        assertThat(transport().parseRetryAfter(future, NOW)).contains(Duration.ofSeconds(90));
        assertThat(transport().parseRetryAfter(past, NOW)).contains(Duration.ZERO);
    }

    @Test
    void rejectsInvalidRetryAfterWithoutGuessing() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "later");

        assertThat(transport().parseRetryAfter(headers, NOW)).isEmpty();
        assertThat(transport().parseRetryAfter(null, NOW)).isEmpty();
    }

    private TelegramTransport transport() {
        return new TelegramTransport(null, null, null);
    }
}
