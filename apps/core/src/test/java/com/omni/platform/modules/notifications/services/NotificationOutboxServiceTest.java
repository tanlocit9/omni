package com.omni.platform.modules.notifications.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalChangedContent;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxClaim;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxRepository;
import com.omni.platform.shared.infrastructure.mapper.JsonMapperConfig;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    @Mock
    private NotificationOutboxRepository repository;

    private JsonMapper jsonMapper;
    private NotificationOutboxService service;

    @BeforeEach
    void setUp() {
        jsonMapper = new JsonMapperConfig().jsonMapper();
        service = new NotificationOutboxService(repository, jsonMapper);
    }

    @Test
    void enqueueUsesAtomicInsertAndReturnsCanonicalExistingRow() {
        NotificationRequest request = request("signal:one");
        NotificationOutboxMessage existing = new NotificationOutboxMessage();
        existing.setProvider(Provider.TELEGRAM);
        existing.setChannel(NotificationChannel.SIGNALS);
        existing.setNotificationKind(NotificationKind.SIGNAL_CHANGED);
        existing.setSchemaVersion(NotificationOutboxService.SCHEMA_VERSION);
        existing.setDeduplicationKey("signal:one");
        when(repository.findByProviderAndChannelAndDeduplicationKey(
                Provider.TELEGRAM, NotificationChannel.SIGNALS, "signal:one"))
                .thenReturn(Optional.of(existing));

        assertThat(service.enqueue(request, NOW)).isSameAs(existing);

        verify(repository).insertIfAbsent(
                Provider.TELEGRAM.name(),
                NotificationChannel.SIGNALS.name(),
                NotificationKind.SIGNAL_CHANGED.name(),
                NotificationOutboxService.SCHEMA_VERSION,
                jsonMapper.writeValueAsString(request),
                "signal:one",
                NOW);
    }

    @Test
    void decodeRoundTripsTypedStructuredContent() {
        NotificationRequest request = request("signal:round-trip");
        NotificationOutboxClaim claim = claim(
                NotificationOutboxService.SCHEMA_VERSION,
                jsonMapper.writeValueAsString(request),
                NotificationKind.SIGNAL_CHANGED,
                NotificationChannel.SIGNALS);

        NotificationRequest decoded = service.decode(claim);

        assertThat(decoded).isEqualTo(request);
        assertThat(decoded.structuredContent()).isInstanceOf(SignalChangedContent.class);
    }

    @Test
    void decodeFailsClosedForUnknownVersionAndEnvelopeMismatch() {
        NotificationRequest request = request("signal:invalid");
        String payload = jsonMapper.writeValueAsString(request);

        assertThatThrownBy(() -> service.decode(claim(
                99, payload, NotificationKind.SIGNAL_CHANGED, NotificationChannel.SIGNALS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported notification schema version");
        assertThatThrownBy(() -> service.decode(claim(
                NotificationOutboxService.SCHEMA_VERSION,
                payload,
                NotificationKind.SIGNAL_CHANGED,
                NotificationChannel.OPERATIONS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Persisted notification envelope does not match payload");
    }

    @Test
    void enqueueRejectsMissingOrOversizedDeliveryIdentity() {
        assertThatThrownBy(() -> service.enqueue(request(" "), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification deduplication key is required");
        assertThatThrownBy(() -> service.enqueue(request("x".repeat(513)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification deduplication key exceeds 512 characters");
        verify(repository, org.mockito.Mockito.never()).insertIfAbsent(
                any(), any(), any(), anyInt(), any(), any(), any());
    }

    private NotificationRequest request(String deduplicationKey) {
        return new NotificationRequest(
                NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.SIGNAL_CHANGED,
                NotificationSeverity.INFO,
                "Signal changed",
                "HOLD -> BUY",
                Map.of("source", "analyzer"),
                deduplicationKey,
                new SignalChangedContent(
                        "HOSE-FPT",
                        "HOLD",
                        "BUY",
                        126_500,
                        "2026-09-19",
                        0.75,
                        List.of("PRICE_ABOVE_MA50"),
                        "CONFIRMED_TREND_EQUALS",
                        "1d",
                        NOW));
    }

    private NotificationOutboxClaim claim(
            int schemaVersion,
            String payload,
            NotificationKind kind,
            NotificationChannel channel) {
        return new NotificationOutboxClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "platform-one",
                Provider.TELEGRAM,
                channel,
                kind,
                schemaVersion,
                payload,
                1);
    }
}
