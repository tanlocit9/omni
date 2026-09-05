package com.omni.platform.modules.notifications.services;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.telegram.TelegramRendering.Registry;

class TelegramNotificationServiceTest {

    @Test
    void sendSkipsDeliveryWhenTelegramIsDisabled() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramNotificationService service = service(false, builder, Clock.systemUTC());

        service.send(request(NotificationType.OPERATIONAL, "Consumer failed", "Failed to process message"));

        server.expect(never(), requestTo("https://api.telegram.org/bottoken/sendMessage"));
        server.verify();
    }

    @Test
    void sendDeliversAudibleOperationalErrorWithFixedHtmlParseMode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andExpect(content().string(containsString("\"parse_mode\":\"HTML\"")))
                .andExpect(content().string(containsString("\"disable_notification\":false")))
                .andExpect(content().string(containsString("Consumer \u0026amp; \u0026lt;failed\u0026gt;")))
                .andExpect(content().string(containsString("Failed \u0026amp; \u0026lt;unsafe\u0026gt;")))
                .andExpect(content().string(not(containsString("Consumer & <failed>"))))
                .andExpect(content().string(not(containsString("Failed & <unsafe>"))))
                .andExpect(content().string(not(containsString("topic-sync-job-status"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service(true, builder, Clock.systemUTC())
                .send(request(NotificationType.OPERATIONAL, "Consumer & <failed>", "Failed & <unsafe>"));

        server.verify();
    }

    @Test
    void sendDeliversSilentTelegramMessageWhenConfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andExpect(content().string(containsString("\"disable_notification\":true")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        TelegramNotificationService service = service(true, builder, Clock.systemUTC());

        service.send(new NotificationRequest(
                NotificationType.OPERATIONAL,
                NotificationSeverity.INFO,
                "Consumer ready",
                "Ready",
                Map.of()));

        server.verify();
    }

    @Test
    void sendSuppressesDuplicatesForOperationalAndSignalNotifications() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        TelegramNotificationService service = service(true, builder, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        service.send(request(NotificationType.OPERATIONAL, "Job 123 failed", "first"));
        service.send(request(NotificationType.OPERATIONAL, " job 456   FAILED ", "duplicate"));
        service.send(request(NotificationType.SIGNAL, "Job 789 failed", "first signal"));
        service.send(request(NotificationType.SIGNAL, "job 999 failed", "duplicate signal"));

        server.verify();
    }

    @Test
    void sendAddsSuppressionSummaryAfterCooldownAndTruncatesFinalMessage() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andExpect(content().string(containsString("Repeated notifications suppressed: 2")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        TelegramNotificationService service = service(true, builder, clock);
        String longMessage = "x".repeat(5_000);

        service.send(request(NotificationType.OPERATIONAL, "failure 1", longMessage));
        service.send(request(NotificationType.OPERATIONAL, "failure 2", "duplicate"));
        service.send(request(NotificationType.OPERATIONAL, "failure 3", "duplicate"));
        clock.advance(Duration.ofMinutes(5));
        service.send(request(NotificationType.OPERATIONAL, "failure 4", "retained"));

        server.verify();
    }

    @Test
    void sendSwallowsTelegramDeliveryFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andRespond(withServerError());
        TelegramNotificationService service = service(true, builder, Clock.systemUTC());

        assertDoesNotThrow(() -> service.send(request(
                NotificationType.OPERATIONAL, "Consumer failed", "Failed to process message")));
        server.verify();
    }

    private TelegramNotificationService service(boolean enabled, RestClient.Builder builder, Clock clock) {
        TelegramNotificationProperties properties = new TelegramNotificationProperties(
                enabled, "token", "chat", "signals-chat", null,
                "https://api.telegram.org", Duration.ofMinutes(5), 100, null, null);
        return new TelegramNotificationService(properties, builder.build(), clock, new Registry(properties));
    }

    private NotificationRequest request(NotificationType type, String title, String message) {
        return new NotificationRequest(
                type,
                NotificationSeverity.ERROR,
                title,
                message,
                Map.of("topic", "topic-sync-job-status"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
