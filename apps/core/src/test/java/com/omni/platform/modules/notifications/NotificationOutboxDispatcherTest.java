package com.omni.platform.modules.notifications;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.configs.NotificationOutboxProperties;
import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxClaim;
import com.omni.platform.modules.notifications.services.NotificationOutboxStore;
import com.omni.platform.modules.notifications.services.TelegramTransport;
import com.omni.platform.modules.notifications.services.TelegramTransport.DeliveryResult;
import com.omni.platform.modules.notifications.services.TelegramTransport.Outcome;

import io.micrometer.core.instrument.Timer;

class NotificationOutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final Duration RATE_LIMIT = Duration.ofMillis(200);

    private NotificationOutboxStore store;
    private TelegramTransport transport;
    private NotificationOutboxMetrics metrics;
    private Timer.Sample sample;
    private NotificationOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        store = mock(NotificationOutboxStore.class);
        transport = mock(TelegramTransport.class);
        metrics = mock(NotificationOutboxMetrics.class);
        sample = mock(Timer.Sample.class);
        when(metrics.start()).thenReturn(sample);
        dispatcher = new NotificationOutboxDispatcher(
                store,
                transport,
                new NotificationOutboxProperties(
                        Duration.ofSeconds(5),
                        new NotificationOutboxProperties.Claim(Duration.ofMinutes(2), 10),
                        3,
                        new NotificationOutboxProperties.Retry(Duration.ofSeconds(5), Duration.ofMinutes(1))),
                telegramProperties(),
                metrics,
                "platform-a");
    }

    @Test
    void deliveredClaimIsMarkedSentAndMeasured() {
        NotificationOutboxClaim claim = claim(1);
        NotificationRequest request = request();
        when(store.claimPending(NOW, "platform-a", Duration.ofMinutes(2), 10)).thenReturn(List.of(claim));
        when(store.acquireProviderPermit(Provider.TELEGRAM, NOW, RATE_LIMIT)).thenReturn(true);
        when(store.decode(claim)).thenReturn(request);
        when(transport.deliver(request)).thenReturn(result(Outcome.DELIVERED, null, Optional.empty()));
        when(store.markDelivered(eq(claim), any(Instant.class))).thenReturn(true);

        dispatcher.dispatchBatch(NOW);

        verify(store).markDelivered(eq(claim), any(Instant.class));
        verify(metrics).delivered(sample);
        verify(store, never()).scheduleRetry(any(), any(), any());
        verify(store, never()).markDead(any(), any(), any());
    }

    @Test
    void retryableFailureUsesBoundedBackoffAndMeasuresRetry() {
        NotificationOutboxClaim claim = claim(2);
        prepareDelivery(claim, result(Outcome.RETRYABLE, "telegram_http_503", Optional.empty()));
        when(store.scheduleRetry(eq(claim), any(Instant.class), eq("telegram_http_503"))).thenReturn(true);

        dispatcher.dispatchBatch(NOW);

        verify(store).scheduleRetry(eq(claim), any(Instant.class), eq("telegram_http_503"));
        verify(metrics).retried(sample);
        verify(store, never()).markDead(any(), any(), any());
    }

    @Test
    void retryAfterOverridesExponentialBackoff() {
        NotificationOutboxClaim claim = claim(1);
        prepareDelivery(claim, result(Outcome.RETRYABLE, "telegram_http_429", Optional.of(Duration.ofSeconds(37))));
        when(store.scheduleRetry(claim, NOW.plusSeconds(37), "telegram_http_429")).thenReturn(true);

        dispatcher.dispatchBatch(NOW);

        verify(store).scheduleRetry(claim, NOW.plusSeconds(37), "telegram_http_429");
        verify(metrics).retried(sample);
    }

    @Test
    void exhaustedRetryableFailureBecomesDead() {
        NotificationOutboxClaim claim = claim(3);
        prepareDelivery(claim, result(Outcome.RETRYABLE, "telegram_http_503", Optional.empty()));
        when(store.markDead(claim, NOW, "max_attempts_telegram_http_503")).thenReturn(true);

        dispatcher.dispatchBatch(NOW);

        verify(store).markDead(claim, NOW, "max_attempts_telegram_http_503");
        verify(metrics).dead(sample);
        verify(store, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void permanentFailureBecomesDeadWithoutRetry() {
        NotificationOutboxClaim claim = claim(1);
        prepareDelivery(claim, result(Outcome.PERMANENT_FAILURE, "telegram_http_400", Optional.empty()));
        when(store.markDead(claim, NOW, "telegram_http_400")).thenReturn(true);

        dispatcher.dispatchBatch(NOW);

        verify(store).markDead(claim, NOW, "telegram_http_400");
        verify(metrics).dead(sample);
        verify(store, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void unavailableProviderPermitDefersWithoutCallingTransport() {
        NotificationOutboxClaim claim = claim(1);
        when(store.claimPending(NOW, "platform-a", Duration.ofMinutes(2), 10)).thenReturn(List.of(claim));
        when(store.acquireProviderPermit(Provider.TELEGRAM, NOW, RATE_LIMIT)).thenReturn(false);
        when(store.scheduleRetry(claim, NOW.plus(RATE_LIMIT), "provider_rate_limited")).thenReturn(true);

        dispatcher.dispatchBatch(NOW);

        verify(store).scheduleRetry(claim, NOW.plus(RATE_LIMIT), "provider_rate_limited");
        verify(metrics).rateLimited(sample);
        verify(transport, never()).deliver(any());
        verify(store, never()).decode(any());
    }

    @Test
    void payloadDecodeFailureBecomesDeadWithoutProviderCall() {
        NotificationOutboxClaim claim = claim(1);
        when(store.claimPending(NOW, "platform-a", Duration.ofMinutes(2), 10)).thenReturn(List.of(claim));
        when(store.acquireProviderPermit(Provider.TELEGRAM, NOW, RATE_LIMIT)).thenReturn(true);
        when(store.decode(claim)).thenThrow(new IllegalArgumentException("unsupported schema"));
        when(store.markDead(claim, NOW, "payload_decode_failure")).thenReturn(true);

        dispatcher.dispatchBatch(NOW);

        verify(store).markDead(claim, NOW, "payload_decode_failure");
        verify(metrics).dead(sample);
        verify(transport, never()).deliver(any());
    }

    @Test
    void staleFencingResultDoesNotRecordSuccessfulOutcomeMetrics() {
        NotificationOutboxClaim delivered = claim(1);
        NotificationRequest request = request();
        when(store.claimPending(NOW, "platform-a", Duration.ofMinutes(2), 10)).thenReturn(List.of(delivered));
        when(store.acquireProviderPermit(Provider.TELEGRAM, NOW, RATE_LIMIT)).thenReturn(true);
        when(store.decode(delivered)).thenReturn(request);
        when(transport.deliver(request)).thenReturn(result(Outcome.DELIVERED, null, Optional.empty()));
        when(store.markDelivered(eq(delivered), any(Instant.class))).thenReturn(false);

        dispatcher.dispatchBatch(NOW);

        verify(store).markDelivered(eq(delivered), any(Instant.class));
        verify(metrics, never()).delivered(any());
        verify(metrics, never()).retried(any());
        verify(metrics, never()).dead(any());
    }

    @Test
    void staleRateLimitDecodeDeadPermanentDeadAndRetryTransitionsStayUnmeasured() {
        NotificationOutboxClaim rateLimited = claim(1);
        NotificationOutboxClaim decodeFailure = claim(1);
        NotificationOutboxClaim permanent = claim(1);
        NotificationOutboxClaim retryable = claim(1);
        when(store.claimPending(NOW, "platform-a", Duration.ofMinutes(2), 10))
                .thenReturn(List.of(rateLimited, decodeFailure, permanent, retryable));
        when(store.acquireProviderPermit(any(), eq(NOW), eq(RATE_LIMIT)))
                .thenReturn(false, true, true, true);
        when(store.decode(decodeFailure)).thenThrow(new IllegalArgumentException("bad payload"));
        when(store.decode(permanent)).thenReturn(request());
        when(store.decode(retryable)).thenReturn(request());
        when(transport.deliver(any())).thenReturn(
                result(Outcome.PERMANENT_FAILURE, "telegram_http_400", Optional.empty()),
                result(Outcome.RETRYABLE, "telegram_http_503", Optional.empty()));

        dispatcher.dispatchBatch(NOW);

        verify(store).scheduleRetry(rateLimited, NOW.plus(RATE_LIMIT), "provider_rate_limited");
        verify(store).markDead(decodeFailure, NOW, "payload_decode_failure");
        verify(store).markDead(permanent, NOW, "telegram_http_400");
        verify(store).scheduleRetry(eq(retryable), any(Instant.class), eq("telegram_http_503"));
        verify(metrics, never()).rateLimited(any());
        verify(metrics, never()).dead(any());
        verify(metrics, never()).retried(any());
    }

    @Test
    void blankInstanceIdUsesGeneratedPlatformIdentity() {
        dispatcher = new NotificationOutboxDispatcher(
                store,
                transport,
                new NotificationOutboxProperties(
                        Duration.ofSeconds(5),
                        new NotificationOutboxProperties.Claim(Duration.ofMinutes(2), 10),
                        3,
                        new NotificationOutboxProperties.Retry(Duration.ofMillis(1), Duration.ofMillis(1))),
                telegramProperties(),
                metrics,
                " ");
        when(store.claimPending(eq(NOW), any(String.class), eq(Duration.ofMinutes(2)), eq(10)))
                .thenReturn(List.of());

        dispatcher.dispatchBatch(NOW);

        verify(store).claimPending(eq(NOW), org.mockito.ArgumentMatchers.startsWith("platform-"),
                eq(Duration.ofMinutes(2)), eq(10));
    }

    private void prepareDelivery(NotificationOutboxClaim claim, DeliveryResult result) {
        NotificationRequest request = request();
        when(store.claimPending(NOW, "platform-a", Duration.ofMinutes(2), 10)).thenReturn(List.of(claim));
        when(store.acquireProviderPermit(Provider.TELEGRAM, NOW, RATE_LIMIT)).thenReturn(true);
        when(store.decode(claim)).thenReturn(request);
        when(transport.deliver(request)).thenReturn(result);
    }

    private static DeliveryResult result(Outcome outcome, String category, Optional<Duration> retryAfter) {
        return new DeliveryResult(outcome, category, retryAfter);
    }

    private static NotificationOutboxClaim claim(int attempts) {
        return new NotificationOutboxClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "platform-a",
                Provider.TELEGRAM,
                NotificationChannel.SIGNALS,
                NotificationKind.MANUAL_GENERIC,
                1,
                "{}",
                attempts);
    }

    private static NotificationRequest request() {
        return new NotificationRequest(
                NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.MANUAL_GENERIC,
                NotificationSeverity.INFO,
                "Title",
                "Message",
                Map.of(),
                "dispatcher-test",
                null);
    }

    private static TelegramNotificationProperties telegramProperties() {
        return new TelegramNotificationProperties(
                true,
                "unused-test-token",
                "unused-operations",
                "unused-signals",
                "HTML",
                "http://localhost",
                Duration.ofMinutes(5),
                100,
                "Asia/Bangkok",
                true,
                RATE_LIMIT,
                null);
    }
}
