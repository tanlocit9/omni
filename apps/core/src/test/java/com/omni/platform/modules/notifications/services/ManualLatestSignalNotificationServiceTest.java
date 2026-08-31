package com.omni.platform.modules.notifications.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class ManualLatestSignalNotificationServiceTest {

    @Test
    void forwardsNormalizedSymbolAndMapsAcceptedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://analyzer:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://analyzer:8000/v1/signals/notifications/latest?symbolKey=HOSE-ACB"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"accepted":true,"status":"ACCEPTED","symbolKey":"HOSE-ACB",
                         "newSignal":"BULLISH","signalDate":"2026-08-29",
                         "generatedAt":"2026-08-29T10:00:00+00:00"}
                        """, MediaType.APPLICATION_JSON));

        var result = new ManualLatestSignalNotificationService(builder.build())
                .sendLatest(" hose-acb ");

        assertThat(result.accepted()).isTrue();
        assertThat(result.symbolKey()).isEqualTo("HOSE-ACB");
        assertThat(result.newSignal()).isEqualTo("BULLISH");
        server.verify();
    }

    @Test
    void omitsBlankSymbolAndMapsNotFound() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://analyzer:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://analyzer:8000/v1/signals/notifications/latest"))
                .andRespond(withResourceNotFound());

        var service = new ManualLatestSignalNotificationService(builder.build());

        assertThatThrownBy(() -> service.sendLatest("   "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404);
        server.verify();
    }
}
