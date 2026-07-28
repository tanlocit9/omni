package com.omni.platform.modules.notifications.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;

class TelegramNotificationServiceTest {

    @Test
    void sendSkipsDeliveryWhenTelegramIsDisabled() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramNotificationService service = new TelegramNotificationService(
                new TelegramNotificationProperties(false, "token", "chat", null, "https://api.telegram.org"),
                builder.build());

        service.send(request());

        server.expect(never(), requestTo("https://api.telegram.org/bottoken/sendMessage"));
        server.verify();
    }

    @Test
    void sendDeliversTelegramMessageWhenConfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        TelegramNotificationService service = new TelegramNotificationService(
                new TelegramNotificationProperties(true, "token", "chat", null, "https://api.telegram.org"),
                builder.build());

        service.send(request());

        server.verify();
    }

    @Test
    void sendSwallowsTelegramDeliveryFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottoken/sendMessage"))
                .andRespond(withServerError());
        TelegramNotificationService service = new TelegramNotificationService(
                new TelegramNotificationProperties(true, "token", "chat", null, "https://api.telegram.org"),
                builder.build());

        assertDoesNotThrow(() -> service.send(request()));
        server.verify();
    }

    private NotificationRequest request() {
        return new NotificationRequest(
                NotificationType.OPERATIONAL,
                NotificationSeverity.ERROR,
                "Consumer failed",
                "Failed to process message",
                Map.of("topic", "topic-sync-job-status"));
    }
}
